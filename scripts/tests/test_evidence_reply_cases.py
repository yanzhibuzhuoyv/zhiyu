"""单次原文绑定回复题集：先固定作者、日期和范围，不用命中代替语义准确。"""

import hashlib
import importlib
import importlib.util
import json
from collections import Counter
from datetime import UTC, datetime, timedelta

import pytest

MODULE = "scripts.evidence_reply_cases"
CURRENT_UTC = "2026-10-07T04:00:00+00:00"
CATEGORIES = {
    "historical_values",
    "dated_or_temporary",
    "author_and_scope",
    "current_or_daily",
}
CASE_FIELDS = {
    "id",
    "category",
    "subtype",
    "history",
    "query",
    "expected_ids",
    "expected_quotes",
    "forbidden_ids",
    "answer_expectation",
    "current_utc",
    "unknown_current",
    "source_use_required",
    "acceptable_scopes",
    "oracle_notes",
}


def test_evidence_reply_cases_exist_before_observation():
    assert importlib.util.find_spec(MODULE) is not None, "独立单次回复新题尚未实现"


@pytest.fixture
def bank():
    if importlib.util.find_spec(MODULE) is None:
        pytest.skip("模块不存在；存在性断言已记录 RED")
    return importlib.import_module(MODULE).build_cases()


def test_32_new_cases_have_four_balanced_categories(bank):
    assert len(bank) == 32
    assert Counter(case["category"] for case in bank) == dict.fromkeys(CATEGORIES, 8)
    for field in ("id", "query", "subtype", "answer_expectation"):
        assert len({case[field] for case in bank}) == 32


def test_schema_preserves_semantic_gold_separately_from_originals(bank):
    for case in bank:
        assert set(case) == CASE_FIELDS
        assert case["current_utc"] == CURRENT_UTC
        assert type(case["unknown_current"]) is bool
        assert type(case["source_use_required"]) is bool
        assert all(case[key].strip() for key in ("id", "query", "subtype", "oracle_notes"))
        assert isinstance(case["acceptable_scopes"], list)
        assert case["acceptable_scopes"]
        assert all(isinstance(scope, str) and scope.strip() for scope in case["acceptable_scopes"])
        assert len(case["acceptable_scopes"]) == len(set(case["acceptable_scopes"]))


def test_every_original_has_unique_role_id_full_text_and_ordered_local_time(bank):
    seen = set()
    current = datetime.fromisoformat(CURRENT_UTC)
    for case in bank:
        history = case["history"]
        assert len(history) in (6, 8)
        assert [original["role"] for original in history] == ["user", "assistant"] * (
            len(history) // 2
        )
        times = []
        for original in history:
            assert set(original) == {"id", "role", "text", "created_at"}
            assert original["id"] not in seen
            seen.add(original["id"])
            assert original["text"].strip()
            timestamp = datetime.fromisoformat(original["created_at"])
            assert timestamp.utcoffset() == timedelta(hours=8)
            assert timestamp < current
            times.append(timestamp)
        assert times == sorted(times)


def test_gold_quotes_are_exact_independent_originals_and_not_forbidden_facts(bank):
    for case in bank:
        originals = {original["id"]: original for original in case["history"]}
        expected, forbidden = case["expected_ids"], case["forbidden_ids"]
        assert len(expected) == len(set(expected))
        assert len(forbidden) == len(set(forbidden))
        assert set(expected).isdisjoint(forbidden)
        assert set(expected + forbidden) <= originals.keys()
        assert set(case["expected_quotes"]) == set(expected)
        for key, quote in case["expected_quotes"].items():
            assert quote and quote in originals[key]["text"]
        if case["source_use_required"]:
            assert expected, "必需来源不能依靠未声明的目标原文"
        for key in expected:
            role = "assistant" if case["subtype"] == "own_bounded_promise" else "user"
            assert originals[key]["role"] == role


def test_known_multi_fact_answers_need_several_independent_sources(bank):
    known = [case for case in bank if case["category"] == "historical_values"]
    assert all(case["source_use_required"] and not case["unknown_current"] for case in known)
    assert sum(len(case["expected_ids"]) >= 2 for case in known) >= 5
    assert all(case["expected_quotes"] for case in known)


def test_relative_dates_resolve_from_source_local_date_instead_of_current(bank):
    cases = {case["id"]: case for case in bank}
    for key, day_delta, result in (
        ("evidence-date-01", 1, "2026-09-27"),
        ("evidence-date-02", -1, "2026-09-24"),
        ("evidence-date-06", 2, "2026-10-02"),
    ):
        case = cases[key]
        original = case["history"][0]
        parsed = datetime.fromisoformat(original["created_at"])
        resolved = (parsed.date() + timedelta(days=day_delta)).isoformat()
        assert resolved == result
        assert result in case["oracle_notes"]
        assert result in " ".join(case["acceptable_scopes"])
        assert case["source_use_required"] and not case["unknown_current"]
    early = datetime.fromisoformat(cases["evidence-date-02"]["history"][0]["created_at"])
    assert early.date() != early.astimezone(UTC).date(), "凌晨题必须覆盖 UTC 日期跨界"


