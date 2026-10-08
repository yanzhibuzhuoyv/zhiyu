import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider, login
from test_media_assets import image_bytes, metadata

from app.config import Settings
from app.main import create_app


@pytest.fixture
def media_app(tmp_path):
    app = create_app(Settings(data_dir=tmp_path, media_dir=tmp_path / "external-media"),
                     provider_client=FakeProvider())
    with TestClient(app) as client:
        headers = login(client)
        other = login(client, "test-key-b")
        response = client.post("/api/conversations", headers=headers, json={
            "use_rag": False, "companion_profile": {"mode": "preset", "style": "steady"},
        })
        yield client, app, headers, other, response.json()["id"]


def test_visual_routes_enforce_owner_and_preset_survives(media_app):
    client, app, headers, other, identifier = media_app
    base = f"/api/conversations/{identifier}/visual"
    assert client.get(base).status_code == 401
    assert client.get(base, headers=other).status_code == 404
    result = client.put(base, headers=headers, json={"mode": "import", "description": "自创角色", "hair": "long"})
    assert result.status_code == 200, result.text
    assert result.json()["visual_version"] == 1
    assert client.put(base, headers=headers, json={"mode": "none", "style": "playful"}).status_code == 422
    profile = app.state.chat.list_conversations(next(iter(app.state.sessions._items.values())).owner)[0]
    assert profile["companion_profile"]["style"] == "steady" and profile["profile_locked"]


def test_uploaded_oc_reference_is_private_verified_and_key_free(media_app):
    client, app, headers, other, identifier = media_app
    base = f"/api/conversations/{identifier}/visual"
    result = client.post(base + "/reference", headers=headers,
                         files={"file": ("../../escape.png", image_bytes(), "image/png")})
    assert result.status_code == 200, result.text
    profile = result.json()
    assert profile["mode"] == "import" and profile["reference_sha256"]
    assert profile["generation"]["remaining"] == 2
    url = profile["reference_url"]
    assert client.get(url, headers=other).status_code == 404
    assert client.get(url).status_code == 401
    image = client.get(url, headers=headers)
    assert image.status_code == 200 and image.headers["content-type"] == "image/png"
    assert image.headers["cache-control"] == "no-store"
    assert client.post(base + "/reference", headers=headers,
                       files={"file": ("fake.png", b"<script>alert(1)</script>", "image/png")}).status_code == 400
    assert "test-key-a" not in result.text


def test_generation_unconfigured_does_not_consume_owner_chances(media_app):
    client, _, headers, other, identifier = media_app
    base = f"/api/conversations/{identifier}/visual"
    assert client.post(base + "/generate", headers=other,
                       json={"request_id": "a", "description": "自创角色"}).status_code == 404
    result = client.post(base + "/generate", headers=headers,
                         json={"request_id": "a", "description": "自创角色"})
    assert result.status_code == 503
    assert result.headers["x-zhiyu-error-code"] == "generation_unconfigured"
    assert client.get(base, headers=headers).json()["generation"]["remaining"] == 2


def test_generation_failure_is_terminal_and_pending_replays_do_not_call_provider(tmp_path):
    calls = []

    async def failing(description, **identity):
        calls.append(description)
        raise RuntimeError("upstream private text")

    app = create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider(), visual_generator=failing)
    with TestClient(app) as client:
        headers = login(client)
        identifier = client.post("/api/conversations", headers=headers, json={"use_rag": False}).json()["id"]
        url = f"/api/conversations/{identifier}/visual/generate"
        body = {"request_id": "terminal-failure", "description": "原创角色"}
        failure = client.post(url, headers=headers, json=body)
        assert failure.status_code == 502 and failure.headers["x-zhiyu-error-code"] == "generation_failed"
        replay = client.post(url, headers=headers, json=body)
        assert replay.status_code == 409 and replay.headers["x-zhiyu-error-code"] == "generation_failed"
        assert calls == ["原创角色"]
        session = next(iter(app.state.sessions._items.values()))
        app.state.visual.reserve(session.owner, identifier, "pending-id", "仍在生成")
        pending = client.post(url, headers=headers, json={"request_id": "pending-id", "description": "仍在生成"})
        assert pending.status_code == 409 and pending.headers["x-zhiyu-error-code"] == "generation_pending"
        assert calls == ["原创角色"]


def test_attachment_is_persisted_history_compatible_and_not_a_memory(media_app):
    client, app, headers, _, identifier = media_app
    photo = app.state.media.ingest("image", image_bytes(), metadata(category="cat", tags=["cat"]))
    response = client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                           json={"content": "发一张小猫照片"})
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["user_message"]["media_attachments"] == []
    attachments = payload["assistant_message"]["media_attachments"]
    assert len(attachments) == 1 and attachments[0]["id"] == photo["id"]
    assert attachments[0]["day_id"] and attachments[0]["source_label"] == "测试作者授权"
    history = client.get(f"/api/conversations/{identifier}/history", headers=headers).json()["messages"]
    assert history[-1]["media_attachments"] == attachments
    assert "测试作者授权" not in payload["assistant_message"]["content"]
    assert client.get(f"/api/conversations/{identifier}/daily-scene", headers=headers).json()["fictional"] is True


def test_plain_message_with_empty_library_has_no_fake_image(media_app):
    client, _, headers, _, identifier = media_app
    result = client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                         json={"content": "给我看看今天的自拍"})
    assert result.status_code == 200
    assert result.json()["assistant_message"]["media_attachments"] == []


def test_old_database_migration_keeps_originals_and_origin_defaults(tmp_path):
    import sqlite3
    from contextlib import closing

    from app.database import ChatDatabase

    first = ChatDatabase(tmp_path)
    conversation = first.create_conversation("a", "original", "test", False)
    first.save_turn("a", conversation["id"], "完整用户原文", "完整AI原文", [])
    with closing(sqlite3.connect(first.path)) as db, db:
        db.execute("ALTER TABLE messages DROP COLUMN media_attachments")
        db.execute("ALTER TABLE messages DROP COLUMN origin")
    restored = ChatDatabase(tmp_path)
    messages = restored.list_messages("a", conversation["id"])
    assert [row["content"] for row in messages] == ["完整用户原文", "完整AI原文"]
    assert all(row["origin"] == "reply" and row["media_attachments"] == [] for row in messages)
