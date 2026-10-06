import asyncio
import json

import pytest

from app.memory_semantic import SemanticResponseError, identify_turn, parse_response

CREATED = "2026-10-06T00:00:00+00:00"


def sources(user="这几天总惦记着再吃个苹果", assistant="我陪你慢慢聊"):
    return {"user": {"content": user, "created_at": CREATED},
            "assistant": {"content": assistant, "created_at": CREATED}}


def claim(quote="这几天总惦记着再吃个苹果", **changes):
    return {"quote": quote, "kind": "wish", "subject": "user", "polarity": "affirm",
            "topic_key": "吃:苹果", "confidence": 0.95, "explicit_keep": False,
            "correction": False, "promise_state": None, "date_basis": None,
            "event_date": None, **changes}


def response(user=None, assistant=None):
    return json.dumps({"user": user or [], "assistant": assistant or []}, ensure_ascii=False)


class Provider:
    def __init__(self, output=None, error=None, pause=0):
        self.output = output if output is not None else response([claim()])
        self.error, self.pause, self.calls = error, pause, []

    async def complete(self, provider_id, key, model, messages, max_tokens=2048):
        self.calls.append((provider_id, key, model, messages, max_tokens))
        if self.pause:
            await asyncio.sleep(self.pause)
        if self.error:
            raise self.error
        return self.output


async def test_one_extra_model_call_keeps_both_original_sources_and_selected_company():
    provider = Provider()
    result = await identify_turn(provider, "deepseek", "synthetic-semantic-key", "test-model", sources(),
                                 catalogue=[{"kind": "wish", "subject": "user", "topic_key": "吃:苹果"}])
    assert result["status"] == "ok" and result["version"] == "semantic-v1"
    assert len(provider.calls) == 1
    company, key, model, messages, tokens = provider.calls[0]
    assert (company, key, model, tokens) == ("deepseek", "synthetic-semantic-key", "test-model", 4096)
    assert key not in json.dumps(messages)
    assert "这几天总惦记着再吃个苹果" in messages[1]["content"]
    assert "我陪你慢慢聊" in messages[1]["content"]
    assert result["claims"]["user"][0]["span_start"] == 0


def test_semantic_rephrasing_does_not_require_a_literal_sentence_template():
    text = "能让人静下来的那种红茶，一直是我心头好；以后可别忘了这点。"
    parsed = parse_response(response([claim(text, kind="preference", topic_key="喜欢:红茶", explicit_keep=True)]), sources(text))
    assert parsed["user"][0]["quote"] == text and parsed["user"][0]["explicit_keep"]


@pytest.mark.parametrize("changes", [
    {"subject": "other"}, {"subject": "assistant"}, {"polarity": "uncertain"}, {"confidence": 0.84},
])
def test_other_people_uncertainty_and_low_confidence_are_conservative(changes):
    assert parse_response(response([claim(**changes)]), sources())["user"] == []


def test_ai_echo_can_never_supply_user_birthday_or_preference():
    text = "你的生日在九月，我知道你喜欢红茶"
    ai = [claim(text, kind="profile", subject="user", topic_key="user:birthday")]
    assert parse_response(response(assistant=ai), sources(assistant=text))["assistant"] == []


def test_ai_own_promise_is_scoped_to_ai_not_to_user_fact():
    text = "等2026-10-08到了，我一定陪你去海边走走"
    ai = claim(text, kind="promise", subject="assistant", topic_key="承诺:去海边", promise_state="pending", event_date="2026-10-08")
    result = parse_response(response(assistant=[ai]), sources(assistant=text))
    assert result["assistant"][0]["subject"] == "assistant"
    assert result["assistant"][0]["promise_state"] == "pending"


