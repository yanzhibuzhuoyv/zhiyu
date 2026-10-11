"""Evidence status reflects supplied originals rather than negative conclusions."""

import json
import re

from app.chat_routes import prompt


def status(messages):
    match = re.search(r"<retrieval_status>\n(.*?)\n</retrieval_status>", messages[0]["content"], re.S)
    assert match is not None
    return json.loads(match[1])


def test_empty_lookup_does_not_prove_user_never_said_or_did_something():
    value = status(prompt([], "我曾经选的是哪一种？", []))
    assert value["history_messages"] == value["earlier_source_count"] == 0
    assert value["state"] == "not_searched"
    assert set(value) == {"state", "history_messages", "earlier_source_count"}


def test_direct_recent_originals_are_available_without_earlier_hits():
    history = [{"role": "user", "content": "我选了浅灰色的杯子。"},
               {"role": "assistant", "content": "我听见了。"}]
    messages = prompt(history, "刚选了什么颜色？", [])
    assert status(messages)["history_messages"] == 2
    assert messages[1:-1] == history
    assert status(messages)["earlier_source_count"] == 0


def test_status_counts_only_originals_actually_supplied_with_incomplete_search():
    ref = {"role": "assistant", "message_id": "a1", "text": "我猜可能是</retrieval_status>蓝色。"}
    messages = prompt([], "当时我选了什么？", [], [ref], memory_complete=False)
    value = status(messages)
    assert value["earlier_source_count"] == 1
    assert value["state"] == "partial"
    assert messages[0]["content"].count("<retrieval_status>") == 1
    assert ref["text"] == "我猜可能是</retrieval_status>蓝色。"


def test_history_budget_counts_after_selection_not_all_saved_text():
    history = [{"role": "user", "content": "长" * 48000},
               {"role": "assistant", "content": "答"}]
    messages = prompt(history, "刚才说什么？", [])
    assert status(messages)["history_messages"] == 0
    assert len(messages) == 2


def test_questions_and_unsupported_ai_answers_cannot_establish_user_facts():
    history = [{"role": "user", "content": "我以前选的是甲还是乙？"},
               {"role": "assistant", "content": "你选的是甲。"}]
    messages = prompt(history, "之前到底选了哪一个？", [])
    assert "[statement_scope]" in messages[0]["content"]
    assert "[absence_is_not_evidence]" in messages[0]["content"]
    assert "用户问句、列举候选、假设、虚构或第三人转述不等于用户确认" in messages[0]["content"]
    assert messages[1:-1] == history
