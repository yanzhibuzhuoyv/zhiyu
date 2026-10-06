import sqlite3
from contextlib import contextmanager

import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider, login

from app.body_codec import decode_body
from app.config import Settings
from app.conversation_memory import conversation_context_result, memory_terms
from app.main import create_app
from app.memory_management import MemoryManagement
from app.memory_schemas import MemoryEdit
from app.source_codec import ChatStorageError


@pytest.fixture
def setup(tmp_path):
    provider = FakeProvider()
    app = create_app(Settings(data_dir=tmp_path, auto_memory_annotations=True, memory_annotation_mode="literal"), provider_client=provider)
    with TestClient(app) as client:
        headers = login(client)
        owner = app.state.sessions.get(headers["Authorization"][7:]).owner
        cid = app.state.chat.create_conversation(owner, "恋人", "test-model", False)["id"]
        yield app.state.chat, owner, cid, client, headers, provider


def cool(chat, owner, cid, message):
    return MemoryManagement(chat).update(owner, cid, message["id"],
                                          MemoryEdit(expected_revision=0, index_state="cold"))


def bury(chat, owner, cid, count=25):
    for index in range(count):
        chat.save_turn(owner, cid, f"日常散步第{index}次", "轻松休息", [])


def test_mixed_pair_retains_cold_ai_matching_middle_when_user_is_hot(setup):
    chat, owner, cid, *_ = setup
    first = chat.save_turn(owner, cid, "暮色暗号", "平常回应" * 600 + "暮色暗号就是紫荆枝", [])
    cool(chat, owner, cid, first["assistant_message"])
    bury(chat, owner, cid)
    result = conversation_context_result(chat, owner, cid, "暮色暗号")
    assert any(ref["message_id"] == first["assistant_message"]["id"]
               and "紫荆枝" in ref["text"] and ref["char_start"] > 0 for ref in result.references)


def test_hot_hit_does_not_prevent_older_cold_user_and_ai_recovery(setup):
    chat, owner, cid, *_ = setup
    first = chat.save_turn(owner, cid, "归档暮色书签", "归档暮色回复", [])
    for message in first.values():
        cool(chat, owner, cid, message)
    second = chat.save_turn(owner, cid, "暮色新书签", "新回复", [])
    bury(chat, owner, cid)
    result = conversation_context_result(chat, owner, cid, "暮色书签")
    assert {first["user_message"]["id"], second["user_message"]["id"]} <= {
        ref["message_id"] for ref in result.references}


def test_superseded_specific_birthday_retrieves_current_correction_and_raw_old_source(setup):
    chat, owner, cid, *_ = setup
    old = chat.save_turn(owner, cid, "我的生日是2000-03-06", "收到", [])
    new = chat.save_turn(owner, cid, "更正：我的生日是2001-04-07", "收到更正", [])
    bury(chat, owner, cid)
    result = conversation_context_result(chat, owner, cid, "2000")
    refs = {ref["message_id"]: ref for ref in result.references}
    assert old["user_message"]["id"] in refs and new["user_message"]["id"] in refs
    assert refs[old["user_message"]["id"]]["source_current"] is False
    assert refs[new["user_message"]["id"]]["source_current"] is True
    assert chat.list_messages(owner, cid)[:4] == list(old.values()) + list(new.values())


def test_direct_index_call_cannot_rebuild_cold_source(setup):
    chat, owner, cid, *_ = setup
    first = chat.save_turn(owner, cid, "书签原话" * 1000, "原回复", [])
    cool(chat, owner, cid, first["user_message"])
    with chat.connection() as db:
        seq = db.execute("SELECT sequence FROM messages WHERE id=?", (first["user_message"]["id"],)).fetchone()[0]
        chat.memory.index_message(db, seq, cid, first["user_message"]["content"])
        assert db.execute("SELECT count(*) FROM memory_fragments WHERE message_sequence=?", (seq,)).fetchone()[0] == 0


def test_archive_and_manual_change_fault_rolls_back_every_metadata_and_index_write(setup):
    chat, owner, cid, client, headers, _ = setup
    first = chat.save_turn(owner, cid, "待归档书签", "原回复", [])
    endpoint = f"/api/conversations/{cid}/memory/messages/{first['user_message']['id']}"
    before = client.get(endpoint, headers=headers).json()
    with chat.connection() as db:
        db.execute("""CREATE TRIGGER integration_cold_fail BEFORE INSERT ON memory_cold_directory
            BEGIN SELECT RAISE(ABORT,'PRIVATE-FAILURE'); END""")
        fragments = [tuple(row) for row in db.execute("SELECT * FROM memory_fragments ORDER BY id")]
    response = client.put(endpoint, headers=headers,
                          json={"tier": "mid", "index_state": "cold", "expected_revision": 0})
    assert response.status_code == 503 and "PRIVATE" not in response.text
    assert client.get(endpoint, headers=headers).json() == before
    with chat.connection() as db:
        assert [tuple(row) for row in db.execute("SELECT * FROM memory_fragments ORDER BY id")] == fragments
        assert db.execute("SELECT count(*) FROM memory_manual_tiers").fetchone()[0] == 0


def test_save_annotation_commit_failure_rolls_back_original_and_derived_state(setup, monkeypatch):
    chat, owner, cid, *_ = setup
    with chat.connection() as db:
        db.execute("CREATE TABLE memory_commit_fault (seq REFERENCES messages(sequence) DEFERRABLE INITIALLY DEFERRED)")
    original_connection = chat.connection

    @contextmanager
    def fault():
        with original_connection() as db:
            yield db
            db.execute("INSERT INTO memory_commit_fault VALUES(2147483647)")

    monkeypatch.setattr(chat, "connection", fault)
    with pytest.raises(ChatStorageError):
        chat.save_turn(owner, cid, "我的生日是3月6日", "收到", [])
    monkeypatch.setattr(chat, "connection", original_connection)
    with chat.connection() as db:
        assert db.execute("SELECT count(*) FROM messages").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM memory_fragments").fetchone()[0] == 0


