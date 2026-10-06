import hashlib
import sqlite3
from datetime import UTC, datetime, timedelta

import pytest

from app.body_codec import encode_body
from app.memory_annotations import AnnotationsStore, MemoryAnnotationsError
from app.memory_semantic import parse_response

NOW = datetime.now(UTC)


@pytest.fixture
def db(tmp_path):
    connection = sqlite3.connect(tmp_path / "annotations.sqlite3")
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys=ON")
    connection.executescript("""
        CREATE TABLE messages(sequence INTEGER PRIMARY KEY, id TEXT UNIQUE NOT NULL,
            conversation_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL,
            created_at TEXT NOT NULL);
    """)
    AnnotationsStore().initialize(connection)
    yield connection
    connection.close()


def source(db, sequence, text, *, days=0, role="user", conversation="c1"):
    created_at = (NOW - timedelta(days=days)).isoformat()
    db.execute("INSERT INTO messages VALUES(?,?,?,?,?,?)",
               (sequence, f"m-{sequence}", conversation, role, encode_body(text), created_at))
    return {"id": "untrusted", "role": "assistant", "content": "untrusted", "created_at": "wrong"}


def record(db, sequence, text, **kwargs):
    forged = source(db, sequence, text, **kwargs)
    return AnnotationsStore().record_auto(db, forged, sequence, True)


def meta(db, *sequences):
    return AnnotationsStore().metadata(db, list(sequences), datetime.now(UTC))


def test_disabled_never_classifies_or_writes(db):
    forged = source(db, 1, "我的生日是3月6日")
    assert AnnotationsStore().record_auto(db, forged, 1, False) == []
    assert meta(db, 1)[1]["tier"] == "mid"
    assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 0


def test_canonical_author_time_hash_and_span_are_used_not_message_argument(db):
    result = record(db, 1, "我的生日是3月6日")
    annotation = result[0]
    assert annotation["subject"] == "user" and annotation["tier"] == "permanent"
    assert annotation["span_sha256"] == hashlib.sha256("我的生日是3月6日".encode()).hexdigest()
    assert annotation["span_start"] == 0 and annotation["span_end"] == len("我的生日是3月6日")
    assert meta(db, 1)[1]["annotations"][0]["role"] == "user"


def test_retry_is_idempotent_and_never_supplies_extra_evidence(db):
    record(db, 1, "我想吃苹果", days=10)
    result = AnnotationsStore().record_auto(db, {}, 1, True)
    assert result[0]["tier"] == "mid"
    assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 1


def test_cross_day_distinct_wishes_promote_without_merging_or_permanent_fact(db):
    for seq, text, days in ((1, "我想吃苹果", 10), (2, "我又想吃苹果", 7), (3, "我想吃苹果。", 4)):
        record(db, seq, text, days=days)
    assert {item["tier"] for item in meta(db, 1, 2, 3).values()} == {"long"}
    assert db.execute("SELECT count(DISTINCT span_sha256) FROM memory_annotations").fetchone()[0] == 3
    assert {row[0] for row in db.execute("SELECT kind FROM memory_annotations")} == {"wish"}


def test_same_text_copies_stay_mid_until_explicit_independent_confirmation(db):
    for seq, days in ((1, 10), (2, 7), (3, 4)):
        record(db, seq, "我想吃苹果", days=days)
    assert {value["tier"] for value in meta(db, 1, 2, 3).values()} == {"mid"}
    for seq in (1, 2, 3):
        AnnotationsStore().confirm_episode(db, seq)
    assert {value["tier"] for value in meta(db, 1, 2, 3).values()} == {"long"}


def test_quoted_third_person_and_ai_echo_do_not_become_user_fact(db):
    for seq, text, role in ((1, "朋友的生日是3月6日", "user"),
                            (2, "假如我的生日是3月6日", "user"),
                            (3, "我的生日是3月6日", "assistant")):
        assert record(db, seq, text, role=role) == []
    assert all(not value["annotations"] for value in meta(db, 1, 2, 3).values())


