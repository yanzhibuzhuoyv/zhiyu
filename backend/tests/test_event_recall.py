from datetime import UTC, datetime, timedelta

import pytest

from app.chat_routes import prompt
from app.conversation_memory import conversation_context
from app.database import ChatDatabase
from app.memory_index import MemoryIndex, MemoryIndexError
from app.memory_ranking import EventWindow


@pytest.fixture
def chat_setup(tmp_path):
    chat = ChatDatabase(tmp_path)
    identifier = chat.create_conversation("owner", "恋人", "model", False)["id"]
    return chat, identifier


def bury(chat, identifier):
    for _ in range(25):
        chat.save_turn("owner", identifier, "散步日常。", "收到。", [])


def test_chicken_query_recalls_two_separate_originals_and_excludes_future_third_party(chat_setup):
    chat, identifier = chat_setup
    birthday = chat.save_turn("owner", identifier, "前天是我生日，家里给我办了小聚会。", "祝你开心。", [])
    chicken = chat.save_turn("owner", identifier, "我前天晚上吃了鸡翅，是烤的。", "好吃吗？", [])
    future = chat.save_turn("owner", identifier, "明天是隔壁朋友的生日，我打算去买礼物。", "挑份礼物吧。", [])
    ai_only = chat.save_turn("owner", identifier, "晚饭喝了汤。", "今天是我这个 AI 的生日。", [])
    bury(chat, identifier)
    _, refs = conversation_context(chat, "owner", identifier, "我想吃鸡翅")
    by_id = {ref["message_id"]: ref for ref in refs}
    for saved in (birthday, chicken):
        original = saved["user_message"]
        ref = by_id[original["id"]]
        assert ref["text"] == original["content"]
        assert ref["role"] == "user" and ref["created_at"] == original["created_at"]
        assert ref["turn_id"] == original["id"]
    event = by_id[birthday["user_message"]["id"]]
    assert event["reason"] == "nearby_event"
    assert event["anchor_ids"] == [chicken["user_message"]["id"]]
    assert event["relation_confirmed"] is False
    assert future["user_message"]["id"] not in by_id
    assert ai_only["assistant_message"]["id"] not in by_id
    assert len(chat.list_messages("owner", identifier)) == 58  # retrieval writes no fused record


def test_event_lookup_scopes_before_limit_and_uses_matching_fragment(chat_setup):
    chat, identifier = chat_setup
    good = chat.save_turn("owner", identifier, "普通日记。" * 1600 + "前天是我的生日，我们庆祝了。", "收到。", [])
    now = datetime.now(UTC)
    window = EventWindow("anchor", 100, now, now - timedelta(hours=12), now + timedelta(hours=12))
    other = chat.create_conversation("other", "隔离", "model", False)["id"]
    for _ in range(40):
        chat.save_turn("other", other, "今天是我的生日。", "庆祝吧。", [])
    found = chat.memory_event_candidates("owner", identifier, 3, [window], limit=1)
    assert len(found) == 1 and found[0]["user_id"] == good["user_message"]["id"]
    assert "前天是我的生日" in found[0]["user_text"]
    assert found[0]["owner"] == "owner" and found[0]["conversation_id"] == identifier
    assert chat.memory_event_candidates("wrong-owner", identifier, 3, [window]) == []
    assert chat.memory_event_candidates("owner", identifier, 2, [window]) == []
    distant = EventWindow("anchor", 100, now, now - timedelta(days=9), now - timedelta(days=8))
    assert chat.memory_event_candidates("owner", identifier, 3, [distant]) == []
    assert chat.memory_event_candidates("owner", identifier, 3, []) == []
    assert chat.memory_event_candidates("owner", identifier, 3, [window], limit=0) == []


def test_prompt_preserves_relative_event_dates_and_marks_link_as_unconfirmed():
    refs = [{"message_id": "birthday", "turn_id": "birthday", "role": "user",
             "text": "昨天是我的生日。", "created_at": "2026-10-05T01:00:00+00:00",
             "reason": "nearby_event", "relation_confirmed": False}]
    system = prompt([], "我想吃鸡翅", [], refs)[0]["content"]
    assert "当前服务器时间（UTC）" in system
    assert "保存时间" in system and "不是事件发生日期" in system
    assert "不证明是同一天或同一事件" in system
    assert "昨天是我的生日。" in system


def test_past_dates_in_same_recording_window_are_not_rewritten_to_same_event(chat_setup):
    chat, identifier = chat_setup
    birthday = chat.save_turn("owner", identifier, "昨天是我的生日。", "收到。", [])
    chicken = chat.save_turn("owner", identifier, "我前天吃了鸡翅。", "收到。", [])
    bury(chat, identifier)
    _, refs = conversation_context(chat, "owner", identifier, "我想吃鸡翅")
    assert {ref["text"] for ref in refs if ref["role"] == "user"} == {
        birthday["user_message"]["content"], chicken["user_message"]["content"]}
    assert all(ref["relation_confirmed"] is False and "event_date" not in ref for ref in refs)


def test_natural_user_update_survives_without_explicit_correction_word(chat_setup):
    chat, identifier = chat_setup
    for _ in range(8):
        chat.save_turn("owner", identifier, "AuroraCafe anniversary is July ninth.", "Recorded.", [])
    updated = chat.save_turn("owner", identifier, "Our anniversary is August tenth.", "Received.", [])
    bury(chat, identifier)
    _, refs = conversation_context(chat, "owner", identifier, "AuroraCafe anniversary")
    assert any(ref["message_id"] == updated["user_message"]["id"] for ref in refs)


def test_closed_event_index_raises_safe_error_without_original_data():
    import sqlite3

    now = datetime.now(UTC)
    window = EventWindow("anchor", 1, now, now, now)
    db = sqlite3.connect(":memory:")
    db.close()
    with pytest.raises(MemoryIndexError, match="事件索引暂时无法检索"):
        MemoryIndex().event_candidates(db, "private-owner", "private-chat", 10, [window])


@pytest.mark.parametrize("query,anchor_text,related_text", [
    ("月光曲", "前天我听了月光曲。", "前天我心情很低落，后来才好了些。"),
    ("海边", "那天我们去了海边。", "当天我们看到了流星，很惊喜。"),
    ("白粥", "昨天我只吃了白粥。", "昨天我感冒了，休息了一整天。"),
])
def test_shared_temporal_originals_support_arbitrary_topics_without_domain_keywords(
        chat_setup, query, anchor_text, related_text):
    chat, identifier = chat_setup
    related = chat.save_turn("owner", identifier, related_text, "收到。", [])
    anchor = chat.save_turn("owner", identifier, anchor_text, "收到。", [])
    bury(chat, identifier)
    _, refs = conversation_context(chat, "owner", identifier, query)
    user_refs = {ref["message_id"]: ref for ref in refs if ref["role"] == "user"}
    assert related["user_message"]["id"] in user_refs
    assert anchor["user_message"]["id"] in user_refs
    assert user_refs[related["user_message"]["id"]]["relation_confirmed"] is False
