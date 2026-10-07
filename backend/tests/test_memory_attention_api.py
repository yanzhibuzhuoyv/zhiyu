import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider, login

from app.config import Settings
from app.main import create_app
from app.source_codec import ChatStorageError


@pytest.fixture
def setup(tmp_path):
    provider = FakeProvider()
    app = create_app(Settings(data_dir=tmp_path), provider_client=provider)
    with TestClient(app) as client:
        headers = login(client)
        owner = app.state.sessions.get(headers["Authorization"][7:]).owner
        chat = app.state.chat
        identifier = chat.create_conversation(owner, "恋人", "test-model", False)["id"]
        rose = chat.save_turn(owner, identifier, "我最喜欢白玫瑰，纪念日是八月十六日。", "谢谢你分享。", [])
        chat.save_turn(owner, identifier, "我收藏一本初版诗集，封面是蓝色的。", "是一本怎样的书？", [])
        for _ in range(25):
            chat.save_turn(owner, identifier, "今天完成了散步。", "休息一下吧。", [])
        yield app, client, headers, owner, identifier, provider, rose


def send(client, headers, identifier, text):
    return client.post(f"/api/conversations/{identifier}/messages", headers=headers, json={"content": text})


def test_successful_offer_gets_hint_next_turn_but_original_remains_recallable(setup):
    _, client, headers, _, identifier, _, rose = setup
    first = send(client, headers, identifier, "白玫瑰")
    second = send(client, headers, identifier, "还记得白玫瑰吗？")
    assert first.status_code == second.status_code == 200
    before, after = first.json()["memory_refs"], second.json()["memory_refs"]
    assert not any(r["recently_offered"] for r in before)
    assert all(r["recently_offered"] for r in after)
    assert [r["message_id"] for r in before] == [r["message_id"] for r in after]
    assert after[0]["message_id"] == rose["user_message"]["id"]
    assert after[0]["text"] == rose["user_message"]["content"]
    assert after[0]["presentation_hint"] == "optional_background"


def test_failed_cloud_reply_does_not_replace_previous_offer(setup):
    app, client, headers, owner, identifier, provider, _ = setup
    assert send(client, headers, identifier, "白玫瑰").status_code == 200
    old_messages = app.state.chat.list_messages(owner, identifier)
    provider.fail = True
    assert send(client, headers, identifier, "初版诗集").status_code == 429
    assert app.state.chat.list_messages(owner, identifier) == old_messages
    provider.fail = False
    assert all(r["recently_offered"] for r in send(client, headers, identifier, "白玫瑰").json()["memory_refs"])


def test_failed_atomic_save_does_not_mark_offer_as_success(setup, monkeypatch):
    app, client, headers, _, identifier, _, _ = setup
    save = app.state.chat.save_turn

    def fail(*args, **kwargs):
        raise ChatStorageError("合成提交失败")

    monkeypatch.setattr(app.state.chat, "save_turn", fail)
    assert send(client, headers, identifier, "白玫瑰").status_code == 503
    monkeypatch.setattr(app.state.chat, "save_turn", save)
    result = send(client, headers, identifier, "白玫瑰").json()
    assert not any(r["recently_offered"] for r in result["memory_refs"])


def test_delete_forgets_offers_without_affecting_foreign_scope(setup):
    app, client, headers, owner, identifier, _, _ = setup
    refs = send(client, headers, identifier, "白玫瑰").json()["memory_refs"]
    app.state.memory_attention.remember(owner, "other-conversation", refs)
    assert client.delete(f"/api/conversations/{identifier}", headers=headers).status_code == 204
    assert not any(r["recently_offered"] for r in app.state.memory_attention.decorate(owner, identifier, refs))
    assert all(r["recently_offered"] for r in app.state.memory_attention.decorate(owner, "other-conversation", refs))
