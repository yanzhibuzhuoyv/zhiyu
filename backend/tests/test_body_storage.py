"""Production body storage preserves originals across history, RAG and migration."""

from contextlib import contextmanager
from datetime import datetime, timedelta

import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider, login

from app.config import Settings
from app.conversation_memory import conversation_context, memory_terms, recent_history
from app.database import ChatDatabase
from app.main import create_app
from app.memory_ranking import EventWindow
from app.source_codec import ChatStorageError


@pytest.fixture
def body_setup(tmp_path):
    provider = FakeProvider()
    app = create_app(Settings(data_dir=tmp_path), provider_client=provider)
    with TestClient(app) as client:
        headers = login(client)
        owner = app.state.sessions.get(headers["Authorization"][7:]).owner
        chat = app.state.chat
        identifier = chat.create_conversation(owner, "恋人", "test-model", False)["id"]
        yield client, headers, owner, identifier, chat, provider


def long_bodies():
    user = "🌸普通风景 e\u0301。" * 550 + "琉璃沙漏的花纹是紫荆枝。" + "🌙普通日记。" * 400 + "青桥月色的暗号是笺影。"
    assistant = "🫂平常回应。" * 600 + "暮云长廊的诗句是菰叶清声。" + "👩‍❤️‍👩轻松交谈。" * 400 + "海棠信笺的结尾是蜡梅。"
    return user, assistant


def raw_rows(chat, identifier):
    with chat.connection() as db:
        return [tuple(row) for row in db.execute(
            "SELECT sequence,id,conversation_id,role,content,sources,created_at FROM messages "
            "WHERE conversation_id=? ORDER BY sequence", (identifier,))]


def index_rows(chat):
    with chat.connection() as db:
        fragments = [tuple(row) for row in db.execute("SELECT * FROM memory_fragments ORDER BY id")]
        postings = [row[0] for row in db.execute("SELECT rowid FROM memory_fts ORDER BY rowid")]
    return fragments, postings


def seed_legacy(chat, owner, identifier, count=2):
    saved = []
    for index in range(count):
        user, assistant = long_bodies()
        turn = chat.save_turn(owner, identifier, user + str(index), assistant + str(index), [])
        with chat.connection() as db:
            for message in turn.values():
                db.execute("UPDATE messages SET content=? WHERE id=?", (message["content"], message["id"]))
        saved.extend(turn.values())
    return saved


def test_saved_bodies_are_blobs_but_api_returns_exact_unicode_and_sources(body_setup):
    client, headers, owner, identifier, chat, _ = body_setup
    user, assistant = long_bodies()
    sources = [{"name": "共同片段", "text": "独立来源原文🌸", "score": 0.7}]
    saved = chat.save_turn(owner, identifier, user, assistant, sources)
    stored = raw_rows(chat, identifier)
    assert all(isinstance(row[4], bytes) for row in stored)
    assert sum(len(row[4]) for row in stored) < (len(user.encode()) + len(assistant.encode())) / 2
    for endpoint in ("messages", "history"):
        response = client.get(f"/api/conversations/{identifier}/{endpoint}", headers=headers)
        assert response.status_code == 200, response.text
        messages = response.json() if endpoint == "messages" else response.json()["messages"]
        assert messages == list(saved.values())


def test_mixed_text_and_blob_restart_pages_keep_order_ids_and_owner_scope(body_setup, tmp_path):
    client, headers, owner, identifier, chat, _ = body_setup
    originals = seed_legacy(chat, owner, identifier)
    originals.extend(chat.save_turn(owner, identifier, *long_bodies(), []).values())
    assert [isinstance(row[4], bytes) for row in raw_rows(chat, identifier)] == [False] * 4 + [True] * 2
    app = create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())
    with TestClient(app) as restarted:
        restored = login(restarted)
        foreign = login(restarted, "foreign-key")
        before, accumulated = None, []
        while True:
            params = {"limit": 3, **({"before": before} if before else {})}
            response = restarted.get(f"/api/conversations/{identifier}/history", headers=restored, params=params)
            assert response.status_code == 200, response.text
            page = response.json()
            accumulated = page["messages"] + accumulated
            if not page["has_more"]:
                break
            before = page["next_before"]
        assert accumulated == originals
        assert len({row["id"] for row in accumulated}) == 6
        assert restarted.get(f"/api/conversations/{identifier}/history", headers=foreign).status_code == 404
        assert app.state.chat.recent_turns("wrong-owner", identifier, 20, 48000) == []
        assert app.state.chat.memory_candidates("wrong-owner", identifier, 10**9,
                                                memory_terms("琉璃沙漏"), 256, 768) == []


