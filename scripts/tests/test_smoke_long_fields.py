"""Counterexamples for the long-field real-HTTP smoke harness."""

import importlib.util
import sqlite3
from contextlib import closing
from pathlib import Path

import pytest

SCRIPT = Path(__file__).parents[1] / "smoke_long_fields.py"
SPEC = importlib.util.spec_from_file_location("smoke_long_fields", SCRIPT)
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)


def original(role="user", turn=1, size=16000):
    return {"id": f"{role}-{turn}", "role": role, "content": smoke.long_payload(size, turn, role),
            "created_at": "2026-10-05T00:00:00+00:00", "sources": []}


def reference(message, turn_id="user-1", location="middle"):
    marker = smoke.anchor_marker(message["role"], location, int(message["id"].split("-")[-1]))
    offset = message["content"].index(marker)
    start = max(0, offset - 128)
    return {"message_id": message["id"], "turn_id": turn_id, "role": message["role"],
            "created_at": message["created_at"], "text": message["content"][start:start + 768],
            "truncated": True}


def test_varied_exact_long_fields_are_deterministic_and_have_distinct_body_not_only_counter():
    first = smoke.long_payload(16000, 1, "user")
    assert first == smoke.long_payload(16000, 1, "user")
    second = smoke.long_payload(16000, 2, "user")
    assert len(first) == len(second) == 16000 and first[500:1000] != second[500:1000]
    assert len(smoke.long_payload(64000, 36, "assistant")) == 64000
    assert "\n" in first and "🌙" in first and len(first.encode()) > len(first)
    assert first.index(smoke.anchor_marker("user", "middle", 1)) > 7500
    assert first.index(smoke.anchor_marker("user", "tail", 1)) > 15500
    assert len(set(first.splitlines())) > 100


@pytest.mark.parametrize("role,size", [("user", 16000), ("assistant", 16000), ("assistant", 64000)])
@pytest.mark.parametrize("location", ["middle", "tail"])
def test_provenance_requires_original_middle_tail_excerpt_and_author(role, size, location):
    item = original(role, 1, size)
    ref = reference(item, location=location)
    assert smoke.verify_reference([ref], item, "user-1", location, 1)["passed"] is True
    for change in ({"message_id": "unknown"}, {"turn_id": "unknown"}, {"role": "assistant" if role == "user" else "user"},
                   {"created_at": "2025-01-01"}, {"text": "缺少原文"}, {"truncated": False}):
        with pytest.raises(smoke.CheckFailure):
            smoke.verify_reference([{**ref, **change}], item, "user-1", location, 1)
    with pytest.raises(smoke.CheckFailure):
        smoke.verify_reference([], item, "user-1", location, 1)


def test_provenance_rejects_a_forged_excerpt_with_real_marker_but_invented_surroundings():
    item = original()
    ref = reference(item)
    ref["text"] = smoke.anchor_marker("user", "middle", 1) + "这行是伪造的。"
    with pytest.raises(smoke.CheckFailure):
        smoke.verify_reference([ref], item, "user-1", "middle", 1)


def test_complete_offsets_cover_body_and_reject_missing_middle_tail_or_fts_row():
    rows = [(offset, min(offset + 768, 16000), True) for offset in range(0, 16000, 640)]
    assert smoke.verify_fragment_rows(16000, rows)["covered_chars"] == 16000
    for damaged in (rows[2:], rows[:-1], rows[:10] + rows[12:],
                    [(start, end, False if index == 4 else found) for index, (start, end, found) in enumerate(rows)]):
        with pytest.raises(smoke.CheckFailure):
            smoke.verify_fragment_rows(16000, damaged)


def test_history_hashes_reject_role_content_sources_or_order_changes():
    messages = [original(), original("assistant")]
    expected = [smoke.signature(item) for item in messages]
    assert smoke.verify_page(messages, expected, 2, set()) == 0
    for damaged in (messages[::-1], [messages[0], {**messages[1], "content": "丢了长回复"}],
                    [messages[0], {**messages[1], "sources": [{"text": "意外引用"}]}]):
        with pytest.raises(smoke.CheckFailure):
            smoke.verify_page(damaged, expected, 2, set())


def test_http_422_does_not_count_as_valid_rejection_if_sqlite_messages_or_index_changed():
    baseline = {"messages": 2, "fragments": 50, "fts_rows": 50, "conversations": 1, "content_utf8_bytes": 10000}
    smoke.verify_rejected_unchanged(baseline, dict(baseline))
    for field in ("messages", "fragments", "fts_rows", "content_utf8_bytes"):
        with pytest.raises(smoke.CheckFailure):
            smoke.verify_rejected_unchanged(baseline, {**baseline, field: baseline[field] + 1})


