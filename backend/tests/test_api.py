from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.providers import ProviderError


class FakeProvider:
    def __init__(self):
        self.fail = False
        self.calls = []

    async def validate(self, provider_id, key, model):
        if key == "invalid-key":
            raise ProviderError(401, "API Key 无效")

    async def complete(self, provider_id, key, model, messages, max_tokens=2048):
        self.calls.append(messages)
        if self.fail:
            raise ProviderError(429, "服务商限流，请稍后重试")
        return "知识库说明：支持中文检索。[1]"


@pytest.fixture
def setup(tmp_path):
    provider = FakeProvider()
    app = create_app(Settings(data_dir=tmp_path, allow_demo=True), provider_client=provider)
    with TestClient(app) as client:
        yield client, provider, app


def login(client, key="test-key-a", provider="deepseek"):
    response = client.post("/api/auth/login", json={
        "provider_id": provider, "api_key": key, "model": "test-model",
    })
    assert response.status_code == 200, response.text
    result = response.json()
    assert "api_key" not in result
    return {"Authorization": "Bearer " + result["token"]}


def conversation(client, headers):
    response = client.post("/api/conversations", headers=headers, json={"use_rag": True})
    assert response.status_code == 201
    return response.json()["id"]


def test_health_and_catalog_are_public(setup):
    client, _, _ = setup
    assert client.get("/health").json()["status"] == "ok"
    catalog = client.get("/api/providers").json()
    assert any(p["id"] == "deepseek" for p in catalog)
    assert any(p["id"] == "demo" for p in catalog)
    assert client.get("/api/conversations").status_code == 401


def test_login_validation_and_logout(setup):
    client, _, _ = setup
    assert client.post("/api/auth/login", json={
        "provider_id": "deepseek", "api_key": "invalid-key", "model": "test",
    }).status_code == 401
    assert client.post("/api/auth/login", json={
        "provider_id": "unknown", "api_key": "some-key", "model": "test",
    }).status_code == 400
    assert client.post("/api/auth/login", json={
        "provider_id": "deepseek", "api_key": "   ", "model": "test",
    }).status_code == 422
    headers = login(client)
    assert client.get("/api/auth/me", headers=headers).json()["provider_id"] == "deepseek"
    assert client.delete("/api/auth/session", headers=headers).status_code == 204
    assert client.get("/api/conversations", headers=headers).status_code == 401


def test_demo_cannot_be_used_without_explicit_setting(tmp_path):
    with TestClient(create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())) as client:
        assert not any(p["id"] == "demo" for p in client.get("/api/providers").json())
        assert client.post("/api/auth/login", json={
            "provider_id": "demo", "api_key": "demo-key", "model": "demo",
        }).status_code == 400


def test_conversation_crud_and_isolation(setup):
    client, _, _ = setup
    owner = login(client)
    other = login(client, "test-key-b")
    identifier = conversation(client, owner)
    assert len(client.get("/api/conversations", headers=owner).json()) == 1
    assert client.get("/api/conversations", headers=other).json() == []
    for method, suffix, payload in [
        ("get", "/messages", None), ("post", "/messages", {"content": "hi"}),
        ("patch", "", {"title": "stolen"}), ("delete", "", None),
    ]:
        kwargs = {"headers": other}
        if payload:
            kwargs["json"] = payload
        assert getattr(client, method)(f"/api/conversations/{identifier}{suffix}", **kwargs).status_code == 404
    updated = client.patch(f"/api/conversations/{identifier}", headers=owner,
                           json={"title": "我的知识", "use_rag": False})
    assert updated.json()["title"] == "我的知识"
    assert updated.json()["use_rag"] is False
    assert client.delete(f"/api/conversations/{identifier}", headers=owner).status_code == 204
    assert client.get(f"/api/conversations/{identifier}/messages", headers=owner).status_code == 404


def test_chat_history_sources_and_provider_failure_are_atomic(setup):
    client, provider, _ = setup
    headers = login(client)
    identifier = conversation(client, headers)
    upload = client.post("/api/documents", headers=headers,
                         files={"file": ("guide.txt", "知语知识库支持中文检索。".encode())})
    assert upload.status_code == 201, upload.text
    response = client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                           json={"content": "知识库支持什么检索？"})
    assert response.status_code == 200, response.text
    assert response.json()["assistant_message"]["sources"][0]["name"] == "guide.txt"
    assert "知语知识库" in provider.calls[0][0]["content"]
    messages = client.get(f"/api/conversations/{identifier}/messages", headers=headers).json()
    assert [m["role"] for m in messages] == ["user", "assistant"]
    provider.fail = True
    response = client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                           json={"content": "第二个问题"})
    assert response.status_code == 429
    assert len(client.get(f"/api/conversations/{identifier}/messages", headers=headers).json()) == 2


def test_documents_size_delete_and_search_are_isolated(setup):
    client, _, _ = setup
    headers = login(client)
    other = login(client, "test-key-b")
    response = client.post("/api/documents", headers=headers,
                           files={"file": ("note.md", "中文知识库检索与文档来源".encode())})
    assert response.status_code == 201
    identifier = response.json()["id"]
    assert client.get("/api/documents", headers=other).json() == []
    assert client.post("/api/knowledge/search", headers=other,
                       json={"query": "中文"}).json() == []
    assert client.delete(f"/api/documents/{identifier}", headers=other).status_code == 404
    assert client.post("/api/documents", headers=headers,
                       files={"file": ("large.txt", b"a" * (5 * 1024 * 1024 + 1))}).status_code == 413
    assert client.post("/api/documents", headers=headers,
                       files={"file": ("run.exe", b"not text")}).status_code == 400
    assert client.delete(f"/api/documents/{identifier}", headers=headers).status_code == 204
    assert client.get("/api/documents", headers=headers).json() == []


def test_same_key_recovers_persisted_history_without_storing_key(tmp_path):
    settings = Settings(data_dir=tmp_path)
    with TestClient(create_app(settings, provider_client=FakeProvider())) as client:
        headers = login(client, "secret-never-write-key")
        identifier = conversation(client, headers)
    with TestClient(create_app(settings, provider_client=FakeProvider())) as client:
        assert client.get("/api/conversations", headers=headers).status_code == 401
        restored = login(client, "secret-never-write-key")
        assert client.get("/api/conversations", headers=restored).json()[0]["id"] == identifier
    assert all(b"secret-never-write-key" not in f.read_bytes() for f in Path(tmp_path).iterdir() if f.is_file())


def test_invalid_inputs_and_disabled_rag(setup):
    client, provider, _ = setup
    headers = login(client)
    assert client.post("/api/conversations", headers=headers,
                       json={"model": "bad\nmodel"}).status_code == 422
    identifier = conversation(client, headers)
    assert client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                       json={"content": "  "}).status_code == 422
    assert client.patch(f"/api/conversations/{identifier}", headers=headers,
                        json={"title": None}).status_code == 422
    client.patch(f"/api/conversations/{identifier}", headers=headers, json={"use_rag": False})
    response = client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                           json={"content": "hello"})
    assert response.status_code == 200
    assert response.json()["assistant_message"]["sources"] == []
    assert provider.calls[-1][-1] == {"role": "user", "content": "hello"}
