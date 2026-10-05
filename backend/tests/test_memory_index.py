import sqlite3
from contextlib import closing

import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider, login

from app.config import Settings
from app.conversation_memory import conversation_context
from app.database import ChatDatabase
from app.main import create_app
from app.memory_index import BACKFILL_BATCH, CHUNK_CHARS, MemoryIndex, MemoryIndexError


@pytest.fixture
def indexed_chat(tmp_path):
    chat = ChatDatabase(tmp_path)
    identifier = chat.create_conversation("owner", "恋人", "model", False)["id"]
    return chat, identifier


def fragments(chat):
    with chat.connection() as db:
        return [dict(row) for row in db.execute("SELECT * FROM memory_fragments ORDER BY id")]


def bury(chat, identifier, count=25):
    for _ in range(count):
        chat.save_turn("owner", identifier, "日常散步。", "轻松休息。", [])


def test_each_saved_role_is_immediately_indexed_without_an_original_text_copy(indexed_chat):
    chat, identifier = indexed_chat
    saved = chat.save_turn("owner", identifier, "用户私密生日是九月十一日。", "AI共同诗句是绿岸微灯。", [])
    with chat.connection() as db:
        rows = db.execute("SELECT m.id, m.role, f.char_start, f.char_end FROM memory_fragments f JOIN messages m ON m.sequence=f.message_sequence ORDER BY m.sequence").fetchall()
        assert [row["id"] for row in rows] == [saved["user_message"]["id"], saved["assistant_message"]["id"]]
        assert [row["role"] for row in rows] == ["user", "assistant"]
        assert list(db.execute("SELECT tokens FROM memory_fts"))[0]["tokens"] is None
        assert {row["name"] for row in db.execute("PRAGMA table_info(memory_fragments)")} == {
            "id", "message_sequence", "char_start", "char_end"}
        assert db.execute("SELECT count(*) FROM memory_fts").fetchone()[0] == 2
    bury(chat, identifier)
    _, refs = conversation_context(chat, "owner", identifier, "AI共同诗句")
    assert any(ref["message_id"] == saved["assistant_message"]["id"]
               and ref["role"] == "assistant" and "绿岸微灯" in ref["text"] for ref in refs)


def test_indexing_an_already_indexed_message_is_idempotent(indexed_chat):
    chat, identifier = indexed_chat
    saved = chat.save_turn("owner", identifier, "纪念日是八月十六日。", "我记下了。", [])
    before = fragments(chat)
    with chat.connection() as db:
        row = db.execute("SELECT sequence,content FROM messages WHERE id=?", (saved["user_message"]["id"],)).fetchone()
        chat.memory.index_message(db, row["sequence"], identifier, row["content"])
        chat.memory.index_message(db, row["sequence"], identifier, row["content"])
    assert fragments(chat) == before


def test_long_messages_cover_all_offsets_and_recall_middle_and_tail_of_both_roles(indexed_chat):
    chat, identifier = indexed_chat
    user = "普通风景。" * 1500 + "琉璃沙漏的花纹是紫荆枝。" + "普通日记。" * 900 + "青桥月色的暗号是笺影。"
    assistant = "平常回应。" * 1700 + "暮云长廊的诗句是菰叶清声。" + "轻松交谈。" * 700 + "海棠信笺的结尾是蜡梅。"
    saved = chat.save_turn("owner", identifier, user, assistant, [])
    with chat.connection() as db:
        for message in (saved["user_message"], saved["assistant_message"]):
            rows = db.execute("SELECT char_start,char_end FROM memory_fragments f JOIN messages m ON m.sequence=f.message_sequence WHERE m.id=? ORDER BY char_start", (message["id"],)).fetchall()
            assert rows[0]["char_start"] == 0 and rows[-1]["char_end"] == len(message["content"])
            assert all(0 <= row["char_start"] < row["char_end"] <= len(message["content"]) for row in rows)
            assert all(row["char_end"] - row["char_start"] <= CHUNK_CHARS for row in rows)
            assert all(left["char_end"] >= right["char_start"] for left, right in zip(rows, rows[1:], strict=False))
    bury(chat, identifier)
    for query, fact, role in (("琉璃沙漏", "紫荆枝", "user"), ("青桥月色", "笺影", "user"),
                              ("暮云长廊", "菰叶清声", "assistant"), ("海棠信笺", "蜡梅", "assistant")):
        _, refs = conversation_context(chat, "owner", identifier, query)
        assert any(ref["role"] == role and fact in ref["text"] and ref["truncated"] for ref in refs)
        assert all(ref["text"] in saved[f"{ref['role']}_message"]["content"] for ref in refs)