def test_negative_supersedes_confirmed_old_preference_without_deleting_source(db):
    record(db, 1, "请记住：我一直喜欢苹果", days=10)
    old = tuple(db.execute("SELECT * FROM messages WHERE sequence=1").fetchone())
    record(db, 2, "我不喜欢苹果", days=1)
    data = meta(db, 1, 2)
    assert data[1]["annotations"][0]["validity"] == "superseded"
    assert data[1]["tier"] == "mid"
    assert data[2]["annotations"][0]["polarity"] == "deny"
    assert tuple(db.execute("SELECT * FROM messages WHERE sequence=1").fetchone()) == old
    assert db.execute("SELECT count(*) FROM memory_annotation_links").fetchone()[0] == 1


def test_explicit_birthday_correction_keeps_old_original_with_link(db):
    record(db, 1, "我的公历生日是03-06", days=8)
    record(db, 2, "更正：我的公历生日是03-07", days=1)
    data = meta(db, 1, 2)
    assert data[1]["annotations"][0]["validity"] == "superseded"
    assert data[2]["tier"] == "permanent"
    assert data[2]["annotations"][0]["supersedes"] == [data[1]["annotations"][0]["id"]]
    assert AnnotationsStore().corrections(db, [1]) == [2]


def test_chained_corrections_find_latest_original_not_invalid_intermediate(db):
    record(db, 1, "我的公历生日是03-06", days=10)
    record(db, 2, "更正：我的公历生日是03-07", days=5)
    record(db, 3, "更正：我的公历生日是03-08", days=1)
    assert AnnotationsStore().corrections(db, [1]) == [3]
    assert AnnotationsStore().corrections(db, [1, 2]) == [3]
    assert AnnotationsStore().corrections(db, [3]) == []


def test_manual_permanent_history_does_not_override_invalid_fact_currentness(db):
    record(db, 1, "我一直喜欢苹果", days=5)
    AnnotationsStore().set_manual(db, 1, "permanent")
    record(db, 2, "我不喜欢苹果", days=1)
    old = meta(db, 1)[1]
    assert old["manual_tier"] == "permanent" and old["tier"] == "permanent"
    assert old["source_current"] is False and old["ranking_tier"] == "mid"
    assert old["can_confirm_independent"] is False


def test_independent_confirmation_capability_is_canonical_and_current(db):
    record(db, 1, "我想读山海经")
    assert meta(db, 1)[1]["can_confirm_independent"] is True
    AnnotationsStore().confirm_episode(db, 1)
    assert meta(db, 1)[1]["can_confirm_independent"] is False
    record(db, 2, "我承诺在2026-10-08陪你读山海经", role="assistant")
    assert meta(db, 2)[2]["can_confirm_independent"] is False


@pytest.mark.parametrize("timestamp", ["not-date", "2026-10-06T00:00:00", "2026-10-06T08:00:00+08:00"])
def test_invalid_or_non_utc_canonical_timestamp_never_supplies_evidence(db, timestamp):
    source(db, 1, "我想吃苹果")
    db.execute("UPDATE messages SET created_at=? WHERE sequence=1", (timestamp,))
    with pytest.raises(MemoryAnnotationsError):
        AnnotationsStore().record_auto(db, {}, 1, True)


def test_invalid_canonical_role_and_missing_source_fail_safely(db):
    source(db, 1, "我想吃苹果")
    db.execute("UPDATE messages SET role='system' WHERE sequence=1")
    with pytest.raises(MemoryAnnotationsError):
        meta(db, 1)
    with pytest.raises(ValueError):
        AnnotationsStore().record_auto(db, {}, 99, True)
    with pytest.raises(ValueError):
        AnnotationsStore().record_auto(db, {}, 1, "true")


@pytest.mark.parametrize("sequences", ["1", [1] * 1025, [True], [None]])
def test_metadata_and_correction_input_budgets(db, sequences):
    source(db, 1, "原话")
    for method in ("metadata", "corrections"):
        with pytest.raises(ValueError):
            if method == "metadata":
                AnnotationsStore().metadata(db, sequences, NOW)
            else:
                AnnotationsStore().corrections(db, sequences)


def test_metadata_now_must_have_explicit_utc_timezone(db):
    source(db, 1, "原话")
    with pytest.raises(ValueError):
        AnnotationsStore().metadata(db, [1], datetime(2026, 1, 1))


def test_manual_source_integrity_is_rechecked(db):
    source(db, 1, "普通原话\x00🌸e\u0301" * 400)
    AnnotationsStore().set_manual(db, 1, "permanent")
    db.execute("UPDATE messages SET content='不同原话' WHERE sequence=1")
    with pytest.raises(MemoryAnnotationsError):
        meta(db, 1)