def test_recent_lengths_and_budget_use_decoded_unicode_characters(body_setup):
    _, _, owner, identifier, chat, _ = body_setup
    older = chat.save_turn(owner, identifier, "💖汉" * 15000, "🌙字" * 10000, [])
    latest = chat.save_turn(owner, identifier, "最近问候", "最近回应", [])
    turns = chat.recent_turns(owner, identifier, 20, 1536)
    assert turns[1]["user_length"] == len(older["user_message"]["content"])
    assert turns[1]["assistant_length"] == len(older["assistant_message"]["content"])
    assert turns[1]["user_text"] == older["user_message"]["content"][:1536]
    assert turns[1]["assistant_text"] == older["assistant_message"]["content"][:1536]
    history, before = recent_history(turns)
    assert history == [{"role": message["role"], "content": message["content"]} for message in latest.values()]
    assert before == turns[0]["sequence"]


@pytest.mark.parametrize("query,fact,role", [
    ("琉璃沙漏", "紫荆枝", "user"), ("青桥月色", "笺影", "user"),
    ("暮云长廊", "菰叶清声", "assistant"), ("海棠信笺", "蜡梅", "assistant"),
])
def test_rag_uses_decoded_middle_tail_offsets_and_keeps_original_provenance(body_setup, query, fact, role):
    _, _, owner, identifier, chat, _ = body_setup
    saved = chat.save_turn(owner, identifier, *long_bodies(), [])
    assert all(isinstance(row[4], bytes) for row in raw_rows(chat, identifier))
    for _ in range(25):
        chat.save_turn(owner, identifier, "日常散步。", "轻松休息。", [])
    _, refs = conversation_context(chat, owner, identifier, query)
    match = next(ref for ref in refs if ref["role"] == role and fact in ref["text"])
    assert match["message_id"] == saved[f"{role}_message"]["id"]
    assert match["created_at"] == saved[f"{role}_message"]["created_at"]
    assert match["turn_id"] == saved["user_message"]["id"]
    assert match["truncated"] is True
    assert all(ref["text"] in saved[f"{ref['role']}_message"]["content"] for ref in refs)


def test_event_candidates_decode_compressed_bodies_at_original_offsets(body_setup):
    _, _, owner, identifier, chat, _ = body_setup
    user = "🌸普通风景。" * 900 + "今天是我的生日，我吃了芒果蛋糕。"
    saved = chat.save_turn(owner, identifier, user, "🫂轻声回应。" * 700 + "生日快乐。", [])
    now = datetime.fromisoformat(saved["user_message"]["created_at"])
    window = EventWindow(saved["user_message"]["id"], 1, now, now - timedelta(hours=1), now + timedelta(hours=1))
    candidates = chat.memory_event_candidates(owner, identifier, 10**9, [window])
    assert len(candidates) == 1
    candidate = candidates[0]
    assert "芒果蛋糕" in candidate["user_text"]
    for role in ("user", "assistant"):
        original = saved[f"{role}_message"]
        assert candidate[f"{role}_text"] in original["content"]
        assert candidate[f"{role}_length"] == len(original["content"])
        assert candidate[f"{role}_id"] == original["id"]
        assert candidate[f"{role}_created_at"] == original["created_at"]


def test_compressed_originals_can_backfill_missing_index_on_restart(body_setup, tmp_path):
    _, _, owner, identifier, chat, _ = body_setup
    saved = chat.save_turn(owner, identifier, *long_bodies(), [])
    assert all(isinstance(row[4], bytes) for row in raw_rows(chat, identifier))
    expected = index_rows(chat)
    with chat.connection() as db:
        db.execute("DELETE FROM memory_fragments")
        assert db.execute("SELECT count(*) FROM memory_fts").fetchone()[0] == 0
    restored = ChatDatabase(tmp_path)
    actual = index_rows(restored)
    assert actual == expected
    assert restored.list_messages(owner, identifier) == list(saved.values())
    found = restored.memory_candidates(owner, identifier, 10**9, memory_terms("海棠信笺"), 256, 768)
    assert "蜡梅" in found[0]["assistant_text"]
    ChatDatabase(tmp_path)
    assert index_rows(restored) == actual