def test_legacy_backfill_is_batched_and_restart_idempotent(tmp_path, monkeypatch):
    chat = ChatDatabase(tmp_path)
    identifier = chat.create_conversation("owner", "恋人", "model", False)["id"]
    count = BACKFILL_BATCH + 3
    with chat.connection() as db:
        for index in range(count):
            db.execute("INSERT INTO messages(id,conversation_id,role,content,sources,created_at) VALUES(?,?,?,?,?,?)",
                       (f"legacy-{index}", identifier, "user" if index % 2 == 0 else "assistant",
                        f"旧聊天白玫瑰{index}", "[]", "2026-10-01"))
    seen = []
    original = MemoryIndex.index_message

    def capture(self, db, sequence, conversation_id, content):
        seen.append(sequence)
        return original(self, db, sequence, conversation_id, content)

    monkeypatch.setattr(MemoryIndex, "index_message", capture)
    restored = ChatDatabase(tmp_path)
    assert len(seen) == count and len(fragments(restored)) == count
    before = fragments(restored)
    ChatDatabase(tmp_path)
    assert len(seen) == count and fragments(restored) == before


def test_single_character_and_punctuation_messages_are_marked_indexed(indexed_chat, tmp_path, monkeypatch):
    chat, identifier = indexed_chat
    chat.save_turn("owner", identifier, "甲", "！", [])
    assert len(fragments(chat)) == 2

    def unexpected(*args):
        raise AssertionError("Already processed messages must not be backfilled again")

    monkeypatch.setattr(MemoryIndex, "index_message", unexpected)
    ChatDatabase(tmp_path)


def test_deleting_conversation_cascades_fragments_and_removes_fts_hits(indexed_chat):
    chat, identifier = indexed_chat
    chat.save_turn("owner", identifier, "秘密纪念日是冬至。", "收到。", [])
    assert chat.delete_conversation("owner", identifier)
    with chat.connection() as db:
        assert db.execute("SELECT count(*) FROM memory_fragments").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM memory_fts").fetchone()[0] == 0
        db.execute("INSERT INTO memory_fts(memory_fts) VALUES('integrity-check')")


def test_failed_index_write_rolls_back_both_messages_and_all_fts_fragments(indexed_chat, monkeypatch):
    chat, identifier = indexed_chat
    original, calls = chat.memory.index_message, []

    def failing(db, sequence, conversation_id, content):
        calls.append(sequence)
        original(db, sequence, conversation_id, content)
        if len(calls) == 2:
            raise MemoryIndexError("Index write unavailable")

    monkeypatch.setattr(chat.memory, "index_message", failing)
    with pytest.raises(MemoryIndexError):
        chat.save_turn("owner", identifier, "生日是九月。", "记住了。", [])
    assert chat.list_messages("owner", identifier) == []
    assert fragments(chat) == []
    with chat.connection() as db:
        assert db.execute("SELECT count(*) FROM memory_fts").fetchone()[0] == 0


