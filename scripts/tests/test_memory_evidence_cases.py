"""证据约束合成题的结构契约；不评价真实回答的语义正确率。"""

import importlib
import importlib.util
import re
from collections import Counter

import pytest

MODULE = "scripts.memory_evidence_cases"


def test_evidence_dataset_module_is_implemented():
    assert importlib.util.find_spec(MODULE) is not None, "证据约束题库尚未实现"


@pytest.fixture
def bank():
    if importlib.util.find_spec(MODULE) is None:
        pytest.skip("数据模块缺失，由存在性断言记录RED")
    return importlib.import_module(MODULE).cases()


def test_exact_frozen_counts(bank):
    assert len(bank) == 24
    assert Counter(case["expected_known"] for case in bank) == {False: 16, True: 8}


def test_case_and_query_identifiers_are_unique(bank):
    assert len({case["id"] for case in bank}) == 24
    assert len({case["query"] for case in bank}) == 24
    assert all(case["id"].strip() and case["query"].strip() for case in bank)


def test_explicit_schema_and_pair_contract(bank):
    fields = {
        "id",
        "seeds",
        "recent_seeds",
        "query",
        "category",
        "expected_known",
        "expected_user_quote",
        "required_patterns",
        "forbidden_patterns",
        "notes",
    }
    for case in bank:
        assert set(case) == fields
        assert len(case["seeds"]) >= 2
        assert type(case["expected_known"]) is bool
        for user, assistant in case["seeds"] + case["recent_seeds"]:
            assert isinstance(user, str) and user.strip()
            assert isinstance(assistant, str) and assistant.strip()


def test_known_gold_quotes_an_actual_user_original(bank):
    for case in bank:
        quote = case["expected_user_quote"]
        if case["expected_known"]:
            users = [pair[0] for pair in case["seeds"] + case["recent_seeds"]]
            assert quote in users
        else:
            assert quote is None


def test_regex_rules_are_explicit_and_compilable(bank):
    for case in bank:
        assert case["required_patterns"] and case["forbidden_patterns"]
        for pattern in case["required_patterns"] + case["forbidden_patterns"]:
            assert pattern
            re.compile(pattern)


def test_recent_ai_guess_and_old_known_boundaries_exist(bank):
    assert sum(bool(case["recent_seeds"]) for case in bank) >= 6
    assert any(case["category"] == "ai_guess_recent" for case in bank)
    assert any(case["category"] == "unconfirmed_choice" for case in bank)
    assert any(case["category"] == "denial_without_replacement" for case in bank)
    assert any(case["category"] == "fiction_not_fact" for case in bank)
    assert any(case["category"] == "third_person_not_user" for case in bank)
    assert any(case["category"] == "explicit_user_denial" for case in bank)
    assert any(case["category"] == "user_confirms_after_ai_guess" for case in bank)


def test_shared_unknown_rules_do_not_replace_case_specific_notes(bank):
    assert len({case["notes"] for case in bank}) == 24
    assert all(case["category"].strip() and case["notes"].strip() for case in bank)


def test_rule_screen_accepts_predeclared_honest_known_replies(bank):
    replies = {
        "known-shop-name": "你确认的店名是青越乐器。",
        "known-card-correction": "你当时用储蓄卡刷卡，不是现金。",
        "known-recipient": "你说相册送给邻居舒阿姨。",
        "known-pronunciation": "行读xing，不读hang。",
        "known-api-path": "新路径是/api/lantern，原来的/api/light停用了。",
        "known-chosen-time": "最终是周六下午三点，不是周五。",
        "known-selected-cover": "样式是浅灰布面，不用皮革。",
        "known-did-not-attend": "你最后没有去书展，留在家里整理抽屉。",
    }
    for case in bank:
        if not case["expected_known"]:
            continue
        answer = replies[case["id"]]
        assert any(re.search(pattern, answer) for pattern in case["required_patterns"])
        assert not any(re.search(pattern, answer) for pattern in case["forbidden_patterns"])


def test_rule_screen_accepts_predeclared_uncertainty(bank):
    uncertain = "原话没有确认这件事，我不能确定，不能把猜测当成你说过的话。"
    for case in bank:
        if case["expected_known"]:
            continue
        assert any(re.search(pattern, uncertain) for pattern in case["required_patterns"])
        assert not any(re.search(pattern, uncertain) for pattern in case["forbidden_patterns"])


def test_fresh_calls_isolate_all_nested_mutable_lists(bank):
    module = importlib.import_module(MODULE)
    before = module.cases()
    bank[0]["seeds"].append(("测试变更", "测试回复"))
    bank[0]["recent_seeds"].append(("测试变更", "测试回复"))
    bank[0]["required_patterns"].append("测试模式")
    assert module.cases() == before


def test_synthetic_origin_and_regex_screening_are_declared():
    if importlib.util.find_spec(MODULE) is None:
        pytest.skip("数据未实现")
    module = importlib.import_module(MODULE)
    assert module.DATASET_VERSION == "memory-evidence-v1"
    assert "synthetic" in module.GOLD_ORIGIN
    assert "human" not in module.GOLD_ORIGIN
    assert module.PATTERN_RULE == "required_any_and_forbidden_none_for_review_only"