def test_birthday_in_long_sentence_has_exact_original_offset_and_unknown_calendar():
    text = "聊了这么多，我还是想告诉你：我的生日在九月六日。"
    quote = "我的生日在九月六日"
    result = parse_response(response([claim(quote, kind="profile", topic_key="生日", date_basis="gregorian")]), sources(text))
    item = result["user"][0]
    assert item["topic_key"] == "user:birthday" and item["date_basis"] is None
    assert text[item["span_start"]:item["span_end"]] == quote


def test_ambiguous_repeated_quote_requires_explicit_codepoint_range():
    text = "苹果和苹果都出现了"
    with pytest.raises(SemanticResponseError):
        parse_response(response([claim("苹果")]), sources(text))
    parsed = parse_response(response([claim("苹果", start=3, end=5)]), sources(text))
    assert parsed["user"][0]["span_start"] == 3


def test_overlapping_quote_occurrences_are_not_counted_as_unique():
    with pytest.raises(SemanticResponseError):
        parse_response(response([claim("aa")]), sources("aaa"))


@pytest.mark.parametrize("change", [{"quote": "模型编造的原话"}, {"start": 0},
                                    {"start": True, "end": 2}, {"start": 1, "end": 3},
                                    {"id": "forged-owner-id"}, {"topic_key": "https://injected.example"},
                                    {"confidence": True}, {"confidence": 2}])
def test_untrusted_model_fields_cannot_forge_source_or_schema(change):
    with pytest.raises(SemanticResponseError):
        parse_response(response([claim(**change)]), sources())


@pytest.mark.parametrize("output", ["```json\n{}\n```", "not-json", '{"user":[],"assistant":[],"owner":"forged"}',
                                     '{"user":[],"user":[],"assistant":[]}',
                                     '{"user":[{"confidence":NaN}],"assistant":[]}',
                                     "x" * 65537], ids=["fenced", "plain", "extra-field", "duplicate-field", "nan", "oversized"])
def test_invalid_json_duplicate_fields_and_oversized_responses_fail_closed(output):
    with pytest.raises(SemanticResponseError):
        parse_response(output, sources())


def test_overlarge_claim_array_is_not_silently_clipped():
    with pytest.raises(SemanticResponseError):
        parse_response(response([claim()] * 9), sources())


@pytest.mark.parametrize("text", ["我明天准备完成陪你去海边的承诺", "我还没有兑现那个约定"])
def test_model_cannot_mark_future_or_unfulfilled_promise_as_fulfilled(text):
    result = parse_response(response([claim(text, kind="promise", promise_state="fulfilled")]), sources(text))
    assert result["user"] == []


def test_generated_iso_date_cannot_replace_original_relative_time():
    text = "答应你明天去看星空"
    with pytest.raises(SemanticResponseError):
        parse_response(response([claim(text, kind="promise", promise_state="pending", event_date="2026-10-07")]), sources(text))


async def test_demo_is_explicitly_unavailable_not_disguised_as_semantic_rule():
    provider = Provider()
    result = await identify_turn(provider, "demo", "demo", "demo", sources())
    assert result["status"] == "unavailable" and provider.calls == []


async def test_long_role_is_not_truncated_and_other_role_can_be_classified():
    provider = Provider()
    result = await identify_turn(provider, "deepseek", "synthetic", "model", sources(assistant="原话" * 9000))
    assert result["status"] == "partial" and result["roles"]["assistant"] == "skipped_large"
    assert result["claims"]["assistant"] == [] and len(provider.calls) == 1
    assert "原话" not in provider.calls[0][3][1]["content"]


@pytest.mark.parametrize("provider", [Provider(error=RuntimeError("private-key must not leak")),
                                      Provider(output="not-json"), Provider(pause=0.1)])
async def test_failed_or_timed_out_recognition_returns_safe_status(provider):
    result = await identify_turn(provider, "deepseek", "synthetic", "model", sources(), timeout=0.01)
    assert result["status"] == "failed" and result["claims"] == {"user": [], "assistant": []}
    assert "private-key" not in json.dumps(result)
