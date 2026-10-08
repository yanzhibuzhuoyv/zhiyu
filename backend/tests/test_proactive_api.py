from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.providers import ProviderError


class Provider:
    calls = 0
    fail = False
    async def validate(self, *args):
        return None

    async def complete(self, *args):
        self.calls += 1
        if self.fail:
            raise ProviderError(429, "稍后再试")
        return "今天也想和你聊聊。你可以忙完再回来。"


@pytest.fixture
def setup(tmp_path):
    provider = Provider()
    app = create_app(Settings(data_dir=tmp_path), provider_client=provider)
    app.state.contact_clock = lambda: datetime.now(UTC).replace(hour=4)
    with TestClient(app) as client:
        headers = {"Authorization": "Bearer " + client.post("/api/auth/login", json={
            "provider_id": "deepseek", "api_key": "isolated-test-key", "model": "test-model"}).json()["token"]}
        identifier = client.post("/api/conversations", headers=headers, json={}).json()["id"]
        yield client, provider, app, headers, identifier


def test_disabled_and_other_owner_cannot_generate(setup):
    client, provider, _, headers, identifier = setup
    path = f"/api/conversations/{identifier}"
    assert client.post(path + "/proactive/check", headers=headers, json={"request_id": "one"}).json()["reason"] == "disabled"
    assert provider.calls == 0
    assert client.put(path + "/proactive-preferences", headers=headers, json={"enabled": 1}).status_code == 422
    assert client.get(path + "/proactive-preferences").status_code == 401
    other = {"Authorization": "Bearer " + client.post("/api/auth/login", json={
        "provider_id": "deepseek", "api_key": "another-isolated-key", "model": "test-model"}).json()["token"]}
    for suffix in ("/proactive-preferences", "/proactive/check"):
        request = client.get(path + suffix, headers=other) if suffix.endswith("preferences") else client.post(
            path + suffix, headers=other, json={"request_id": "one"})
        assert request.status_code == 404
    assert provider.calls == 0


def test_enabled_message_is_saved_once_without_synthetic_user_and_recovers(setup):
    client, provider, app, headers, identifier = setup
    path = f"/api/conversations/{identifier}"
    client.put(path + "/proactive-preferences", headers=headers, json={"enabled": True})
    first = client.post(path + "/proactive/check", headers=headers, json={"request_id": "one"})
    assert first.status_code == 200, first.text
    assert first.json()["emitted"]
    second = client.post(path + "/proactive/check", headers=headers, json={"request_id": "one"})
    assert second.json()["message"]["id"] == first.json()["message"]["id"]
    assert provider.calls == 1
    history = client.get(path + "/messages", headers=headers).json()
    assert len(history) == 1 and history[0]["role"] == "assistant" and history[0]["origin"] == "proactive"
    with app.state.proactive.connection() as db:
        db.execute("UPDATE events SET status='pending',message_id=NULL")
    recovered = client.post(path + "/proactive/check", headers=headers, json={"request_id": "one"})
    assert recovered.json()["message"]["id"] == first.json()["message"]["id"]
    assert provider.calls == 1


def test_failure_is_not_silently_retried_or_saved(setup):
    client, provider, _, headers, identifier = setup
    path = f"/api/conversations/{identifier}"
    client.put(path + "/proactive-preferences", headers=headers, json={"enabled": True})
    provider.fail = True
    assert client.post(path + "/proactive/check", headers=headers, json={"request_id": "one"}).status_code == 429
    retry = client.post(path + "/proactive/check", headers=headers, json={"request_id": "one"})
    assert retry.json()["reason"] == "failed" and not retry.json()["emitted"]
    assert provider.calls == 1
    assert client.get(path + "/messages", headers=headers).json() == []
    provider.fail = False
    restored = client.post(path + "/proactive/check", headers=headers, json={"request_id": "second"})
    assert restored.json()["emitted"]
    assert provider.calls == 2


def test_interrupted_request_is_not_replayed_and_does_not_lock_all_future_days(setup):
    client, provider, app, headers, identifier = setup
    path = f"/api/conversations/{identifier}"
    client.put(path + "/proactive-preferences", headers=headers, json={"enabled": True})
    session = app.state.sessions.get(headers["Authorization"].split()[1])
    yesterday = app.state.contact_clock() - timedelta(days=1)
    app.state.proactive.reserve(session.owner, identifier, "interrupted", yesterday.date().isoformat(), yesterday)
    retry = client.post(path + "/proactive/check", headers=headers, json={"request_id": "interrupted"})
    assert retry.json()["reason"] == "unknown" and provider.calls == 0
    fresh = client.post(path + "/proactive/check", headers=headers, json={"request_id": "new-day"})
    assert fresh.json()["emitted"] and provider.calls == 1