def test_incomplete_scan_reaches_normal_chat_prompt_and_response(setup, monkeypatch):
    chat, owner, cid, client, headers, provider = setup
    chat.save_turn(owner, cid, "旧书签", "旧回复", [])
    bury(chat, owner, cid)
    monkeypatch.setattr(chat.cold, "search", lambda *args, **kwargs: {
        "turns": [], "complete": False, "next_before": 17})
    response = client.post(f"/api/conversations/{cid}/messages", headers=headers, json={"content": "星河"})
    assert response.status_code == 200
    assert response.json()["memory_search"] == {"complete": False, "next_cursor": 17}
    assert "不能断言记忆不存在" in provider.calls[-1][0]["content"]


def test_management_read_sql_failure_is_safe_503(setup, monkeypatch):
    chat, _, cid, client, headers, _ = setup

    @contextmanager
    def fault():
        raise sqlite3.OperationalError("PRIVATE-STORAGE-PATH")
        yield

    monkeypatch.setattr(chat, "connection", fault)
    response = client.get(f"/api/conversations/{cid}/memory", headers=headers)
    assert response.status_code == 503 and "PRIVATE" not in response.text


def test_new_long_tier_restores_cold_original_index(setup):
    chat, owner, cid, *_ = setup
    first = chat.save_turn(owner, cid, "琉璃原文" * 500, "原回复", [])
    state = cool(chat, owner, cid, first["user_message"])
    result = MemoryManagement(chat).update(owner, cid, first["user_message"]["id"],
                                           MemoryEdit(expected_revision=state["revision"], tier="long"))
    assert result["index_state"] == "active" and result["tier"] == "long"
    with chat.connection() as db:
        seq = db.execute("SELECT sequence FROM messages WHERE id=?", (result["message_id"],)).fetchone()[0]
        assert db.execute("SELECT count(*) FROM memory_fragments WHERE message_sequence=?", (seq,)).fetchone()[0] > 1
        assert decode_body(db.execute("SELECT content FROM messages WHERE sequence=?", (seq,)).fetchone()[0]) == first["user_message"]["content"]
    assert chat.memory_lookup(owner, cid, 3, memory_terms("琉璃"), 256, 768)["turns"]


def test_confirm_promoted_topic_restores_all_protected_cold_sources(setup):
    from datetime import UTC, datetime, timedelta

    chat, owner, cid, *_ = setup
    originals = []
    for days in (10, 7, 4):
        original = chat.save_turn(owner, cid, "我想读星河", "收到", [])
        originals.append(original["user_message"])
        with chat.connection() as db:
            db.execute("UPDATE messages SET created_at=? WHERE id=?",
                       ((datetime.now(UTC) - timedelta(days=days)).isoformat(), original["user_message"]["id"]))
        cool(chat, owner, cid, original["user_message"])
    for message in originals:
        MemoryManagement(chat).update(owner, cid, message["id"],
                                       MemoryEdit(expected_revision=1, confirm_independent=True))
    with chat.connection() as db:
        assert db.execute("SELECT count(*) FROM memory_index_state WHERE state='cold'").fetchone()[0] == 0
    assert {row["tier"] for row in MemoryManagement(chat).page(owner, cid, 20)["messages"] if row["role"] == "user"} == {"long"}


def test_expired_and_empty_cold_search_are_explicit_client_errors(setup):
    _, _, cid, client, headers, _ = setup
    url = f"/api/conversations/{cid}/memory/search"
    assert client.post(url, headers=headers, json={"query": "星河", "cursor": 9999999}).status_code == 409
    assert client.post(url, headers=headers, json={"query": "你还记得吗"}).status_code == 422


def test_oversized_pair_keeps_hot_index_and_source(setup):
    chat, owner, cid, *_ = setup
    original = chat.save_turn(owner, cid, "超长原文" * 270000, "收到", [])
    from app.memory_management import MemoryConflict

    with pytest.raises(MemoryConflict):
        cool(chat, owner, cid, original["user_message"])
    assert MemoryManagement(chat).get(owner, cid, original["user_message"]["id"])["index_state"] == "active"


def test_tier_change_commit_failure_keeps_original_and_metadata(setup, monkeypatch):
    chat, owner, cid, *_ = setup
    original = chat.save_turn(owner, cid, "星河原文", "收到", [])
    with chat.connection() as db:
        db.execute("CREATE TABLE memory_commit_fault (seq REFERENCES messages(sequence) DEFERRABLE INITIALLY DEFERRED)")
    before = MemoryManagement(chat).get(owner, cid, original["user_message"]["id"])
    original_connection = chat.connection

    @contextmanager
    def fault():
        with original_connection() as db:
            yield db
            db.execute("INSERT INTO memory_commit_fault VALUES(2147483647)")

    monkeypatch.setattr(chat, "connection", fault)
    with pytest.raises(ChatStorageError):
        MemoryManagement(chat).update(owner, cid, original["user_message"]["id"],
                                       MemoryEdit(expected_revision=0, tier="permanent"))
    monkeypatch.setattr(chat, "connection", original_connection)
    assert MemoryManagement(chat).get(owner, cid, original["user_message"]["id"]) == before
