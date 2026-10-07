"""Source fidelity is mechanical; natural claims remain semantically unverified."""

import copy
import json

import pytest

from scripts.evidence_reply import VERSION, parse_reply, structured_messages

REF = {
    "message_id": "synthetic-u1",
    "role": "user",
    "text": "昨天我还不确定是哪一本。",
    "created_at": "2026-09-25T00:30:00+08:00",
    "source_current": None,
    "truncated": True,
}
SOURCE = {
    "kind": "source",
    "source_id": REF["message_id"],
    "quote": REF["text"],
    "scope": "historical",
}


def raw(segments, **fields):
    return json.dumps({"version": VERSION, "segments": segments, **fields}, ensure_ascii=False)


def test_all_segments_exact_offsets_and_no_time_or_negation_rewrite():
    refs = [copy.deepcopy(REF)]
    before = copy.deepcopy(refs)
    parsed = parse_reply(
        raw([{"kind": "natural", "text": "我陪你。"}, SOURCE, {"kind": "uncertain"}]), refs
    )
    citation = parsed["citations"][0]
    assert parsed["rendered"][citation["char_start"] : citation["char_end"]] == REF["text"]
    assert citation["role"] == "user" and citation["created_at"] == REF["created_at"]
    assert "你的" in parsed["rendered"] and "节选" in parsed["rendered"]
    assert "保存" in parsed["rendered"] and "事件发生时间" in parsed["rendered"]
    assert parsed["rendered"].endswith("本次提供的记录还不足以确认。")
    assert refs == before


def test_ai_own_promise_and_corrected_history_are_not_user_current_fact():
    ref = {**REF, "role": "assistant", "source_current": False}
    parsed = parse_reply(raw([SOURCE]), [ref])
    assert "AI" in parsed["rendered"] and "更正" in parsed["rendered"]
    assert parsed["citations"][0]["role"] == "assistant"


def test_natural_text_is_not_semantically_certified_or_replaced():
    unsafe = "你从来没有说过那件事。"
    parsed = parse_reply(raw([{"kind": "natural", "text": unsafe}]), [REF])
    assert parsed["rendered"] == unsafe
    assert parsed["citations"] == []
    assert "safe" not in parsed and "model_checked" not in parsed


@pytest.mark.parametrize(
    "segment",
    [
        {**SOURCE, "quote": "哪一本"},
        {**SOURCE, "source_id": "missing"},
        {**SOURCE, "scope": "current"},
        {**SOURCE, "role": "user"},
        {"kind": "uncertain", "text": "没有"},
        {"kind": "unknown"},
        {"kind": "natural", "text": ""},
        {"kind": "natural", "text": 1},
        {"kind": "natural", "text": "长" * 64001},
        [],
        None,
    ],
)
def test_invalid_segments_fail_without_partial_rendering(segment):
    with pytest.raises(ValueError):
        parse_reply(raw([segment]), [REF])


@pytest.mark.parametrize(
    "value",
    [
        "not json",
        '{"version":"x","version":"evidence-reply-v1","segments":[]}',
        '{"version":"evidence-reply-v1","segments":[NaN]}',
        raw([]),
        raw([SOURCE, SOURCE]),
        raw([{"kind": "uncertain"}] * 33),
        raw([{"kind": "natural", "text": "长" * 32001}] * 2),
        raw([SOURCE], extra=True),
        json.dumps({"version": "old", "segments": [SOURCE]}),
        " " * (2 * 1024 * 1024 + 1),
        json.dumps([SOURCE]),
    ],
    ids=lambda value: type(value).__name__,
)
def test_invalid_json_top_level_and_resource_limits(value):
    with pytest.raises(ValueError):
        parse_reply(value, [REF])


@pytest.mark.parametrize(
    "updates",
    [
        {"created_at": "2026-09-25T00:30:00"},
        {"created_at": "not date"},
        {"role": "system"},
        {"message_id": "$reserved"},
        {"message_id": ""},
        {"source_current": "yes"},
        {"text": ""},
        {"truncated": "true"},
    ],
)
def test_invalid_source_metadata(updates):
    with pytest.raises(ValueError):
        parse_reply(raw([{"kind": "uncertain"}]), [{**REF, **updates}])


def test_ambiguous_source_id_and_aggregate_source_limit():
    with pytest.raises(ValueError):
        parse_reply(raw([SOURCE]), [REF, {**REF, "text": "别的一段"}])
    with pytest.raises(ValueError):
        parse_reply(raw([SOURCE]), [REF] * 65)
    with pytest.raises(ValueError):
        parse_reply(
            raw([SOURCE]),
            [{**REF, "message_id": f"long-{i}", "text": "长" * 64000} for i in range(3)],
        )


def test_prompt_keeps_system_profile_and_current_query_with_no_mutation():
    base = [
        {"role": "system", "content": "固定人格"},
        {"role": "assistant", "content": "我在。"},
        {"role": "user", "content": "完整提问，不要丢尾部"},
    ]
    before = copy.deepcopy(base)
    result = structured_messages(base, [REF], "2026-10-07T04:00:00+00:00")
    assert result[0] == base[0] and result[-1] == base[-1] and base == before
    assert any("JSON" in m["content"] and "natural" in m["content"] for m in result)
    assert any(REF["text"] in m["content"] and REF["created_at"] in m["content"] for m in result)
    result[0]["content"] = "changed"
    assert base == before


@pytest.mark.parametrize(
    "base,clock",
    [
        ([], "2026-10-07T04:00:00+00:00"),
        ([{"role": "assistant", "content": "末尾不是用户"}], "2026-10-07T04:00:00+00:00"),
        ([{"role": "user", "content": "提问"}], "2026-10-07T04:00:00"),
        ([{"role": "user", "content": "", "extra": True}], "2026-10-07T04:00:00+00:00"),
    ],
)
def test_invalid_prompt_input(base, clock):
    with pytest.raises(ValueError):
        structured_messages(base, [REF], clock)


def test_prompt_checks_aggregate_budget_before_network_request():
    base = [{"role": "system", "content": "长" * 80000}, {"role": "user", "content": "长" * 80000}]
    with pytest.raises(ValueError, match="base_text_limit"):
        structured_messages(base, [REF], "2026-10-07T04:00:00+00:00")