def test_future_source_is_neither_fact_nor_pinned_promise(db):
    record(db, 1, "我的生日是3月6日", days=-5)
    record(db, 2, "我承诺在2026-10-08陪你吃火锅", days=-5)
    assert all(value["tier"] == "mid" and not value["pinned"] for value in meta(db, 1, 2).values())


def test_unannotated_metadata_and_corrections_do_not_decode_unused_originals(db, monkeypatch):
    source(db, 1, "长字段普通话。" * 1000)
    def unexpected_decode(_value):
        raise AssertionError("metadata without a source annotation need not decode a body")
    monkeypatch.setattr("app.memory_annotations.decode_body", unexpected_decode)
    assert meta(db, 1)[1]["tier"] == "mid"
    assert AnnotationsStore().corrections(db, [1]) == []


def test_source_pointer_and_manual_override_integrity_still_require_decoding(db, monkeypatch):
    record(db, 1, "我的生日是3月6日")
    source(db, 2, "普通原话")
    AnnotationsStore().set_manual(db, 2, "long")
    def failed_decode(_value):
        raise MemoryAnnotationsError("fixture codec failure")
    monkeypatch.setattr("app.memory_annotations.decode_body", failed_decode)
    with pytest.raises(MemoryAnnotationsError):
        meta(db, 1)
    with pytest.raises(MemoryAnnotationsError):
        meta(db, 2)


def test_different_conversations_never_contribute_evidence_or_corrections(db):
    record(db, 1, "请记住：我一直喜欢苹果", days=10)
    record(db, 2, "我不喜欢苹果", days=1, conversation="c2")
    assert meta(db, 1)[1]["tier"] == "permanent"


def test_ai_promise_is_protected_but_remains_ai_and_never_user_permanent_fact(db):
    record(db, 1, "我承诺在2020-01-01陪你吃火锅", role="assistant")
    value = meta(db, 1)[1]
    assert value["tier"] == "mid" and value["pinned"] is True
    assert value["annotations"][0]["subject"] == "assistant"
    assert value["annotations"][0]["promise_state"] == "pending"


@pytest.mark.parametrize("tier", ["mid", "long", "permanent"])
def test_manual_tier_retains_original_and_auto_clear_does_not_remove_annotation(db, tier):
    record(db, 1, "我的生日是3月6日")
    before = tuple(db.execute("SELECT * FROM messages").fetchone())
    result = AnnotationsStore().set_manual(db, 1, tier)
    assert result["tier"] == tier
    assert meta(db, 1)[1]["tier"] == tier
    assert AnnotationsStore().set_manual(db, 1, "auto")["tier"] == "permanent"
    assert tuple(db.execute("SELECT * FROM messages").fetchone()) == before
    assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 1


@pytest.mark.parametrize("method,args", [
    ("set_manual", (99, "long")), ("confirm_episode", (99,)),
    ("set_manual", (1, "bad")), ("set_manual", (True, "long")),
])
def test_missing_sources_and_invalid_manual_values_are_rejected(db, method, args):
    source(db, 1, "普通原文")
    with pytest.raises(ValueError):
        getattr(AnnotationsStore(), method)(db, *args)


def test_canonical_changed_span_cannot_be_used_for_weight_or_confirmed_episode(db):
    record(db, 1, "我想吃苹果")
    db.execute("UPDATE messages SET content='我想吃香蕉' WHERE sequence=1")
    with pytest.raises(MemoryAnnotationsError):
        meta(db, 1)
    with pytest.raises(MemoryAnnotationsError):
        AnnotationsStore().confirm_episode(db, 1)


def test_unsupported_message_cannot_be_marked_independent_assertion(db):
    source(db, 1, "朋友喜欢苹果")
    with pytest.raises(ValueError):
        AnnotationsStore().confirm_episode(db, 1)


def test_annotation_trigger_failure_rolls_back_outer_message_transaction(db):
    db.commit()
    db.executescript("""CREATE TRIGGER reject_annotation BEFORE INSERT ON memory_annotations
        BEGIN SELECT RAISE(ABORT,'fixture-failure'); END;""")
    with pytest.raises(MemoryAnnotationsError), db:
        record(db, 1, "我的生日是3月6日")
    assert db.execute("SELECT count(*) FROM messages").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 0


