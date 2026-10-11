"""Distinguish skipped lookup from a completed bounded lookup, without cloud calls."""

import json
import re

import pytest
import test_conversation_memory as memory_fixtures

from app.chat_routes import prompt
from app.conversation_memory import conversation_context_result

memory_setup = memory_fixtures.memory_setup
seed_history = memory_fixtures.seed_history


def factual_status(messages):
    system = messages[0]["content"]
    match = re.search(r"<retrieval_status>\n(.*?)\n</retrieval_status>", system, re.S)
    assert match is not None
    return json.loads(match[1])


def test_generic_query_is_not_searched_and_does_not_call_lookup(memory_setup, monkeypatch):
    _, _, owner, identifier, _, chat = memory_setup
    seed_history(chat, owner, identifier)

    def forbidden(*args, **kwargs):
        pytest.fail("empty query terms must not claim to have performed a lookup")

    monkeypatch.setattr(chat, "memory_lookup", forbidden)
    result = conversation_context_result(chat, owner, identifier, "你还记得我吗？")
    assert result.references == []
    assert result.status == "not_searched"
    assert result.complete is False


def test_empty_archive_has_not_been_searched(memory_setup):
    _, _, owner, identifier, _, chat = memory_setup
    result = conversation_context_result(chat, owner, identifier, "生日")
    assert result.status == "not_searched"
    assert result.references == []


def test_completed_zero_hits_is_ok_and_preserves_every_original(memory_setup):
    _, _, owner, identifier, _, chat = memory_setup
    seed_history(chat, owner, identifier)
    before = chat.list_messages(owner, identifier)
    result = conversation_context_result(chat, owner, identifier, "不存在的天文台编码")
    assert result.status == "ok"
    assert result.complete is True
    assert result.references == []
    assert chat.list_messages(owner, identifier) == before


def test_incomplete_zero_hits_is_partial(memory_setup, monkeypatch):
    _, _, owner, identifier, _, chat = memory_setup
    seed_history(chat, owner, identifier)
    monkeypatch.setattr(chat, "memory_lookup", lambda *args: {
        "turns": [], "complete": False, "next_before": 5,
    })
    result = conversation_context_result(chat, owner, identifier, "不存在的天文台编码")
    assert result.status == "partial"
    assert result.complete is False
    assert result.next_cursor == 5


def test_route_exposes_tristate_without_changing_chat_originals(memory_setup):
    client, headers, owner, identifier, provider, chat = memory_setup
    seed_history(chat, owner, identifier)
    result = client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                         json={"content": "你还记得我吗？"})
    assert result.status_code == 200
    assert result.json()["memory_search"]["status"] == "not_searched"
    assert result.json()["memory_search"]["complete"] is False
    assert factual_status(provider.calls[-1])["state"] == "not_searched"
    assert result.json()["user_message"]["content"] == "你还记得我吗？"


def test_prompt_default_is_not_searched_and_status_contains_only_observations():
    system = prompt([], "你知道我的生日吗？", [])
    assert factual_status(system) == {
        "state": "not_searched", "history_messages": 0, "earlier_source_count": 0,
    }
    assert "absence_proves_never_said" not in system[0]["content"]
    assert "<memory_evidence_status>" not in system[0]["content"]
    assert "<behavior_rules>" in system[0]["content"]


@pytest.mark.parametrize("value", ["ok", "not_searched", "partial"])
def test_every_status_retains_absence_and_natural_voice_rules(value):
    system = prompt([], "你知道我的生日吗？", [], retrieval_state=value)
    assert factual_status(system)["state"] == value
    assert "[absence_is_not_evidence]" in system[0]["content"]
    assert "不能把未找到说成用户没讲过" in system[0]["content"]
    assert "我没印象了" in system[0]["content"]


def test_one_rule_can_be_disabled_without_changing_another_rule_or_original():
    history = [{"role": "user", "content": "我喜欢白玫瑰。"}]
    baseline = prompt(history, "今天见到花了", [])
    variant = prompt(history, "今天见到花了", [], disabled_rule_ids={"daily_first"})
    assert "[daily_first]" in baseline[0]["content"]
    assert "[daily_first]" not in variant[0]["content"]
    assert "[absence_is_not_evidence]" in variant[0]["content"]
    assert baseline[1:] == variant[1:]


def test_unknown_rule_or_invalid_state_is_rejected():
    with pytest.raises(ValueError):
        prompt([], "生日", [], disabled_rule_ids={"invented_rule"})
    with pytest.raises(ValueError):
        prompt([], "生日", [], retrieval_state="definitely_never_said")
