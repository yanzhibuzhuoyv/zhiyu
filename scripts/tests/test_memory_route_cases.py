"""Data invariants only; no model, threshold, credential, or private data access."""

import json

import pytest

from scripts.memory_route_cases import (
    CASES,
    cases_for_split,
    split_statistics,
    validate,
)


def test_versioned_cases_have_fixed_disjoint_balanced_splits():
    validate(CASES)
    assert json.loads(json.dumps(CASES, ensure_ascii=False)) == list(CASES)
    assert split_statistics() == {
        "train": {"count": 50, "present": 20, "retrieve": 30},
        "validation": {"count": 25, "present": 10, "retrieve": 15},
        "test": {"count": 25, "present": 10, "retrieve": 15},
    }
    assert len({case["text"] for case in CASES}) == 100
    assert all(len(case["recent_context"]) <= 4 for case in CASES)


def test_validation_rejects_ambiguous_schema_and_duplicate_identity():
    sample = cases_for_split("train")[0]
    for invalid in (
        {**sample, "gold": "maybe"},
        {**sample, "split": "dev"},
        {**sample, "recent_context": [{"role": "system", "content": "无效"}]},
        {**sample, "text": ""},
        {**sample, "unexpected": True},
    ):
        with pytest.raises(ValueError):
            validate([invalid])
    with pytest.raises(ValueError):
        validate([sample, sample])


def test_split_reader_returns_fresh_json_data_and_rejects_unknown_split():
    first = cases_for_split("train")
    first[0]["text"] = "调用者修改"
    first[0]["recent_context"].append({"role": "user", "content": "调用者修改"})
    second = cases_for_split("train")
    assert second[0]["text"] != "调用者修改"
    assert all(message["content"] != "调用者修改" for message in second[0]["recent_context"])
    with pytest.raises(ValueError):
        cases_for_split("unknown")
