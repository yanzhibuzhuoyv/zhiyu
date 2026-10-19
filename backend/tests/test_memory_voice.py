"""Memory uncertainty belongs in the companion's voice, with evidence intact."""

import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider, conversation, login

from app.chat_routes import prompt
from app.config import Settings
from app.main import create_app
from app.proactive_routes import contact_prompt


@pytest.mark.parametrize("complete", [True, False])
def test_missing_memory_guidance_does_not_teach_database_wording(complete):
    messages = prompt([], "你知道我的生日吗？", [], memory_complete=complete)
    system = messages[0]["content"]
    assert "把记忆的不确定性表达成自己的回想感受" in system
    assert "我这会儿没想起来，你再同我讲讲好不好" in system
    assert "自然说明‘目前可用的记录无法确认’" not in system
    assert "应先说明目前记录无法确认" not in system
    assert "不能把未找到说成用户没讲过" in system


def test_voice_rule_does_not_discard_known_originals_or_override_personality():
    history = [{"role": "user", "content": "我的生日是公历六月十八日。"},
               {"role": "assistant", "content": "六月十八，我记住了。"}]
    profile = {"mode": "preset", "style": "distant", "role": "partner"}
    messages = prompt(history, "你知道我的生日吗？", [], companion_profile=profile)
    system = messages[0]["content"]
    assert messages[1:-1] == history
    assert "情感疏离" in system
    assert "已有明确原话时直接回答" in system
    assert "不改变绑定性格" in system
    assert "不能把未找到说成用户没讲过" in system


def test_rule_allows_user_requested_technical_discussion_and_explicit_never_told():
    system = prompt([], "给我讲讲RAG数据库怎么工作", [])[0]["content"]
    assert "用户主动讨论技术时可以正常解释" in system
    assert "只有用户明确说自己没讲过时" in system


def test_all_reply_kinds_keep_natural_voice_and_do_not_force_source_numbers():
    source = {"name": "喜好.txt", "text": "喜欢白玫瑰"}
    system = prompt([], "今天看到白玫瑰啦", [source])[0]["content"]
    assert "所有聊天回复都以当前恋人的自然口吻交流" in system
    assert "日常分享、旧事核对、主动消息和配图说明" in system
    assert "日常聊天不展示文档编号" in system
    assert "使用 [1] 等编号注明来源" not in system


def test_proactive_reply_uses_same_voice_without_forging_a_user_message():
    history = [{"role": "user", "content": "今天先忙自己的事。"}]
    daily = {"day_id": "2026-10-09", "current_slot": {"activity": "虚拟晚间休息"}}
    messages = contact_prompt({"mode": "preset", "style": "steady"}, history, daily)
    assert "所有聊天回复都以当前恋人的自然口吻交流" in messages[0]["content"]
    assert "成熟稳重" in messages[0]["content"]
    assert messages[1:-1] == history
    assert "不是用户的聊天原话" in messages[-1]["content"]


def test_normal_chat_receives_voice_rule_in_its_existing_single_reply_call(tmp_path):
    provider = FakeProvider()
    app = create_app(Settings(data_dir=tmp_path), provider_client=provider)
    with TestClient(app) as client:
        headers = login(client)
        identifier = conversation(client, headers)
        result = client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                             json={"content": "你知道我的生日吗？"})
        assert result.status_code == 200, result.text
        assert len(provider.calls) == 1
        assert "把记忆的不确定性表达成自己的回想感受" in provider.calls[0][0]["content"]
        history = client.get(f"/api/conversations/{identifier}/history", headers=headers).json()
        assert history["messages"][0]["content"] == "你知道我的生日吗？"
        assert history["messages"][1]["content"] == result.json()["assistant_message"]["content"]
