"""A provider rejecting a Key is distinct from a stale app session."""

from fastapi.testclient import TestClient
from test_api import FakeProvider, conversation, login

from app.config import Settings
from app.main import create_app
from app.providers import ProviderError


class RejectingProvider(FakeProvider):
    reject_login = False
    reject_reply = False

    async def validate(self, *args):
        if self.reject_login:
            raise ProviderError(401, "AI 服务商拒绝了 API Key，请检查是否仍有效")
        return await super().validate(*args)

    async def complete(self, *args):
        if self.reject_reply:
            raise ProviderError(401, "AI 服务商拒绝了 API Key，请检查是否仍有效")
        return await super().complete(*args)


def test_missing_or_expired_session_has_specific_code(tmp_path):
    app = create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())
    with TestClient(app) as client:
        for headers in ({}, {"Authorization": "Bearer stale-token"}):
            response = client.get("/api/conversations", headers=headers)
            assert response.status_code == 401
            assert response.headers["x-zhiyu-error-code"] == "session_expired"
            assert response.headers["www-authenticate"] == "Bearer"


def test_provider_rejection_during_login_is_not_session_expiry(tmp_path):
    provider = RejectingProvider()
    provider.reject_login = True
    app = create_app(Settings(data_dir=tmp_path), provider_client=provider)
    with TestClient(app) as client:
        response = client.post("/api/auth/login", json={"provider_id": "deepseek", "api_key": "test-key"})
        assert response.status_code == 401
        assert response.headers["x-zhiyu-error-code"] == "provider_auth_failed"
        assert "test-key" not in response.text


def test_provider_rejection_preserves_chat_and_app_session(tmp_path):
    provider = RejectingProvider()
    app = create_app(Settings(data_dir=tmp_path), provider_client=provider)
    with TestClient(app) as client:
        headers = login(client)
        identifier = conversation(client, headers)
        url = f"/api/conversations/{identifier}"
        first = client.post(url + "/messages", headers=headers, json={"content": "原来的一句话"})
        assert first.status_code == 200
        saved = client.get(url + "/history", headers=headers).json()
        provider.reject_reply = True
        response = client.post(url + "/messages", headers=headers, json={"content": "这个请求被拒绝"})
        assert response.status_code == 401
        assert response.headers["x-zhiyu-error-code"] == "provider_auth_failed"
        assert client.get(url + "/history", headers=headers).json() == saved
        assert client.get("/api/auth/me", headers=headers).status_code == 200
