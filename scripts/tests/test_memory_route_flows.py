"""结构契约只验证合成连续对话，不把作者 gold 当真人标注。"""

import importlib
import importlib.util
from collections import Counter
from datetime import datetime

import pytest

MODULE = "scripts.memory_route_flows"


def test_flow_dataset_module_is_implemented():
    assert importlib.util.find_spec(MODULE) is not None, "冻结连续对话数据尚未实现"


@pytest.fixture
def data():
    if importlib.util.find_spec(MODULE) is None:
        pytest.skip("缺少数据模块；由实现存在性断言记录 RED")
    return importlib.import_module(MODULE).flows()


def test_exact_twenty_flows_and_two_hundred_turns(data):
    assert len(data) == 20
    assert all(len(flow["turns"]) == 10 for flow in data)
    assert len({flow["id"] for flow in data}) == 20


def test_exact_predeclared_route_counts(data):
    counts = Counter(turn["gold"] for flow in data for turn in flow["turns"])
    assert counts == {"present": 80, "retrieve": 120}


def test_old_original_source_schema(data):
    all_ids = []
    for flow in data:
        assert 3 <= len(flow["old_messages"]) <= 5
        for source in flow["old_messages"]:
            assert set(source) == {"id", "role", "created_at", "text"}
            assert source["role"] in {"user", "assistant"}
            assert source["text"].strip()
            assert datetime.fromisoformat(source["created_at"]).tzinfo is not None
            all_ids.append(source["id"])
    assert len(all_ids) == len(set(all_ids))


def test_bridge_places_all_old_sources_outside_recent_twenty(data):
    for flow in data:
        assert len(flow["bridge_turns"]) >= 22
        assert all(set(turn) == {"user", "assistant"} for turn in flow["bridge_turns"])
        assert all(
            turn["user"].strip() and turn["assistant"].strip() for turn in flow["bridge_turns"]
        )


def test_gold_sources_refer_to_originals_in_the_same_flow(data):
    for flow in data:
        sources = {source["id"] for source in flow["old_messages"]}
        for turn in flow["turns"]:
            assert set(turn["needed_source_ids"]) <= sources
            assert len(turn["needed_source_ids"]) == len(set(turn["needed_source_ids"]))
            if turn["gold"] == "present":
                assert not turn["needed_source_ids"]
            assert turn["reason"].strip()


def test_texts_are_original_unique_and_nonempty(data):
    turns = [turn for flow in data for turn in flow["turns"]]
    assert len({turn["id"] for turn in turns}) == 200
    assert len({turn["text"] for turn in turns}) == 200
    assert all(turn["text"].strip() and turn["assistant"].strip() for turn in turns)


def test_exact_two_long_fields_fit_the_current_interface(data):
    turns = [turn for flow in data for turn in flow["turns"]]
    long_turns = [turn for turn in turns if len(turn["text"]) >= 15_000]
    assert len(long_turns) == 2
    assert all(len(turn["text"]) <= 16_000 for turn in turns)
    assert all(turn["gold"] == "retrieve" for turn in long_turns)
    assert all(turn["category"] == "long_input" for turn in long_turns)


def test_boundary_categories_are_included(data):
    categories = {turn["category"] for flow in data for turn in flow["turns"]}
    assert {
        "daily_share",
        "emotion",
        "missing_name",
        "promise",
        "correction",
        "calendar_uncertain",
        "linked_originals",
        "ai_promise",
        "recent20_not4",
        "recent4",
        "code_switch",
        "adversarial",
        "unknown_history",
        "long_input",
    } <= categories


def test_recent_context_examples_are_present_after_actual_original_replies(data):
    for flow in data:
        seventh = flow["turns"][6]
        ninth = flow["turns"][8]
        assert seventh["gold"] == "present" and seventh["category"] == "recent20_not4"
        assert ninth["gold"] == "present" and ninth["category"] == "recent4"
        assert flow["turns"][1]["assistant"] in seventh["reason"]
        assert flow["turns"][7]["assistant"] in ninth["reason"]


def test_fresh_calls_do_not_share_nested_mutable_objects(data):
    module = importlib.import_module(MODULE)
    independent = module.flows()
    original = independent[0]["turns"][0]["text"]
    data[0]["turns"][0]["text"] = "测试修改"
    data[0]["old_messages"][0]["text"] = "修改源"
    assert independent[0]["turns"][0]["text"] == original
    assert module.flows()[0]["turns"][0]["text"] == original


def test_author_gold_is_declared_without_human_annotation_claim():
    if importlib.util.find_spec(MODULE) is None:
        pytest.skip("数据未实现")
    module = importlib.import_module(MODULE)
    assert "synthetic" in module.GOLD_ORIGIN
    assert "human" not in module.GOLD_ORIGIN
    assert module.DATASET_VERSION == "continuous-originals-v1"