def test_index_failure_is_safe_503_and_upstream_failure_never_indexes(tmp_path, monkeypatch):
    provider = FakeProvider()
    app = create_app(Settings(data_dir=tmp_path), provider_client=provider)
    with TestClient(app) as client:
        headers = login(client)
        identifier = client.post("/api/conversations", headers=headers, json={"use_rag": False}).json()["id"]
        provider.fail = True
        assert client.post(f"/api/conversations/{identifier}/messages", headers=headers, json={"content": "私密纪念日"}).status_code == 429
        assert fragments(app.state.chat) == []
        provider.fail = False

        def failure(*args):
            raise MemoryIndexError("SENSITIVE-INDEX-WRITE")

        monkeypatch.setattr(app.state.chat.memory, "index_message", failure)
        response = client.post(f"/api/conversations/{identifier}/messages", headers=headers, json={"content": "私密纪念日"})
        assert response.status_code == 503 and "SENSITIVE" not in response.text
        assert fragments(app.state.chat) == []


def test_scope_isolation_happens_before_candidate_limit_and_complete_pairing(indexed_chat):
    chat, identifier = indexed_chat
    first = chat.save_turn("owner", identifier, "AuroraCafe anniversary July ninth.", "Recorded.", [])
    foreign = chat.create_conversation("foreign-owner", "他人", "model", False)["id"]
    sibling = chat.create_conversation("owner", "另一个", "model", False)["id"]
    for number in range(300):
        chat.save_turn("foreign-owner", foreign, f"AuroraCafe anniversary {number}.", "Private.", [])
        chat.save_turn("owner", sibling, f"AuroraCafe anniversary {number}.", "Sibling.", [])
    bury(chat, identifier)
    _, refs = conversation_context(chat, "owner", identifier, "AuroraCafe anniversary")
    assert refs[0]["message_id"] == first["user_message"]["id"]
    assert conversation_context(chat, "foreign-owner", identifier, "AuroraCafe") == ([], [])
    assert {ref["turn_id"] for ref in refs} == {first["user_message"]["id"]}


def test_best_fragment_keeps_precise_middle_fact_despite_a_later_weak_match(indexed_chat):
    chat, identifier = indexed_chat
    text = "普通段落。" * 1300 + "琉璃沙漏的特别花纹是紫荆枝。" + "平凡日记。" * 1400 + "沙漏只是普通装饰。"
    saved = chat.save_turn("owner", identifier, text, "收到。", [])
    bury(chat, identifier)
    _, refs = conversation_context(chat, "owner", identifier, "琉璃沙漏特别花纹")
    assert any(ref["message_id"] == saved["user_message"]["id"] and "紫荆枝" in ref["text"] for ref in refs)


def test_many_fragments_are_deduplicated_into_pairs_before_candidate_quota(indexed_chat):
    chat, identifier = indexed_chat
    first = chat.save_turn("owner", identifier, "AuroraCafe anniversary is July ninth.", "Recorded.", [])
    for index in range(20):
        chat.save_turn("owner", identifier, (f"anniversary ordinary discussion {index}. " * 450),
                       "anniversary daily reply. " * 650, [])
    bury(chat, identifier)
    assert len(fragments(chat)) > 1000
    candidates = chat.memory_candidates("owner", identifier, 10**9, ["auroracafe", "anniversary"], 256, 768)
    assert len({turn["sequence"] for turn in candidates}) == len(candidates) == 21
    _, refs = conversation_context(chat, "owner", identifier, "AuroraCafe anniversary")
    assert any(ref["message_id"] == first["user_message"]["id"] and "July ninth" in ref["text"] for ref in refs)


