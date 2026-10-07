"""V2 corpus invariants; no model, routing policy, previous bank, or private data."""

import json

import pytest

from scripts.memory_route_cases_v2 import (
    CASES,
    VERSION,
    cases_for_split,
    split_statistics,
    validate,
)


def test_frozen_v2_corpus_has_complete_disjoint_json_splits():
    assert VERSION == "memory-route-synthetic-v2"
    validate(CASES)
    assert json.loads(json.dumps(CASES, ensure_ascii=False)) == list(CASES)
    assert split_statistics() == {
        "train": {"count": 100, "present": 40, "retrieve": 60},
        "validation": {"count": 50, "present": 20, "retrieve": 30},
        "test": {"count": 50, "present": 20, "retrieve": 30},
    }
    assert len({case["text"] for case in CASES}) == 200
    assert all(len(case["recent_context"]) <= 4 for case in CASES)
    identifiers = [
        {case["id"] for case in cases_for_split(split)}
        for split in ("train", "validation", "test")
    ]
    assert all(
        not identifiers[first] & identifiers[second]
        for first in range(3)
        for second in range(first + 1, 3)
    )


def test_v2_validation_rejects_invalid_or_duplicate_cases():
    sample = cases_for_split("train")[0]
    for invalid in (
        {**sample, "gold": "unsure"},
        {**sample, "split": "test"},
        {**sample, "reason": " "},
        {**sample, "recent_context": [{"role": "system", "content": "错误"}]},
        {**sample, "recent_context": [{"role": "user", "content": "消息"}] * 5},
        {**sample, "extra": 1},
    ):
        with pytest.raises(ValueError):
            validate([invalid])
    with pytest.raises(ValueError):
        validate([sample, sample])


def test_v2_reader_copies_data_and_requires_known_split():
    result = cases_for_split("validation")
    result[0]["text"] = "测试调用者的修改"
    result[0]["recent_context"].append({"role": "user", "content": "新增消息"})
    fresh = cases_for_split("validation")
    assert fresh[0]["text"] != "测试调用者的修改"
    assert not fresh[0]["recent_context"]
    with pytest.raises(ValueError):
        cases_for_split("production")
