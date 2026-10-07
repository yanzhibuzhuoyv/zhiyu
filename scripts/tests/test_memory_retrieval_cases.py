"""冻结 A/B 检索题的数据契约；不把命中 ID 当成回答语义正确。"""

import importlib
import importlib.util
from collections import Counter
from datetime import datetime

import pytest

MODULE = "scripts.memory_retrieval_cases"
CATEGORIES = {
    "exact_identifier",
    "semantic_paraphrase",
    "correction",
    "third_party_or_hypothetical",
    "ai_guess",
    "user_question_unknown",
    "multi_fact",
    "daily_share",
}


def test_case_module_exists():
    assert importlib.util.find_spec(MODULE) is not None, "独立 A/B 检索题尚未实现"


@pytest.fixture
def bank():
    if importlib.util.find_spec(MODULE) is None:
        pytest.skip("数据模块缺失，由存在性断言记录 RED")
    return importlib.import_module(MODULE).build_cases()


def test_frozen_distribution(bank):
    assert len(bank) == 48
    assert Counter(case["category"] for case in bank) == dict.fromkeys(CATEGORIES, 6)
    assert Counter(bool(case["expected_ids"]) for case in bank) == {True: 24, False: 24}


def test_case_schema_and_unique_questions(bank):
    expected = {
        "id", "category", "history", "query", "expected_ids", "forbidden_ids",
        "answer_expectation", "expected_quotes",
    }
    assert len({case["id"] for case in bank}) == 48
    assert len({case["query"] for case in bank}) == 48
    assert len({case["answer_expectation"] for case in bank}) == 48
    for case in bank:
        assert set(case) == expected
        assert case["query"].strip() and case["answer_expectation"].strip()


def test_originals_are_independent_complete_pairs_with_fixed_timestamps(bank):
    seen = set()
    for case in bank:
        history = case["history"]
        assert 4 <= len(history) <= 12 and len(history) % 2 == 0
        assert [item["role"] for item in history] == ["user", "assistant"] * (len(history) // 2)
        times = []
        for original in history:
            assert set(original) == {"id", "role", "text", "created_at"}
            assert original["id"] not in seen
            seen.add(original["id"])
            assert original["text"].strip()
            parsed = datetime.fromisoformat(original["created_at"])
            assert parsed.year == 2026 and parsed.month == 9 and parsed.utcoffset() is not None
            times.append(parsed)
        assert times == sorted(times)


def test_expected_sources_exist_are_user_originals_and_disjoint_from_forbidden(bank):
    for case in bank:
        originals = {item["id"]: item for item in case["history"]}
        assert len(case["expected_ids"]) == len(set(case["expected_ids"]))
        assert len(case["forbidden_ids"]) == len(set(case["forbidden_ids"]))
        assert set(case["expected_ids"]).isdisjoint(case["forbidden_ids"])
        assert set(case["expected_ids"] + case["forbidden_ids"]) <= originals.keys()
        assert all(originals[item]["role"] == "user" for item in case["expected_ids"])
        assert set(case["expected_quotes"]) == set(case["expected_ids"])
        assert all(quote in originals[item]["text"] for item, quote in case["expected_quotes"].items())
        assert all(1 <= len(quote) <= 50 for quote in case["expected_quotes"].values())


def test_unknown_sources_preserve_role_and_scope_traps(bank):
    for case in bank:
        if case["category"] in {"third_party_or_hypothetical", "ai_guess", "user_question_unknown"}:
            assert not case["expected_ids"] and case["forbidden_ids"]
    guesses = [case for case in bank if case["category"] == "ai_guess"]
    for case in guesses:
        originals = {item["id"]: item for item in case["history"]}
        assert any(originals[item]["role"] == "assistant" for item in case["forbidden_ids"])


def test_multi_fact_queries_require_multiple_independent_sources(bank):
    assert all(len(case["expected_ids"]) >= 2 for case in bank if case["category"] == "multi_fact")
    assert all(case["forbidden_ids"] for case in bank if case["category"] == "correction")
    assert all(not case["forbidden_ids"] for case in bank if case["category"] == "daily_share")


def test_long_original_has_unique_target_at_tail(bank):
    long_case = next(case for case in bank if case["id"] == "paraphrase-long-field")
    originals = {item["id"]: item for item in long_case["history"]}
    target = originals[long_case["expected_ids"][0]]["text"]
    assert sum("\u4e00" <= char <= "\u9fff" for char in target) > 2000
    assert "雁脊露营地" not in target[:2000]
    assert target.endswith("最后确认，帐篷搭在雁脊露营地，绝不是湖湾驿站。")
    assert "雁脊露营地" not in long_case["query"]


def test_build_cases_returns_independent_nested_lists_and_dicts(bank):
    module = importlib.import_module(MODULE)
    frozen = module.build_cases()
    bank[0]["history"][0]["text"] = "测试修改"
    bank[0]["history"].append({"id": "test"})
    bank[0]["expected_ids"].append("test")
    bank[0]["forbidden_ids"].append("test")
    assert module.build_cases() == frozen


def test_synthetic_predeclared_origin(bank):
    module = importlib.import_module(MODULE)
    assert module.DATASET_VERSION == "retrieval-order-independent-v1"
    assert module.GOLD_ORIGIN == "predeclared independent synthetic cases"
    assert all("鸡翅" not in case["query"] and "生日" not in case["query"] for case in bank)