def test_server_worker_failure_is_propagated_without_sensitive_exception_text():
    server = smoke.LoopbackServer(None)
    server.failure = ValueError("PRIVATE-TOKEN-OR-TEXT")
    with pytest.raises(smoke.CheckFailure) as error:
        server.check()
    assert "PRIVATE-TOKEN-OR-TEXT" not in str(error.value)


def test_cleanup_snapshot_failure_still_closes_http_and_stops_server_without_hiding_original_error(tmp_path, monkeypatch):
    report = {"status": "failed", "failure": {"message": "original assertion"}, "storage": {}}
    experiment = smoke.LongFieldRun(tmp_path, None, report)
    experiment.path.touch()
    events = []

    class Client:
        def close(self):
            events.append("http_closed")

    class Server:
        def stop(self):
            events.append("server_stopped")

    def broken_snapshot(path):
        raise ValueError("PRIVATE-ORIGINAL-TEXT")

    experiment.client, experiment.server = Client(), Server()
    monkeypatch.setattr(smoke, "snapshot", broken_snapshot)
    experiment.cleanup()
    assert events == ["http_closed", "server_stopped"]
    assert report["failure"]["message"] == "original assertion"
    assert report["status"] == "failed" and report["cleanup"]["completed"] is False
    assert "PRIVATE-ORIGINAL-TEXT" not in str(report)


@pytest.mark.parametrize("result", [{"quick_check": "database corruption", "fragments": 2, "fts_rows": 2},
                                     {"quick_check": "ok", "fragments": 2, "fts_rows": 1}])
def test_bad_sqlite_integrity_or_missing_fts_is_never_a_passed_measurement(result):
    with pytest.raises(smoke.CheckFailure):
        smoke.verify_snapshot_integrity(result)


def test_server_listener_closes_even_if_worker_refuses_to_stop():
    server = smoke.LoopbackServer(None)
    events = []

    class Listener:
        def close(self):
            events.append("listener_closed")

    class Worker:
        def join(self, timeout):
            pass

        def is_alive(self):
            return True

    class Uvicorn:
        should_exit = False
        force_exit = False

    server.listener, server.thread, server.server = Listener(), Worker(), Uvicorn()
    with pytest.raises(smoke.CheckFailure, match="failed to stop"):
        server.stop()
    assert events == ["listener_closed"] and server.server.force_exit is True


@pytest.mark.parametrize("rows", [[], [("user", "汉字🌙"), ("assistant", "ASCII reply"),
                                      ("user", "多行\n原文"), ("tool", "外部角色"),
                                      (None, "NULL角色"), (None, None), ("assistant", None)]])
def test_per_role_byte_statistics_match_old_group_by_including_empty_and_nullable_values(rows):
    with closing(sqlite3.connect(":memory:")) as db:
        db.execute("CREATE TABLE messages(role TEXT,content TEXT)")
        db.executemany("INSERT INTO messages(role,content) VALUES(?,?)", rows)
        old = db.execute("SELECT role,count(*),coalesce(sum(length(CAST(content AS BLOB))),0) FROM messages GROUP BY role").fetchall()
        new = smoke.role_storage_counts(db)
        assert {role: (count, size) for role, count, size in new} == {
            role: (count, size) for role, count, size in old}
        assert sum(row[1] for row in new) == len(rows)
        assert sum(row[2] for row in new) == sum(len(content.encode()) for _, content in rows if content is not None)


def test_statistics_query_plan_and_vm_never_sort_or_copy_full_content_into_a_temp_record():
    with closing(sqlite3.connect(":memory:")) as db:
        db.execute("CREATE TABLE messages(role TEXT,content TEXT)")
        db.executemany("INSERT INTO messages VALUES(?,?)", [("user", "长用户原文" * 8000),
                                                           ("assistant", "长AI回复" * 8000)])
        require_no_sort = db.execute("EXPLAIN QUERY PLAN " + smoke.ROLE_AGGREGATE_SQL, ("user",)).fetchall()
        assert all("TEMP B-TREE" not in row[-1] for row in require_no_sort)
        aggregate_ops = db.execute("EXPLAIN " + smoke.ROLE_AGGREGATE_SQL, ("user",)).fetchall()
        assert not {row[1] for row in aggregate_ops} & {"SorterOpen", "SorterInsert", "Sort", "MakeRecord", "IdxInsert"}
        role_ops = db.execute("EXPLAIN " + smoke.ROLE_ENUMERATION_SQL).fetchall()
        # Only messages column zero (role) reaches DISTINCT's tiny temporary index.
        assert {row[3] for row in role_ops if row[1] == "Column"} == {0}
        old_ops = db.execute("EXPLAIN SELECT role,count(*),coalesce(sum(length(CAST(content AS BLOB))),0) FROM messages GROUP BY role").fetchall()
        assert "SorterInsert" in {row[1] for row in old_ops}