def test_second_body_encoding_failure_rolls_back_both_messages_and_index(body_setup, monkeypatch):
    _, _, owner, identifier, chat, _ = body_setup
    calls = []

    def encode(text):
        calls.append(text)
        if len(calls) == 2:
            raise ChatStorageError("SENSITIVE-ENCODING-FAILURE")
        return text

    before = chat.get_conversation(owner, identifier)
    monkeypatch.setattr("app.database.encode_body", encode, raising=False)
    with pytest.raises(ChatStorageError):
        chat.save_turn(owner, identifier, *long_bodies(), [])
    assert len(calls) == 2
    assert raw_rows(chat, identifier) == []
    assert index_rows(chat) == ([], [])
    assert chat.get_conversation(owner, identifier) == before


@pytest.mark.parametrize("route", ["messages", "history"])
def test_corrupt_body_history_is_safe_503_without_original_bytes(body_setup, route):
    client, headers, owner, identifier, chat, _ = body_setup
    saved = chat.save_turn(owner, identifier, *long_bodies(), [])
    with chat.connection() as db:
        db.execute("UPDATE messages SET content=? WHERE id=?", (b"SENSITIVE-CORRUPTED-BODY", saved["user_message"]["id"]))
    with pytest.raises(ChatStorageError):
        chat.list_messages(owner, identifier)
    response = client.get(f"/api/conversations/{identifier}/{route}", headers=headers)
    assert response.status_code == 503
    assert "SENSITIVE" not in response.text


def test_corrupt_recent_body_fails_before_provider_or_any_new_write(body_setup):
    client, headers, owner, identifier, chat, provider = body_setup
    saved = chat.save_turn(owner, identifier, *long_bodies(), [])
    with chat.connection() as db:
        db.execute("UPDATE messages SET content=? WHERE id=?", (b"SENSITIVE-CORRUPTED-BODY", saved["assistant_message"]["id"]))
    before = raw_rows(chat, identifier), index_rows(chat)
    with pytest.raises(ChatStorageError):
        chat.recent_turns(owner, identifier, 20, 48000)
    response = client.post(f"/api/conversations/{identifier}/messages", headers=headers, json={"content": "继续陪我聊天"})
    assert response.status_code == 503 and "SENSITIVE" not in response.text
    assert provider.calls == []
    assert (raw_rows(chat, identifier), index_rows(chat)) == before


def test_corrupt_archived_rag_body_fails_safely_instead_of_returning_garbage(body_setup):
    client, headers, owner, identifier, chat, provider = body_setup
    saved = chat.save_turn(owner, identifier, *long_bodies(), [])
    for _ in range(25):
        chat.save_turn(owner, identifier, "日常散步。", "轻松休息。", [])
    with chat.connection() as db:
        db.execute("UPDATE messages SET content=? WHERE id=?", (b"SENSITIVE-CORRUPTED-BODY", saved["user_message"]["id"]))
    with pytest.raises(ChatStorageError):
        chat.memory_candidates(owner, identifier, 3, memory_terms("琉璃沙漏"), 256, 768)
    response = client.post(f"/api/conversations/{identifier}/messages", headers=headers, json={"content": "琉璃沙漏的花纹是什么"})
    assert response.status_code == 503 and "SENSITIVE" not in response.text
    assert provider.calls == []


def test_batched_body_migration_preserves_metadata_indexes_isolation_and_is_idempotent(body_setup):
    _, _, owner, identifier, chat, _ = body_setup
    expected = seed_legacy(chat, owner, identifier)
    foreign = chat.create_conversation("foreign-owner", "他人", "model", False)["id"]
    seed_legacy(chat, "foreign-owner", foreign, 1)
    untouched = raw_rows(chat, foreign)
    before = raw_rows(chat, identifier), index_rows(chat)
    assert chat.migrate_bodies("foreign-owner", identifier) is None
    assert (raw_rows(chat, identifier), index_rows(chat)) == before
    first = chat.migrate_bodies(owner, identifier, batch_rows=3)
    assert first["visited"] == first["converted"] == 3
    assert first["after"] == before[0][2][0] and not first["complete"]
    second = chat.migrate_bodies(owner, identifier, after=first["after"], batch_rows=3)
    assert second["visited"] == second["converted"] == 1 and second["complete"]
    assert chat.list_messages(owner, identifier) == expected
    assert index_rows(chat) == before[1]
    assert raw_rows(chat, foreign) == untouched
    migrated = raw_rows(chat, identifier)
    assert all(isinstance(row[4], bytes) for row in migrated)
    assert all(old[:4] + old[5:] == new[:4] + new[5:] for old, new in zip(before[0], migrated, strict=True))
    again = chat.migrate_bodies(owner, identifier)
    assert again["visited"] == again["converted"] == 0 and again["complete"]
    assert raw_rows(chat, identifier) == migrated and index_rows(chat) == before[1]