def test_interrupted_backfill_keeps_finished_batches_and_rolls_back_partial_batch(tmp_path, monkeypatch):
    chat = ChatDatabase(tmp_path)
    identifier = chat.create_conversation("owner", "恋人", "model", False)["id"]
    total = BACKFILL_BATCH + 3
    with chat.connection() as db:
        for index in range(total):
            db.execute("INSERT INTO messages(id,conversation_id,role,content,sources,created_at) VALUES(?,?,?,?,?,?)",
                       (f"interrupted-{index}", identifier, "user" if index % 2 == 0 else "assistant",
                        "白玫瑰旧聊天", "[]", "2026-10-01"))
    original, indexed = MemoryIndex.index_message, []

    def interrupted(self, db, sequence, conversation_id, content):
        indexed.append(sequence)
        original(self, db, sequence, conversation_id, content)
        if len(indexed) == BACKFILL_BATCH + 2:
            raise MemoryIndexError("Interrupted migration")

    monkeypatch.setattr(MemoryIndex, "index_message", interrupted)
    with pytest.raises(MemoryIndexError):
        ChatDatabase(tmp_path)
    assert len(fragments(chat)) == BACKFILL_BATCH
    with chat.connection() as db:
        assert db.execute("SELECT count(*) FROM memory_fts").fetchone()[0] == BACKFILL_BATCH
    monkeypatch.setattr(MemoryIndex, "index_message", original)
    restored = ChatDatabase(tmp_path)
    assert len(fragments(restored)) == total


def test_fts_input_cannot_change_scope_or_inject_match_syntax(indexed_chat):
    chat, identifier = indexed_chat
    chat.save_turn("owner", identifier, "AuroraCafe anniversary.", "Recorded.", [])
    assert chat.memory_candidates("owner", identifier, 10**9, ['" OR anniversary'], 256, 768) == []


def test_unknown_index_version_is_explicitly_rejected(indexed_chat, tmp_path):
    chat, _ = indexed_chat
    with chat.connection() as db:
        db.execute("UPDATE memory_index_metadata SET version=999 WHERE id=1")
    with pytest.raises(RuntimeError, match="索引版本"):
        ChatDatabase(tmp_path)


def test_index_sql_faults_are_converted_to_safe_storage_errors():
    index = MemoryIndex()
    db = sqlite3.connect(":memory:")
    db.close()
    with pytest.raises(MemoryIndexError, match="无法写入"):
        index.index_message(db, 1, "conversation", "PRIVATE-ORIGINAL-TEXT")
    with pytest.raises(MemoryIndexError, match="无法检索"):
        index.candidates(db, "owner", "conversation", 100, ["anniversary"], 256, 768)


def test_empty_candidates_do_not_query_sql_when_no_meaningful_terms_or_quota():
    class Unexpected:
        def execute(self, *args):
            raise AssertionError("Empty retrieval must not query SQLite")

    index = MemoryIndex()
    assert index.candidates(Unexpected(), "owner", "conversation", 100, [], 256, 768) == []
    assert index.candidates(Unexpected(), "owner", "conversation", 100, ["anniversary"], 0, 768) == []


def test_fragment_rowid_reuse_after_delete_never_resurrects_old_words(indexed_chat):
    chat, identifier = indexed_chat
    chat.save_turn("owner", identifier, "OldAuroraCafe anniversary.", "Recorded.", [])
    assert chat.delete_conversation("owner", identifier)
    second = chat.create_conversation("owner", "第二段测试", "model", False)["id"]
    saved = chat.save_turn("owner", second, "NewHarborBookshop birthday.", "Recorded.", [])
    assert chat.memory_candidates("owner", second, 10**9, ["oldauroracafe"], 256, 768) == []
    found = chat.memory_candidates("owner", second, 10**9, ["newharborbookshop"], 256, 768)
    assert found[0]["user_id"] == saved["user_message"]["id"]


def test_old_sqlite_version_is_explicitly_rejected(monkeypatch):
    monkeypatch.setattr(sqlite3, "sqlite_version_info", (3, 42, 0))
    with closing(sqlite3.connect(":memory:")) as db, pytest.raises(RuntimeError, match="3.43"):
        MemoryIndex().initialize(db)


def test_missing_fts5_is_explicitly_rejected():
    class Unsupported:
        def execute(self, *args):
            raise sqlite3.OperationalError("no such module: fts5")

    with pytest.raises(RuntimeError, match="FTS5"):
        MemoryIndex().initialize(Unsupported())
