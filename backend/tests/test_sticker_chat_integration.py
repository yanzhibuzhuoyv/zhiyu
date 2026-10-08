import re
from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient
from test_media_assets import image_bytes, metadata

from app.config import Settings
from app.main import create_app


class StickerProvider:
    def __init__(self):
        self.calls = []
        self.choice = "none"
        self.text = "忙完了就来这里歇一会儿，我陪着你。"

    async def validate(self, *args):
        return None

    async def complete(self, provider_id, api_key, model, messages):
        self.calls.append(messages)
        nonce = re.search(r"\[\[ZHIYU_STICKER:([a-f0-9]{32}):", messages[0]["content"])
        if not nonce:
            return self.text
        return self.text + f"\n[[ZHIYU_STICKER:{nonce[1]}:{self.choice}]]"


@pytest.fixture
def setup(tmp_path):
    provider = StickerProvider()
    app = create_app(Settings(data_dir=tmp_path), provider_client=provider)
    app.state.contact_clock = lambda: datetime.now(UTC).replace(hour=4)
    with TestClient(app) as client:
        token = client.post("/api/auth/login", json={"provider_id": "deepseek", "api_key": "synthetic-test-key", "model": "test"}).json()["token"]
        headers = {"Authorization": "Bearer " + token}
        identifier = client.post("/api/conversations", headers=headers, json={
            "use_rag": False, "companion_profile": {"mode": "preset", "style": "playful"},
        }).json()["id"]
        sticker = app.state.media.ingest("sticker", image_bytes(), metadata(category="cat", tags=["comfort"], action="伸爪陪伴"))
        yield client, provider, app, headers, identifier, sticker


def test_daily_share_can_receive_sticker_without_request_or_notification_permission(setup):
    client, provider, app, headers, identifier, sticker = setup
    provider.choice = sticker["id"]
    path = f"/api/conversations/{identifier}"
    assert client.get(path + "/proactive-preferences", headers=headers).json()["enabled"] is False
    result = client.post(path + "/messages", headers=headers, json={"content": "今天终于忙完了。"})
    assert result.status_code == 200, result.text
    answer = result.json()["assistant_message"]
    assert len(provider.calls) == 1
    assert answer["content"] == provider.text
    assert [item["id"] for item in answer["media_attachments"]] == [sticker["id"]]
    history = client.get(path + "/messages", headers=headers).json()
    assert history[0]["content"] == "今天终于忙完了。"
    assert all("ZHIYU_STICKER" not in row["content"] for row in history)
    owner = app.state.sessions.get(headers["Authorization"].split()[1]).owner
    assert all("ZHIYU_STICKER" not in row["content"] for row in app.state.chat.list_messages(owner, identifier))


def test_none_is_a_real_choice_and_keyword_does_not_force_sticker(setup):
    client, provider, app, headers, identifier, _ = setup
    owner = app.state.sessions.get(headers["Authorization"].split()[1]).owner
    for _ in range(2):
        app.state.chat.save_turn(owner, identifier, "前面的日常", "嗯", [])
    result = client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                         json={"content": "哈哈好开心，想你啦。"})
    assert result.status_code == 200
    assert result.json()["assistant_message"]["media_attachments"] == []
    assert result.json()["assistant_message"]["content"] == provider.text
    assert len(provider.calls) == 1


def test_unknown_or_foreign_sticker_id_cannot_be_sent(setup):
    client, provider, app, headers, identifier, _ = setup
    foreign = app.state.media.ingest("sticker", image_bytes("blue"), metadata(category="cat", owner="b" * 64))
    provider.choice = foreign["id"]
    result = client.post(f"/api/conversations/{identifier}/messages", headers=headers, json={"content": "今天终于忙完了。"})
    assert result.status_code == 200
    assert result.json()["assistant_message"]["media_attachments"] == []
    assert foreign["id"] not in provider.calls[0][0]["content"]
    assert "ZHIYU_STICKER" not in result.json()["assistant_message"]["content"]


def test_proactive_reply_uses_same_semantic_choice_and_remains_idempotent(setup):
    client, provider, _, headers, identifier, sticker = setup
    path = f"/api/conversations/{identifier}"
    provider.choice = sticker["id"]
    client.put(path + "/proactive-preferences", headers=headers, json={"enabled": True})
    first = client.post(path + "/proactive/check", headers=headers, json={"request_id": "sticker-one"})
    assert first.status_code == 200, first.text
    assert first.json()["emitted"]
    assert [item["id"] for item in first.json()["message"]["media_attachments"]] == [sticker["id"]]
    assert first.json()["message"]["content"] == provider.text
    replay = client.post(path + "/proactive/check", headers=headers, json={"request_id": "sticker-one"})
    assert replay.json()["message"]["id"] == first.json()["message"]["id"]
    assert len(provider.calls) == 1
    history = client.get(path + "/messages", headers=headers).json()
    assert len(history) == 1 and history[0]["origin"] == "proactive"
