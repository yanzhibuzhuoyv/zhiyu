import hashlib
import sqlite3
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from app.body_codec import decode_body, encode_body
from app.memory_bloom import encode_bloom
from app.memory_cold import BACKFILL_ACTIVE_SQL, ColdIndex
from app.memory_index import MemoryIndex
from app.source_codec import ChatStorageError


@pytest.fixture
def cold_db():
    db = sqlite3.connect(":memory:")
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("CREATE TABLE conversations(id TEXT PRIMARY KEY,owner TEXT NOT NULL)")
    db.execute("""CREATE TABLE messages(sequence INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE,
        conversation_id TEXT REFERENCES conversations(id) ON DELETE CASCADE,role TEXT,
        content TEXT,sources TEXT,created_at TEXT)""")
    db.execute("""CREATE TABLE memory_recognition(sequence INTEGER PRIMARY KEY
        REFERENCES messages(sequence) ON DELETE CASCADE,mode TEXT NOT NULL,
        status TEXT NOT NULL,version TEXT NOT NULL)""")
    index, cold = MemoryIndex(), ColdIndex()
    index.initialize(db)
    cold.initialize(db)
    db.executemany("INSERT INTO conversations VALUES(?,?)",
                   [("chat", "owner"), ("sibling", "owner"), ("foreign", "other")])
    db.commit()
    yield db, index, cold
    db.close()


def seed(db, index, user="用户秘密琉璃灯", assistant="AI秘密海棠笺", cid="chat"):
    with db:
        numbers = []
        for role, original in (("user", user), ("assistant", assistant)):
            cursor = db.execute("""INSERT INTO messages(id,conversation_id,role,content,sources,created_at)
                VALUES(?,?,?,?,?,?)""", (f"message-{db.total_changes}-{role}", cid, role,
                                       encode_body(original), '[{"name":"original-source"}]',
                                       "2026-10-06T00:00:00+00:00"))
            numbers.append(cursor.lastrowid)
            index.index_message(db, cursor.lastrowid, cid, original)
    return tuple(numbers)


def cool_pair(db, index, cold, sequences, cid="chat"):
    with db:
        db.execute("BEGIN IMMEDIATE")
        for sequence in sequences:
            original = decode_body(db.execute("SELECT content FROM messages WHERE sequence=?",
                                               (sequence,)).fetchone()[0])
            cold.cool(db, sequence, cid, original, index)


def search(db, cold, terms, *, owner="owner", cid="chat", **kwargs):
    with db:
        db.execute("BEGIN")
        return cold.search(db, owner, cid, 1000000, terms, **kwargs)


def snapshot(db):
    return {table: [tuple(row) for row in db.execute(f"SELECT * FROM {table} ORDER BY 1")]
            for table in ("messages", "memory_index_state", "memory_cold_directory",
                          "memory_fragments", "memory_fts")}


def test_default_active_cool_warm_revisions_and_originals_remain_exact(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index)
    original = snapshot(db)["messages"]
    assert cold.get_state(db, numbers[0]) == {"index_state": "active", "revision": 0}
    assert not cold.is_cold(db, numbers[0])
    cool_pair(db, index, cold, numbers)
    assert cold.get_state(db, numbers[0]) == {"index_state": "cold", "revision": 1}
    assert db.execute("SELECT count(*) FROM memory_fragments").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM memory_fts").fetchone()[0] == 0
    directory = db.execute("SELECT * FROM memory_cold_directory WHERE sequence=?", (numbers[0],)).fetchone()
    assert directory["source_sha"] == hashlib.sha256("用户秘密琉璃灯".encode()).hexdigest()
    with db:
        db.execute("BEGIN IMMEDIATE")
        cold.warm(db, numbers[0], "chat", "用户秘密琉璃灯", index)
    assert cold.get_state(db, numbers[0]) == {"index_state": "active", "revision": 2}
    assert not cold.is_cold(db, numbers[0])
    assert db.execute("SELECT count(*) FROM memory_fts").fetchone()[0] == 1
    assert snapshot(db)["messages"] == original
    db.execute("INSERT INTO memory_fts(memory_fts) VALUES('integrity-check')")


def test_missing_recognition_schema_fails_closed_without_removing_index(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index)
    db.execute("DROP TABLE memory_recognition")
    db.commit()
    before = snapshot(db)
    with pytest.raises(ChatStorageError):
        cool_pair(db, index, cold, numbers)
    assert snapshot(db) == before


