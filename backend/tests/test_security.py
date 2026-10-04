from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider, login

from app.auth import LoginLimiter, Session, Sessions
from app.config import Settings
from app.main import create_app


def test_session_expiry_hashing_and_stable_identity(tmp_path):
    store = Sessions(tmp_path)
    token, session = store.issue("deepseek", "private-key", "test-model")
    assert token not in store._items
    assert "private-key" not in repr(session)
    assert store.get(token) is session
    second = Sessions(tmp_path)
    _, restored = second.issue("deepseek", "private-key", "another-model")
    assert restored.owner == session.owner
    _, another = second.issue("kimi", "private-key", "test-model")
    assert another.owner != session.owner
    store._items[store._digest(token)] = Session(session.owner, session.provider_id, session.model,
                                                datetime.now(UTC) - timedelta(seconds=1), session.api_key)
    assert store.get(token) is None


def test_invalid_identity_secret_fails_closed(tmp_path):
    (tmp_path / "identity.secret").write_bytes(b"broken")
    with pytest.raises(ValueError, match="损坏"):
        Sessions(tmp_path)


def test_login_rate_limiter(monkeypatch):
    now = [100.0]
    monkeypatch.setattr("app.auth.time.monotonic", lambda: now[0])
    limiter = LoginLimiter(limit=2, window=60)
    assert limiter.allow("one")
    assert limiter.allow("one")
    assert not limiter.allow("one")
    assert limiter.allow("two")
    now[0] += 61
    assert limiter.allow("one")


def test_error_response_does_not_echo_secret(tmp_path):
    with TestClient(create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())) as client:
        secret = "secret-key-never-echo\nBAD"
        result = client.post("/api/auth/login", json={"provider_id": "deepseek", "api_key": secret, "model": "test"})
        assert result.status_code == 422
        assert "secret-key-never-echo" not in result.text
        assert result.headers["cache-control"] == "no-store"
        assert client.get("/api/conversations", headers={"Authorization": "Bearer " + "a" * 300}).status_code == 401
        for _ in range(9):
            response = client.post("/api/auth/login", json={"provider_id": "unknown", "api_key": "good-key", "model": "test"})
            assert response.status_code == 400
        assert client.post("/api/auth/login", json={"provider_id": "unknown", "api_key": "good-key", "model": "test"}).status_code == 400
        assert client.post("/api/auth/login", json={"provider_id": "unknown", "api_key": "good-key", "model": "test"}).status_code == 429


def test_request_size_limit_and_config(tmp_path, monkeypatch):
    monkeypatch.setenv("ZHIYU_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("ZHIYU_DEMO", "true")
    assert Settings.from_env().allow_demo
    with pytest.raises(ValueError):
        Settings(session_hours=0)
    with TestClient(create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())) as client:
        result = client.post("/api/auth/login", content=b"a" * (6 * 1024 * 1024 + 1))
        assert result.status_code == 413
        assert client.post("/api/auth/login", content="{}", headers={"Content-Length": "broken"}).status_code == 400
        headers = login(client)
        result = client.post("/api/knowledge/search", headers=headers, json={"query": "hi", "top_k": 99})
        assert result.status_code == 422


def test_chunked_request_is_bounded_before_json_parsing(tmp_path):
    with TestClient(create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())) as client:
        result = client.post("/api/auth/login", content=iter([b"a" * (6 * 1024 * 1024 + 1)]))
        assert result.status_code == 413


def test_unauthorized_multipart_is_rejected_before_parsing(tmp_path, monkeypatch):
    parsed = []
    from starlette.formparsers import MultiPartParser

    original = MultiPartParser.parse

    async def recording(self):
        parsed.append(True)
        return await original(self)

    monkeypatch.setattr(MultiPartParser, "parse", recording)
    with TestClient(create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())) as client:
        result = client.post("/api/documents", files={"file": ("note.txt", b"test")})
        assert result.status_code == 401
        assert parsed == []
