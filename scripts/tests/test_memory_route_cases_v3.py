"""V3 data-only checks; previous data is read exclusively through its train API."""

import json

import pytest

from scripts.memory_route_cases_v2 import cases_for_split as v2_cases_for_split
from scripts.memory_route_cases_v3 import (
    CASES,
    VERSION,
    cases_for_split,
    split_statistics,
    validate,
)


def test_v3_has_fixed_train_and_disjoint_new_json_splits():
    assert VERSION == "memory-route-synthetic-v3"
    validate(CASES)
    assert json.loads(json.dumps(CASES, ensure_ascii=False)) == list(CASES)
    assert split_statistics() == {
        "train": {"count": 100, "present": 40, "retrieve": 60},
        "validation": {"count": 40, "present": 16, "retrieve": 24},
        "test": {"count": 40, "present": 16, "retrieve": 24},
    }
    assert cases_for_split("train") == v2_cases_for_split("train")
    assert len({case["id"] for case in CASES}) == 180
    assert len({case["text"] for case in CASES}) == 180
    assert all(len(case["recent_context"]) <= 4 for case in CASES)
    assert all(
        case["id"].startswith("v3-") for case in CASES if case["split"] != "train"
    )


def test_v3_rejects_schema_drift_and_duplicate_identity():
    sample = cases_for_split("validation")[0]
    for invalid in (
        {**sample, "gold": "maybe"},
        {**sample, "split": "train"},
        {**sample, "category": ""},
        {**sample, "recent_context": [{"role": "tool", "content": "错误"}]},
        {**sample, "recent_context": [{"role": "user", "content": "消息"}] * 5},
        {**sample, "extra_field": False},
    ):
        with pytest.raises(ValueError):
            validate([invalid])
    with pytest.raises(ValueError):
        validate([sample, sample])


def test_v3_reader_isolated_from_fixed_train_and_other_readers():
    train = cases_for_split("train")
    train[0]["text"] = "调用者改了训练原文"
    train[0]["recent_context"].append({"role": "user", "content": "新增"})
    assert cases_for_split("train") == v2_cases_for_split("train")
    validation = cases_for_split("validation")
    validation[0]["reason"] = "调用者修改"
    assert cases_for_split("validation")[0]["reason"] != "调用者修改"
    with pytest.raises(ValueError):
        cases_for_split("old_validation")