def test_idempotent_state_operations_and_tier_only_revision_bump(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index)
    cool_pair(db, index, cold, numbers)
    cool_pair(db, index, cold, numbers)
    assert cold.get_state(db, numbers[0])["revision"] == 1
    with db:
        db.execute("BEGIN IMMEDIATE")
        cold.bump_revision(db, numbers[0])
        cold.bump_revision(db, numbers[1])
    assert cold.get_state(db, numbers[0])["revision"] == 2
    active = seed(db, index)[0]
    with db:
        db.execute("BEGIN IMMEDIATE")
        cold.bump_revision(db, active)
    assert cold.get_state(db, active) == {"index_state": "active", "revision": 1}


def test_failure_after_directory_write_rolls_back_fts_and_state(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index)
    before = snapshot(db)
    db.execute("""CREATE TRIGGER fail_cold BEFORE DELETE ON memory_fragments
        BEGIN SELECT RAISE(ABORT,'private-sentinel'); END""")
    with pytest.raises(ChatStorageError) as caught:
        cool_pair(db, index, cold, numbers)
    assert "private-sentinel" not in str(caught.value)
    assert snapshot(db) == before


def test_failed_warm_after_state_clear_rolls_back_entire_pair(cold_db, monkeypatch):
    db, index, cold = cold_db
    numbers = seed(db, index)
    cool_pair(db, index, cold, numbers)
    before = snapshot(db)
    original_index = index.index_message

    def fail(*args):
        original_index(*args)
        raise ChatStorageError("聊天索引不可用")

    monkeypatch.setattr(index, "index_message", fail)
    with pytest.raises(ChatStorageError), db:
        db.execute("BEGIN IMMEDIATE")
        cold.warm(db, numbers[0], "chat", "用户秘密琉璃灯", index)
    assert snapshot(db) == before


@pytest.mark.parametrize("operation", ["cool", "warm"])
@pytest.mark.parametrize("cid,original", [("sibling", "用户秘密琉璃灯"), ("chat", "改写的原话")])
def test_wrong_source_cid_or_original_is_rejected_before_mutation(cold_db, operation, cid, original):
    db, index, cold = cold_db
    numbers = seed(db, index)
    before = snapshot(db)
    with pytest.raises(ChatStorageError), db:
        db.execute("BEGIN IMMEDIATE")
        getattr(cold, operation)(db, numbers[0], cid, original, index)
    assert snapshot(db) == before


def test_restart_backfill_predicate_respects_cold_missing_fragments(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index)
    cool_pair(db, index, cold, numbers)
    index.initialize(db)
    cold.initialize(db)
    active = db.execute(f"SELECT m.sequence FROM messages m WHERE {BACKFILL_ACTIVE_SQL}").fetchall()
    assert not active
    assert cold.is_cold(db, numbers[0])


def test_foreign_owner_and_sibling_are_excluded_before_scan_or_decode(cold_db):
    db, index, cold = cold_db
    mine = seed(db, index)
    sibling = seed(db, index, cid="sibling")
    foreign = seed(db, index, cid="foreign")
    for numbers, cid in ((mine, "chat"), (sibling, "sibling"), (foreign, "foreign")):
        cool_pair(db, index, cold, numbers, cid)
    found = search(db, cold, ["琉璃"])
    assert [turn["sequence"] for turn in found["turns"]] == [mine[0]]
    missing = search(db, cold, ["琉璃"], owner="other")
    assert missing["scanned"] == missing["decoded"] == 0
    assert missing["complete"] and not missing["turns"]


def test_unicode_blob_both_roles_middle_tail_and_exact_offsets(cold_db):
    db, index, cold = cold_db
    user = "é e\u0301👩🏽‍🚀\0普通风景。" * 700 + "琉璃沙漏紫荆枝。" + "日常散步。" * 500 + "青桥月色笺影。"
    assistant = "组合字符e\u0301。" * 1200 + "暮云长廊菰叶清声。" + "轻松交谈。" * 500 + "海棠信笺蜡梅。"
    numbers = seed(db, index, user, assistant)
    cool_pair(db, index, cold, numbers)
    originals = snapshot(db)["messages"]
    for terms, role, expected in ((["琉璃", "沙漏"], "user", "紫荆枝"),
                                  (["青桥", "月色"], "user", "笺影"),
                                  (["暮云", "长廊"], "assistant", "菰叶清声"),
                                  (["海棠", "信笺"], "assistant", "蜡梅")):
        result = search(db, cold, terms)
        assert result["complete"] and result["decoded"] == 2
        turn = result["turns"][0]
        original = user if role == "user" else assistant
        assert expected in turn[f"{role}_text"]
        assert turn[f"{role}_text"] == original[turn[f"{role}_start"]:turn[f"{role}_char_end"]]
        assert turn[f"{role}_length"] == len(original)
        assert turn["owner"] == "owner" and turn["conversation_id"] == "chat"
    assert snapshot(db)["messages"] == originals