def test_failure_after_old_source_invalidated_restores_all_annotation_changes(db):
    record(db, 1, "请记住：我一直喜欢苹果", days=10)
    db.commit()
    db.execute("""CREATE TRIGGER reject_superseded_audit BEFORE INSERT ON memory_annotation_audit
        WHEN new.action='superseded' BEGIN SELECT RAISE(ABORT,'fixture-failure'); END""")
    db.commit()
    with pytest.raises(MemoryAnnotationsError), db:
        record(db, 2, "我不喜欢苹果", days=1)
    assert meta(db, 1)[1]["tier"] == "permanent"
    assert meta(db, 1)[1]["annotations"][0]["validity"] == "current"
    assert db.execute("SELECT count(*) FROM messages").fetchone()[0] == 1
    assert db.execute("SELECT count(*) FROM memory_annotation_links").fetchone()[0] == 0


def test_thousand_sources_are_scoped_bounded_and_no_raw_text_in_metadata_tables(db):
    for seq in range(1, 1001):
        record(db, seq, "我想吃苹果", days=(seq % 20))
    data = meta(db, 1, 500, 1000)
    assert len(data) == 3 and all(item["tier"] == "mid" for item in data.values())
    assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 1000
    result = record(db, 1001, "我想吃苹果")
    assert result == [{"status": "skipped", "reason": "topic_annotation_limit"}]
    assert db.execute("SELECT count(*) FROM messages").fetchone()[0] == 1001
    columns = {row[1] for row in db.execute("PRAGMA table_info(memory_annotations)")}
    assert not columns.intersection({"text", "content", "summary", "merged_fact"})


def semantic_claim(text, *, kind="wish", subject="user", topic="吃:苹果", **changes):
    return {"quote": text, "kind": kind, "subject": subject, "polarity": "affirm",
            "topic_key": topic, "confidence": 0.95, "explicit_keep": False,
            "correction": False, "promise_state": "pending" if kind == "promise" else None,
            "date_basis": None, "event_date": None, **changes}


def semantic_record(db, sequence, text, claims, *, role="user", days=0):
    import json
    source(db, sequence, text, role=role, days=days)
    payload = {"user": claims if role == "user" else [], "assistant": claims if role == "assistant" else []}
    inputs = {"user": {"content": text if role == "user" else "普通用户话", "created_at": NOW.isoformat()},
              "assistant": {"content": text if role == "assistant" else "普通AI话", "created_at": NOW.isoformat()}}
    parsed = parse_response(json.dumps(payload, ensure_ascii=False), inputs)[role]
    return AnnotationsStore().record_semantic(db, sequence, parsed)


def test_semantic_synonymous_independent_originals_progress_without_fused_memory(db):
    for seq, text, days in ((1, "又嘴馋苹果了", 10), (2, "现在好想再来一个苹果", 7), (3, "隔几天又惦记苹果的味道", 4)):
        semantic_record(db, seq, text, [semantic_claim(text)], days=days)
    values = meta(db, 1, 2, 3)
    assert {item["tier"] for item in values.values()} == {"long"}
    assert all(item["annotations"][0]["kind"] == "wish" for item in values.values())
    assert all(item["annotations"][0]["confidence"] == 0.95 for item in values.values())
    assert all(item["annotations"][0]["classification_version"].startswith("semantic-v1:") for item in values.values())


def test_semantic_multiple_exact_spans_birthday_and_preference_remain_separate(db):
    text = "我的生日在九月六日。红茶我一直喜欢，你以后别忘了。"
    claims = [semantic_claim("我的生日在九月六日", kind="profile", topic="user:birthday"),
              semantic_claim("红茶我一直喜欢，你以后别忘了", kind="preference", topic="喜欢:红茶", explicit_keep=True)]
    result = semantic_record(db, 1, text, claims)
    assert len(result) == 2 and len({row["id"] for row in result}) == 2
    with db:
        AnnotationsStore().record_semantic(db, 1, [])
    assert len(meta(db, 1)[1]["annotations"]) == 2
    assert db.execute("SELECT count(*) FROM memory_semantic_runs").fetchone()[0] == 1


