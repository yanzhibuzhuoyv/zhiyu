"""预声明出处与范围题集；数据金标不能充当线上校验器输入。"""

import hashlib
import importlib
import importlib.util
import json
from collections import Counter
from datetime import datetime

import pytest

MODULE = "scripts.memory_answer_guard_cases"
CATEGORIES = {
    "confirmed_user_fact", "record_insufficient", "ai_guess",
    "third_party_or_hypothetical", "correction_or_time", "allowed_response",
}
CASE_FIELDS = {
    "id", "category", "subtype", "history", "query", "expected_ids", "expected_quotes",
    "forbidden_ids", "answer_expectation", "challenge_drafts",
}
DRAFT_FIELDS = {"id", "text", "expected_supported", "violation", "rationale"}


def test_answer_guard_case_module_exists():
    assert importlib.util.find_spec(MODULE) is not None, "独立后验校验题尚未冻结"


@pytest.fixture
def bank():
    if importlib.util.find_spec(MODULE) is None:
        pytest.skip("数据模块缺失，由存在性断言记录 RED")
    return importlib.import_module(MODULE).build_cases()


def test_frozen_24_case_distribution(bank):
    assert len(bank) == 24
    assert Counter(case["category"] for case in bank) == dict.fromkeys(CATEGORIES, 4)
    assert Counter(bool(case["expected_ids"]) for case in bank) == {True: 11, False: 13}
    allowed = [case["subtype"] for case in bank if case["category"] == "allowed_response"]
    assert set(allowed) == {
        "daily_share", "explicit_user_denial", "assistant_promise", "bounded_prior_silence",
    }


def test_unique_cases_and_complete_schema(bank):
    for field in ("id", "query", "answer_expectation"):
        assert len({case[field] for case in bank}) == 24
    for case in bank:
        assert set(case) == CASE_FIELDS
        assert all(case[field].strip() for field in ("id", "query", "subtype", "answer_expectation"))


def test_each_original_keeps_role_id_time_and_text(bank):
    seen = set()
    for case in bank:
        assert len(case["history"]) in (6, 8)
        assert [m["role"] for m in case["history"]] == ["user", "assistant"] * (
            len(case["history"]) // 2
        )
        timestamps = []
        for original in case["history"]:
            assert set(original) == {"id", "role", "text", "created_at"}
            assert original["id"] not in seen
            seen.add(original["id"])
            assert original["text"].strip()
            timestamp = datetime.fromisoformat(original["created_at"])
            assert timestamp.utcoffset() is not None
            timestamps.append(timestamp)
        assert timestamps == sorted(timestamps)


def test_original_quotes_and_forbidden_sources_are_disjoint(bank):
    for case in bank:
        sources = {source["id"]: source for source in case["history"]}
        expected, forbidden = case["expected_ids"], case["forbidden_ids"]
        assert len(expected) == len(set(expected))
        assert len(forbidden) == len(set(forbidden))
        assert set(expected).isdisjoint(forbidden)
        assert set(expected + forbidden) <= sources.keys()
        assert set(case["expected_quotes"]) == set(expected)
        assert all(quote and quote in sources[key]["text"] for key, quote in case["expected_quotes"].items())
        for key in expected:
            role = "assistant" if case["subtype"] == "assistant_promise" else "user"
            assert sources[key]["role"] == role


def test_unknowns_are_not_declared_as_negative_user_facts(bank):
    for case in bank:
        if case["category"] in {"record_insufficient", "ai_guess", "third_party_or_hypothetical"}:
            assert not case["expected_ids"]
        if case["category"] == "ai_guess":
            sources = {source["id"]: source for source in case["history"]}
            assert any(sources[key]["role"] == "assistant" for key in case["forbidden_ids"])
        if case["category"] == "third_party_or_hypothetical":
            assert case["forbidden_ids"]


def test_correction_includes_bounded_past_and_current_support(bank):
    corrections = [case for case in bank if case["category"] == "correction_or_time"]
    assert all(case["forbidden_ids"] or len(case["expected_ids"]) == 2 for case in corrections)
    assert any(len(case["expected_ids"]) == 2 for case in corrections)
    assert any(len({m["created_at"][:10] for m in case["history"]}) > 1 for case in corrections)


def test_challenge_pairs_are_complete_and_semantically_labelled(bank):
    seen = set()
    for case in bank:
        drafts = case["challenge_drafts"]
        assert len(drafts) == 2
        assert [draft["expected_supported"] for draft in drafts] == [True, False]
        for draft in drafts:
            assert set(draft) == DRAFT_FIELDS
            assert draft["id"] not in seen
            seen.add(draft["id"])
            assert type(draft["expected_supported"]) is bool
            assert all(draft[key].strip() for key in ("id", "text", "violation", "rationale"))
        assert drafts[0]["violation"] == "none"
        assert drafts[1]["violation"] != "none"
        assert drafts[0]["text"] != drafts[1]["text"]


def test_new_questions_and_user_originals_are_not_copies_of_old_48(bank):
    old = importlib.import_module("scripts.memory_retrieval_cases").build_cases()
    old_queries = {case["query"] for case in old}
    old_user_text = {m["text"] for case in old for m in case["history"] if m["role"] == "user"}
    assert all(case["query"] not in old_queries for case in bank)
    assert all(
        m["text"] not in old_user_text for case in bank for m in case["history"] if m["role"] == "user"
    )


def test_nested_build_cases_objects_are_independent(bank):
    frozen = importlib.import_module(MODULE).build_cases()
    bank[0]["history"][0]["text"] = "测试改写原文"
    bank[0]["expected_quotes"].clear()
    bank[0]["expected_ids"].append("test")
    bank[0]["challenge_drafts"][0]["rationale"] = "测试修改标签"
    assert importlib.import_module(MODULE).build_cases() == frozen


def test_origin_is_synthetic_and_predeclared(bank):
    module = importlib.import_module(MODULE)
    assert module.DATASET_VERSION == "answer-provenance-independent-v1"
    assert module.GOLD_ORIGIN == "predeclared independent synthetic cases before model outputs"
    assert all("鸡翅" not in case["query"] and "生日" not in case["query"] for case in bank)


def test_frozen_pre_output_semantic_gold_digest(bank):
    payload = json.dumps(bank, ensure_ascii=False, sort_keys=True).encode()
    assert hashlib.sha256(payload).hexdigest() == (
        "b5ad46bb4814d98210287dfda20a341f71de82eacff41ad7dedd4e91b1145e02"
    ), "原文、挑战稿和语义边界应在看模型输出之前冻结"