def test_ascii_chunk_boundary_terms_do_not_have_bloom_false_negatives(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index, "a" * 1100, "recorded")
    cool_pair(db, index, cold, numbers)
    result = search(db, cold, ["a" * 768])
    assert len(result["turns"]) == 1
    assert result["turns"][0]["user_start"] == 0


def test_bloom_false_positive_does_not_return_unrelated_originals(cold_db, monkeypatch):
    db, index, cold = cold_db
    numbers = seed(db, index)
    cool_pair(db, index, cold, numbers)
    monkeypatch.setattr("app.memory_bloom.Bloom.may_contain_any", lambda *_: True)
    result = search(db, cold, ["nonexistent"])
    assert result["complete"] and result["decoded"] == 2
    assert not result["turns"]


@pytest.mark.parametrize("column,value", [("source_sha", "0" * 64), ("char_length", 1),
                                        ("bloom", b"broken-frame")])
def test_corrupted_directory_or_source_integrity_fails_closed(cold_db, column, value):
    db, index, cold = cold_db
    numbers = seed(db, index)
    cool_pair(db, index, cold, numbers)
    with db:
        db.execute(f"UPDATE memory_cold_directory SET {column}=? WHERE sequence=?", (value, numbers[0]))
    with pytest.raises(ChatStorageError):
        search(db, cold, ["琉璃"])


def test_both_cold_roles_are_sha_checked_even_when_only_one_matches(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index)
    cool_pair(db, index, cold, numbers)
    with db:
        db.execute("UPDATE memory_cold_directory SET source_sha=? WHERE sequence=?", ("0" * 64, numbers[1]))
    with pytest.raises(ChatStorageError):
        search(db, cold, ["琉璃"])


def test_search_requires_read_snapshot_and_writes_require_outer_transaction(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index)
    with pytest.raises(ChatStorageError, match="事务"):
        cold.search(db, "owner", "chat", 999, ["琉璃"])
    for operation in (cold.cool, cold.warm):
        with pytest.raises(ChatStorageError, match="事务"):
            operation(db, numbers[0], "chat", "用户秘密琉璃灯", index)


def test_cascade_removes_derived_directory_and_state_but_only_for_target(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index)
    sibling = seed(db, index, cid="sibling")
    cool_pair(db, index, cold, numbers)
    cool_pair(db, index, cold, sibling, "sibling")
    with db:
        db.execute("DELETE FROM conversations WHERE id='chat'")
    assert db.execute("SELECT count(*) FROM memory_cold_directory").fetchone()[0] == 2
    assert cold.is_cold(db, sibling[0])


def seed_direct_cold(db, count, *, content="ordinary discussion", cid="chat"):
    sha = hashlib.sha256(content.encode()).hexdigest()
    bloom = encode_bloom(["t" + term.encode().hex() for term in ("ordinary", "discussion")])
    with db:
        for number in range(count):
            cursor = db.execute("INSERT INTO messages(id,conversation_id,role,content,sources,created_at) VALUES(?,?,?,?,?,?)",
                                (f"direct-{cid}-{number}", cid, "user" if number % 2 == 0 else "assistant",
                                 content, "[]", "2026-10-06T00:00:00+00:00"))
            db.execute("INSERT INTO memory_index_state VALUES(?,'cold',1,1)", (cursor.lastrowid,))
            db.execute("INSERT INTO memory_cold_directory VALUES(?,?,?,?)", (cursor.lastrowid, bloom, sha, len(content)))


def test_twenty_thousand_directory_scan_budget_is_paginated_without_false_exhaustion(cold_db):
    db, _, cold = cold_db
    seed_direct_cold(db, 20002)
    first = search(db, cold, ["absent"])
    assert first["scanned"] == 20000 and first["decoded"] == 0
    assert not first["complete"] and first["next_before"] is not None
    second = search(db, cold, ["absent"], cursor=first["next_before"])
    assert second["complete"] and second["scanned"] == 2


def test_decode_budget_resumes_at_unprocessed_pair_and_returns_no_duplicate_turns(cold_db):
    db, index, cold = cold_db
    sequences = [seed(db, index, "sharedtopic user", "sharedtopic assistant") for _ in range(34)]
    for numbers in sequences:
        cool_pair(db, index, cold, numbers)
    first = search(db, cold, ["sharedtopic"])
    assert first["decoded"] == 64 and not first["complete"]
    second = search(db, cold, ["sharedtopic"], cursor=first["next_before"])
    assert second["complete"]
    ids = [turn["sequence"] for turn in [*first["turns"], *second["turns"]]]
    assert len(ids) == len(set(ids)) == 34


