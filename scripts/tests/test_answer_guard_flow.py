"""Completion, bounded repair and immutable evidence are not semantic accuracy."""

import asyncio
import copy
import json

import pytest

from scripts.answer_guard_flow import guard_answer
from scripts.memory_answer_guard import VERSION, evidence_pack


def review(draft, *, bad=False):
    return json.dumps({"version": VERSION, "reviewed_all": True, "claims": [
        {"quote": draft, "verdict": "unsupported" if bad else "non_factual",
         "issue": "scope" if bad else "none", "evidence": []}]})


def result(content, complete=True):
    return {"content": content, "grade": {"usable_pass": complete}, "latency_seconds": .2,
            "done_received": complete, "finish_reason": "stop" if complete else "length"}


@pytest.mark.parametrize("stage", ["clean", "repair", "bad-recheck", "parse", "transport", "repair-transport"])
def test_guard_bounds_and_never_delivers_failed_candidate(stage):
    query, draft = "我说过什么？", "你从没告诉过我。"
    evidence = evidence_pack(query, [])
    base = [{"role": "system", "content": "固定人格"}, {"role": "user", "content": query}]
    before = copy.deepcopy((evidence, base))
    events = []

    async def generate(messages, phase, mode):
        events.append((messages, phase, mode))
        if stage == "transport":
            return result("", False)
        if phase == "rewrite":
            return result("目前无法确认。", stage != "repair-transport")
        if stage == "parse":
            return result("not json")
        candidate = draft if phase == "review" else "目前无法确认。"
        bad = stage != "clean" and (phase == "review" or stage == "bad-recheck")
        return result(review(candidate, bad=bad))

    outcome = asyncio.run(guard_answer(query, draft, evidence, base, generate, "enabled"))
    assert (evidence, base) == before
    assert outcome["draft_content"] == draft
    assert len(events) <= 3
    if stage in {"clean", "repair"}:
        assert outcome["status"] == ("model_checked" if stage == "clean" else "model_checked_repaired")
        assert outcome["final_content"] == (draft if stage == "clean" else "目前无法确认。")
    else:
        assert outcome["status"] == "unverified" and outcome["final_content"] is None
    if stage in {"repair", "bad-recheck", "repair-transport"}:
        assert events[1][1:] == ("rewrite", "enabled")
        assert events[1][0][:2] == base
    assert outcome["manual_review_required"] is True


def test_review_valid_quotes_do_not_make_independent_truth_grade():
    async def generate(_messages, _phase, _mode):
        return result(review("我在陪你。"))

    query = "今天有点累。"
    outcome = asyncio.run(guard_answer(query, "我在陪你。", evidence_pack(query, []),
                                     [{"role": "user", "content": query}], generate, "disabled"))
    assert outcome["status"] == "model_checked"
    assert "factual_pass" not in outcome


def test_overlong_full_draft_is_retained_without_cloud_or_delivery():
    async def generate(*_args):
        pytest.fail("overlong draft must fail before another cloud call")

    draft = "长" * 64001
    query = "陪我聊聊。"
    outcome = asyncio.run(guard_answer(query, draft, evidence_pack(query, []),
                                     [{"role": "user", "content": query}], generate, "disabled"))
    assert outcome["status"] == "unverified" and outcome["final_content"] is None
    assert outcome["draft_content"] == draft
    assert outcome["events"][0]["error"] == "ValueError"


def test_bad_repair_input_fails_with_original_draft_preserved():
    async def generate(*_args):
        return result(review("你从没说过。", bad=True))

    outcome = asyncio.run(guard_answer("原题", "你从没说过。", evidence_pack("原题", []),
                                     [{"role": "user", "content": "被换过的题"}], generate, "disabled"))
    assert outcome["status"] == "unverified" and outcome["final_content"] is None
    assert outcome["events"][-1]["phase"] == "rewrite_input"


def test_same_explicit_current_clock_reaches_first_and_second_audits():
    seen = []
    stamp = "2026-10-07T00:00:00+00:00"

    async def generate(messages, phase, _mode):
        if phase == "rewrite":
            return result("九月的那周如此，现在无法确认。")
        seen.append(json.loads(messages[-1]["content"])["current_utc"])
        return result(review("这周还是如此。" if phase == "review" else "九月的那周如此，现在无法确认。",
                             bad=phase == "review"))

    query = "现在呢？"
    outcome = asyncio.run(guard_answer(query, "这周还是如此。", evidence_pack(query, []),
                         [{"role": "user", "content": query}], generate, "disabled", reference_time=stamp))
    assert outcome["status"] == "model_checked_repaired"
    assert seen == [stamp, stamp]


def test_invalid_clock_is_unverified_without_losing_draft():
    async def generate(*_args):
        pytest.fail("timezone-free clock must be rejected before cloud")

    query = "现在呢？"
    outcome = asyncio.run(guard_answer(query, "我在。", evidence_pack(query, []),
                         [{"role": "user", "content": query}], generate, "disabled", reference_time="2026-10-07"))
    assert outcome["status"] == "unverified" and outcome["draft_content"] == "我在。"