def test_migration_budget_cursor_tracks_processed_rows_and_progresses_uncompressed_text(body_setup):
    _, _, owner, identifier, chat, _ = body_setup
    seed_legacy(chat, owner, identifier)
    rows = raw_rows(chat, identifier)
    budget = 2 * len(rows[0][4].encode())
    result = chat.migrate_bodies(owner, identifier, batch_rows=4, byte_limit=budget)
    assert result["visited"] == 1 and result["after"] == rows[0][0]
    assert result["oversized_row"] is False and result["complete"] is False
    result = chat.migrate_bodies(owner, identifier, after=result["after"], byte_limit=1024)
    assert result["visited"] == 1 and result["after"] == rows[1][0]
    assert result["oversized_row"] is True
    short = chat.save_turn(owner, identifier, "短", "短回应", [])
    with chat.connection() as db:
        after = db.execute("SELECT sequence FROM messages WHERE id=?", (short["user_message"]["id"],)).fetchone()[0] - 1
    result = chat.migrate_bodies(owner, identifier, after=after, batch_rows=1)
    assert result["visited"] == 1 and result["converted"] == 0
    assert result["after"] == after + 1 and result["complete"] is False
    result = chat.migrate_bodies(owner, identifier, after=result["after"])
    assert result["visited"] == 1 and result["converted"] == 0 and result["complete"]


def test_migration_encoding_fault_rolls_back_the_whole_batch(body_setup, monkeypatch):
    from app.body_codec import encode_body

    _, _, owner, identifier, chat, _ = body_setup
    seed_legacy(chat, owner, identifier)
    before = raw_rows(chat, identifier), index_rows(chat)
    calls = []

    def encode(text):
        calls.append(text)
        if len(calls) == 2:
            raise ChatStorageError("SENSITIVE-ENCODING-FAILURE")
        return encode_body(text)

    monkeypatch.setattr("app.body_migration.encode_body", encode)
    with pytest.raises(ChatStorageError):
        chat.migrate_bodies(owner, identifier, batch_rows=4)
    assert len(calls) == 2
    assert (raw_rows(chat, identifier), index_rows(chat)) == before


@pytest.mark.parametrize("arguments", [
    {"after": -1}, {"after": True}, {"batch_rows": 0}, {"batch_rows": 129},
    {"batch_rows": True}, {"byte_limit": 1023}, {"byte_limit": 8 * 1024 * 1024 + 1},
    {"after": 3, "through": 2}, {"through": True},
])
def test_migration_rejects_invalid_cursor_batch_budget_or_boundary_before_writing(body_setup, arguments):
    _, _, owner, identifier, chat, _ = body_setup
    seed_legacy(chat, owner, identifier, 1)
    before = raw_rows(chat, identifier), index_rows(chat)
    with pytest.raises(ValueError):
        chat.migrate_bodies(owner, identifier, **arguments)
    assert (raw_rows(chat, identifier), index_rows(chat)) == before


def test_migration_through_boundary_excludes_later_legacy_rows(body_setup):
    _, _, owner, identifier, chat, _ = body_setup
    first = seed_legacy(chat, owner, identifier, 1)
    boundary = raw_rows(chat, identifier)[-1][0]
    later = seed_legacy(chat, owner, identifier, 1)
    before = raw_rows(chat, identifier)
    indexes = index_rows(chat)
    result = chat.migrate_bodies(owner, identifier, through=boundary)
    assert result["through"] == result["after"] == boundary
    assert result["visited"] == result["converted"] == 2 and result["complete"]
    after = raw_rows(chat, identifier)
    assert all(isinstance(row[4], bytes) for row in after[:2])
    assert after[2:] == before[2:]
    assert chat.list_messages(owner, identifier) == first + later
    assert index_rows(chat) == indexes
    again = chat.migrate_bodies(owner, identifier, after=boundary, through=boundary)
    assert again["visited"] == again["converted"] == 0 and again["complete"]