def test_character_budget_solo_exception_and_blocked_original_are_explicit(cold_db):
    db, index, cold = cold_db
    large = "sharedtopic " + "日常。" * 50000
    numbers = seed(db, index, large, large)
    cool_pair(db, index, cold, numbers)
    result = search(db, cold, ["sharedtopic"])
    assert result["solo_exception"] and result["decoded"] == 2
    assert len(result["turns"]) == 1
    enormous = "sharedtopic " + "x " * (600 * 1024)
    more = seed(db, index, enormous, "small reply")
    cool_pair(db, index, cold, more)
    blocked = search(db, cold, ["sharedtopic"])
    assert not blocked["complete"] and blocked["blocked_sequence"] is not None


@pytest.mark.parametrize("kwargs", [{"limit": 0}, {"limit": 257}, {"excerpt_chars": 0},
                                    {"cursor": -1}, {"cursor": True}])
def test_invalid_search_parameters_are_rejected(cold_db, kwargs):
    db, _, cold = cold_db
    with pytest.raises(ValueError):
        search(db, cold, ["琉璃"], **kwargs)


def test_search_before_boundary_and_bounded_utc_windows_apply_before_scan(cold_db):
    db, index, cold = cold_db
    first = seed(db, index, "生日在九月", "收到")
    second = seed(db, index, "生日在十月", "记住")
    with db:
        db.execute("UPDATE messages SET created_at='2026-09-01T00:00:00+00:00' WHERE sequence IN (?,?)", first)
    cool_pair(db, index, cold, first)
    cool_pair(db, index, cold, second)
    window = SimpleNamespace(start=datetime(2026, 9, 1, tzinfo=UTC),
                             end=datetime(2026, 9, 2, tzinfo=UTC))
    found = search(db, cold, ["生日"], windows=[window])
    assert found["scanned"] == 1 and found["decoded"] == 2
    assert [turn["sequence"] for turn in found["turns"]] == [first[0]]
    with db:
        db.execute("BEGIN")
        bounded = cold.search(db, "owner", "chat", second[1], ["生日"])
    assert [turn["sequence"] for turn in bounded["turns"]] == [first[0]]
    assert found["turns"][0]["user_created_at"] == "2026-09-01T00:00:00+00:00"


@pytest.mark.parametrize("windows", [[], [None], [None, None, None],
                                    [SimpleNamespace(start=datetime(2026, 1, 1), end=datetime(2026, 1, 2))]])
def test_invalid_event_windows_are_rejected(cold_db, windows):
    db, _, cold = cold_db
    with pytest.raises(ValueError):
        search(db, cold, ["生日"], windows=windows)


def test_missing_cold_directory_and_changed_legacy_text_fail_closed(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index)
    cool_pair(db, index, cold, numbers)
    with db:
        db.execute("UPDATE messages SET content='改写秘密琉璃灯' WHERE sequence=?", (numbers[0],))
    with pytest.raises(ChatStorageError):
        search(db, cold, ["琉璃"])
    with db:
        db.execute("DELETE FROM memory_cold_directory WHERE sequence=?", (numbers[0],))
    with pytest.raises(ChatStorageError, match="目录缺失"):
        search(db, cold, ["琉璃"])


def test_hot_counterpart_is_checked_without_bloom_and_large_budget_is_transparent(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index)
    cool_pair(db, index, cold, numbers[:1])
    found = search(db, cold, ["琉璃"])
    assert found["decoded"] == 2 and "海棠" in found["turns"][0]["assistant_text"]
    with db:
        db.execute("INSERT INTO memory_index_state VALUES(?,'active',2,1)", (numbers[1],))
    with pytest.raises(ChatStorageError, match="版本"):
        search(db, cold, ["琉璃"])


def test_directory_read_budget_stops_before_payload_and_cursor_can_resume(cold_db, monkeypatch):
    db, index, cold = cold_db
    numbers = seed(db, index)
    cool_pair(db, index, cold, numbers)
    monkeypatch.setattr("app.memory_cold_search.MAX_DIRECTORY_BYTES", 0)
    limited = search(db, cold, ["琉璃"])
    assert not limited["complete"] and limited["directory_bytes"] == limited["decoded"] == 0
    monkeypatch.setattr("app.memory_cold_search.MAX_DIRECTORY_BYTES", 1024 * 1024)
    resumed = search(db, cold, ["琉璃"], cursor=limited["next_before"])
    assert resumed["complete"] and len(resumed["turns"]) == 1


def test_invalid_schema_state_and_empty_query_are_explicit(cold_db):
    db, index, cold = cold_db
    numbers = seed(db, index)
    with db:
        db.execute("INSERT INTO memory_index_state VALUES(?,'active',2,1)", (numbers[0],))
    with pytest.raises(ChatStorageError, match="版本"):
        cold.get_state(db, numbers[0])
    assert search(db, cold, ["甲"])["complete"]