def test_this_week_and_expired_temporary_states_do_not_define_current_week(bank):
    cases = {case["id"]: case for case in bank}
    for key in ("evidence-date-03", "evidence-date-04", "evidence-date-05"):
        case = cases[key]
        assert case["unknown_current"]
        assert not case["source_use_required"] and not case["expected_ids"]
        assert case["forbidden_ids"]
        assert "现在" in case["answer_expectation"]
    assert "2026-09-28 至 2026-10-04" in cases["evidence-date-03"]["oracle_notes"]
    assert "2026-10-05 至 2026-10-11" in cases["evidence-date-03"]["oracle_notes"]
    assert "2026-10-03" in cases["evidence-date-04"]["oracle_notes"]
    assert "2026-10-02" in cases["evidence-date-05"]["oracle_notes"]


def test_author_traps_and_own_promise_have_explicit_semantic_boundaries(bank):
    cases = {case["id"]: case for case in bank}
    for key in (
        "evidence-author-01",
        "evidence-author-02",
        "evidence-author-03",
        "evidence-author-04",
    ):
        case = cases[key]
        assert case["unknown_current"] and not case["expected_ids"]
        assert case["forbidden_ids"]
    promise = cases["evidence-author-06"]
    assert promise["subtype"] == "own_bounded_promise"
    assert promise["expected_ids"] == ["evidence-author-06-a2"]
    assert "承诺" in promise["answer_expectation"] and "已经" in promise["oracle_notes"]
    distinct = cases["evidence-author-07"]
    assert distinct["expected_ids"] == ["evidence-author-07-u3"]
    assert "evidence-author-07-u1" in distinct["forbidden_ids"]


def test_daily_and_explicit_current_inputs_do_not_require_irrelevant_old_quotes(bank):
    cases = {case["id"]: case for case in bank}
    for key in (
        "evidence-current-01",
        "evidence-current-02",
        "evidence-current-03",
        "evidence-current-04",
        "evidence-current-07",
        "evidence-current-08",
    ):
        assert not cases[key]["source_use_required"]
        assert not cases[key]["expected_ids"]
        assert not cases[key]["unknown_current"]
    for key in ("evidence-current-05", "evidence-current-06"):
        assert cases[key]["source_use_required"] and cases[key]["expected_ids"]
        assert "限定" in cases[key]["oracle_notes"]


def test_two_long_originals_are_natural_complete_text_with_target_only_at_tail(bank):
    long_cases = [case for case in bank if case["subtype"].startswith("long_original_")]
    assert len(long_cases) == 2
    for case in long_cases:
        key = case["expected_ids"][0]
        original = next(original["text"] for original in case["history"] if original["id"] == key)
        quote = case["expected_quotes"][key]
        assert sum("\u4e00" <= char <= "\u9fff" for char in original) >= 2500
        assert (
            sum("\u4e00" <= char <= "\u9fff" for char in original[: original.index(quote)]) >= 2500
        )
        assert original.endswith(quote)
        assert original.count(quote) == 1
        assert quote not in case["query"]
        paragraphs = original.split("\n")
        assert len(paragraphs) >= 15 and len(paragraphs) == len(set(paragraphs))
        assert all("第" + str(number) + "段" not in original for number in range(1, 50))


def test_all_new_questions_and_user_texts_are_distinct_from_both_old_banks(bank):
    previous = []
    for name in ("scripts.memory_retrieval_cases", "scripts.memory_answer_guard_cases"):
        previous.extend(importlib.import_module(name).build_cases())
    old_queries = {case["query"] for case in previous}
    old_user_texts = {
        source["text"]
        for case in previous
        for source in case["history"]
        if source["role"] == "user"
    }
    assert all(case["query"] not in old_queries for case in bank)
    assert all(
        source["text"] not in old_user_texts
        for case in bank
        for source in case["history"]
        if source["role"] == "user"
    )


def test_build_cases_keeps_all_nested_gold_and_original_objects_independent(bank):
    module = importlib.import_module(MODULE)
    frozen = module.build_cases()
    bank[0]["history"][0]["text"] = "只修改测试返回值"
    bank[0]["expected_ids"].append("test")
    bank[0]["expected_quotes"].clear()
    bank[0]["forbidden_ids"].append("test")
    bank[0]["acceptable_scopes"].clear()
    assert module.build_cases() == frozen


def test_origin_and_canonical_bank_hash_are_frozen_before_model_output(bank):
    module = importlib.import_module(MODULE)
    assert module.DATASET_VERSION == "single-evidence-reply-independent-v1"
    assert module.GOLD_ORIGIN == "predeclared independent synthetic cases before model outputs"
    payload = json.dumps(bank, ensure_ascii=False, sort_keys=True).encode("utf-8")
    assert (
        hashlib.sha256(payload).hexdigest()
        == "1b9f0294ea2d96c5ce8005443600a62746210391a9d4bdfed1c2ec1c7d7550c7"
    )