def test_migration_sql_fault_rolls_back_complete_batch_and_safe_retry(body_setup):
    _, _, owner, identifier, chat, _ = body_setup
    seed_legacy(chat, owner, identifier)
    before = raw_rows(chat, identifier), index_rows(chat)
    blocked = before[0][1][0]
    with chat.connection() as db:
        db.execute(f"""CREATE TRIGGER body_test_update_fail BEFORE UPDATE OF content ON messages
            WHEN old.sequence={blocked} BEGIN SELECT RAISE(ABORT, 'SENSITIVE-SQL-FAILURE'); END""")
    with pytest.raises(ChatStorageError) as error:
        chat.migrate_bodies(owner, identifier)
    assert "SENSITIVE" not in str(error.value)
    assert (raw_rows(chat, identifier), index_rows(chat)) == before
    with chat.connection() as db:
        db.execute("DROP TRIGGER body_test_update_fail")
    result = chat.migrate_bodies(owner, identifier)
    assert result["visited"] == result["converted"] == 4 and result["complete"]
    assert index_rows(chat) == before[1]


def test_migration_rejects_valid_encoded_payload_that_changes_the_original(body_setup, monkeypatch):
    from app.body_codec import encode_body

    _, _, owner, identifier, chat, _ = body_setup
    seed_legacy(chat, owner, identifier, 1)
    before = raw_rows(chat, identifier), index_rows(chat)
    monkeypatch.setattr("app.body_migration.encode_body", lambda text: encode_body(text + "更改"))
    with pytest.raises(ChatStorageError):
        chat.migrate_bodies(owner, identifier)
    assert (raw_rows(chat, identifier), index_rows(chat)) == before


def test_rag_rejects_fragment_offsets_outside_decoded_original(body_setup):
    _, _, owner, identifier, chat, _ = body_setup
    saved = chat.save_turn(owner, identifier, *long_bodies(), [])
    with chat.connection() as db:
        sequence = db.execute("SELECT sequence FROM messages WHERE id=?", (saved["user_message"]["id"],)).fetchone()[0]
        db.execute("""UPDATE memory_fragments SET char_start=char_start+100000,char_end=char_end+100000
            WHERE message_sequence=?""", (sequence,))
    with pytest.raises(ChatStorageError):
        chat.memory_candidates(owner, identifier, 3, memory_terms("琉璃沙漏"), 256, 768)


def test_migration_commit_fault_rolls_back_rows_and_index_and_allows_retry(body_setup, monkeypatch):
    _, _, owner, identifier, chat, _ = body_setup
    seed_legacy(chat, owner, identifier)
    before = raw_rows(chat, identifier), index_rows(chat)
    with chat.connection() as db:
        db.execute("""CREATE TABLE body_test_commit_fault (
            message_sequence INTEGER REFERENCES messages(sequence) DEFERRABLE INITIALLY DEFERRED)""")
    original_connection = chat.connection

    @contextmanager
    def deferred_commit_fault():
        with original_connection() as db:
            yield db
            db.execute("INSERT INTO body_test_commit_fault VALUES(2147483647)")

    monkeypatch.setattr(chat, "connection", deferred_commit_fault)
    with pytest.raises(ChatStorageError) as error:
        chat.migrate_bodies(owner, identifier)
    assert "FOREIGN KEY" not in str(error.value)
    monkeypatch.setattr(chat, "connection", original_connection)
    assert (raw_rows(chat, identifier), index_rows(chat)) == before
    with chat.connection() as db:
        assert db.execute("SELECT count(*) FROM body_test_commit_fault").fetchone()[0] == 0
    result = chat.migrate_bodies(owner, identifier)
    assert result["visited"] == result["converted"] == 4 and result["complete"]


def test_history_does_not_decode_corrupt_lookahead_outside_returned_page(body_setup):
    client, headers, owner, identifier, chat, _ = body_setup
    originals = []
    for index in range(51):
        originals.extend(chat.save_turn(owner, identifier, f"问候{index}", f"回应{index}", []).values())
    with chat.connection() as db:
        db.execute("UPDATE messages SET content=? WHERE id=?", (b"SENSITIVE-CORRUPTED-LOOKAHEAD", originals[1]["id"]))
    response = client.get(f"/api/conversations/{identifier}/history", headers=headers, params={"limit": 100})
    assert response.status_code == 200, response.text
    page = response.json()
    assert page["messages"] == originals[2:] and page["has_more"]
    assert page["next_before"] == originals[2]["id"]
    response = client.get(f"/api/conversations/{identifier}/history", headers=headers,
                          params={"limit": 100, "before": page["next_before"]})
    assert response.status_code == 503 and "SENSITIVE" not in response.text