def test_semantic_negative_invalidates_old_profile_or_preference_and_keeps_raw(db):
    text = "红茶就是我的心头好，你以后可别忘了"
    semantic_record(db, 1, text, [semantic_claim(text, kind="preference", topic="喜欢:红茶", explicit_keep=True)], days=8)
    negative = "现在对红茶已经没有兴趣了"
    semantic_record(db, 2, negative, [semantic_claim(negative, kind="preference", topic="喜欢:红茶", polarity="deny")])
    assert meta(db, 1)[1]["annotations"][0]["validity"] == "superseded"
    assert db.execute("SELECT content FROM messages WHERE sequence=1").fetchone()[0] == text
    assert AnnotationsStore().corrections(db, [1]) == [2]


def test_semantic_retry_cannot_change_topic_or_gain_additional_evidence(db):
    text = "又惦记苹果了"
    first = semantic_record(db, 1, text, [semantic_claim(text)])
    other = {**semantic_claim(text, topic="随意新主题"), "span_start": 0, "span_end": len(text),
             "span_sha256": hashlib.sha256(text.encode()).hexdigest()}
    second = AnnotationsStore().record_semantic(db, 1, [other])
    assert [item["id"] for item in first] == [item["id"] for item in second]
    assert second[0]["topic_key"] == "吃:苹果"


def test_semantic_canonical_span_verification_rejects_modified_claim_before_write(db):
    text = "我又想看看星空"
    source(db, 1, text)
    forged = {**semantic_claim(text, topic="看:星空"), "span_start": 1, "span_end": len(text),
              "span_sha256": hashlib.sha256(text.encode()).hexdigest()}
    with pytest.raises(MemoryAnnotationsError):
        AnnotationsStore().record_semantic(db, 1, [forged])
    assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM memory_semantic_runs").fetchone()[0] == 0


def test_ai_resolves_only_own_exact_topic_pending_promise_without_new_text(db):
    text = "我答应陪你去海边"
    semantic_record(db, 1, text, [semantic_claim(text, kind="promise", subject="assistant", topic="约定:看海")], role="assistant", days=4)
    semantic_record(db, 2, text, [semantic_claim(text, kind="promise", topic="约定:看海")], days=3)
    resolved = "已经兑现陪你去海边的承诺了"
    semantic_record(db, 3, resolved, [semantic_claim(resolved, kind="promise", subject="assistant", topic="约定:看海", promise_state="fulfilled")], role="assistant")
    values = meta(db, 1, 2, 3)
    assert not values[1]["pinned"] and values[1]["annotations"][0]["validity"] == "superseded"
    assert values[2]["pinned"] and values[2]["annotations"][0]["validity"] == "current"
    assert values[3]["annotations"][0]["promise_state"] == "fulfilled"


def test_new_pending_or_unknown_resolved_topic_cannot_clear_existing_debt(db):
    for seq in (1, 2):
        text = f"我第{seq}次答应陪你看海"
        semantic_record(db, seq, text, [semantic_claim(text, kind="promise", topic="约定:看海", correction=True)], days=3-seq)
    done = "已经完成读书的约定了"
    semantic_record(db, 3, done, [semantic_claim(done, kind="promise", topic="约定:读书", promise_state="fulfilled")])
    assert all(meta(db, seq)[seq]["pinned"] for seq in (1, 2))


def test_ambiguous_multiple_pending_same_topic_are_not_all_auto_fulfilled(db):
    for seq in (1, 2):
        text = f"我第{seq}次答应陪你看海"
        semantic_record(db, seq, text, [semantic_claim(text, kind="promise", topic="约定:看海")], days=3-seq)
    done = "已经完成陪你看海的约定了"
    semantic_record(db, 3, done, [semantic_claim(done, kind="promise", topic="约定:看海", promise_state="fulfilled")])
    assert all(meta(db, seq)[seq]["pinned"] for seq in (1, 2))


def test_semantic_sql_failure_rolls_back_all_spans_and_first_attempt_marker(db):
    db.commit()
    db.execute("""CREATE TRIGGER reject_semantic_marker BEFORE INSERT ON memory_semantic_runs
        BEGIN SELECT RAISE(ABORT,'fixture-failure'); END""")
    db.commit()
    text = "我的生日在九月六日"
    with pytest.raises(MemoryAnnotationsError), db:
        semantic_record(db, 1, text, [semantic_claim(text, kind="profile", topic="user:birthday")])
    assert db.execute("SELECT count(*) FROM messages").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 0
