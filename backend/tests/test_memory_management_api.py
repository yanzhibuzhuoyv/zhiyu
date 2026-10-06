from dataclasses import replace

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


def fixture(tmp_path):
    app = create_app(Settings(data_dir=tmp_path, allow_demo=True, memory_annotation_mode="literal"))
    app.state.chat.auto_annotations = True
    client = TestClient(app)
    login = client.post("/api/auth/login", json={"provider_id": "demo", "api_key": "memory-api-synthetic"})
    headers = {"Authorization": "Bearer " + login.json()["token"]}
    conversation = client.post("/api/conversations", headers=headers, json={"use_rag": False}).json()
    return app, client, headers, conversation["id"]


def saved(client, headers, identifier, text="我想读星河笔记。"):
    response = client.post(f"/api/conversations/{identifier}/messages", headers=headers, json={"content": text})
    assert response.status_code == 200
    return response.json()["user_message"]


def test_manual_tier_changes_metadata_preserving_all_original_history(tmp_path):
    _, client, headers, identifier = fixture(tmp_path)
    original = saved(client, headers, identifier)
    endpoint = f"/api/conversations/{identifier}/memory/messages/{original['id']}"
    before = client.get(endpoint, headers=headers)
    assert before.status_code == 200
    assert before.json()["tier"] == "mid"
    assert before.json()["revision"] == 0
    updated = client.put(endpoint, headers=headers, json={"tier": "permanent", "expected_revision": 0})
    assert updated.status_code == 200
    assert updated.json()["tier"] == updated.json()["manual_tier"] == "permanent"
    assert updated.json()["revision"] == 1
    assert client.get(f"/api/conversations/{identifier}/messages", headers=headers).json()[0] == original
    reset = client.put(endpoint, headers=headers, json={"tier": "auto", "expected_revision": 1})
    assert reset.status_code == 200
    assert reset.json()["tier"] == "mid" and reset.json()["manual_tier"] is None


def test_stale_revision_and_protected_archive_do_not_mutate_source_or_metadata(tmp_path):
    _, client, headers, identifier = fixture(tmp_path)
    original = saved(client, headers, identifier)
    endpoint = f"/api/conversations/{identifier}/memory/messages/{original['id']}"
    kept = client.put(endpoint, headers=headers, json={"tier": "permanent", "expected_revision": 0}).json()
    stale = client.put(endpoint, headers=headers, json={"tier": "mid", "expected_revision": 0})
    assert stale.status_code == 409
    archive = client.put(endpoint, headers=headers, json={"index_state": "cold", "expected_revision": 1})
    assert archive.status_code == 409
    assert client.get(endpoint, headers=headers).json() == kept


def test_manual_cold_search_restart_restore_keeps_body_and_ownership(tmp_path):
    _, client, headers, identifier = fixture(tmp_path)
    original = saved(client, headers, identifier, "长原文星河书签" * 500 + "尾部暮色暗号")
    endpoint = f"/api/conversations/{identifier}/memory/messages/{original['id']}"
    cooled = client.put(endpoint, headers=headers, json={"index_state": "cold", "expected_revision": 0})
    assert cooled.status_code == 200 and cooled.json()["index_state"] == "cold"
    search = client.post(f"/api/conversations/{identifier}/memory/search", headers=headers,
                         json={"query": "暮色暗号"})
    assert search.status_code == 200 and search.json()["complete"]
    assert any(row["message_id"] == original["id"] and "暮色暗号" in row["text"]
               for row in search.json()["messages"])
    restored_app = create_app(Settings(data_dir=tmp_path, allow_demo=True))
    restored = TestClient(restored_app)
    login = restored.post("/api/auth/login", json={"provider_id": "demo", "api_key": "memory-api-synthetic"})
    fresh_headers = {"Authorization": "Bearer " + login.json()["token"]}
    state = restored.get(endpoint, headers=fresh_headers).json()
    assert state["index_state"] == "cold"
    active = restored.put(endpoint, headers=fresh_headers,
                          json={"index_state": "active", "expected_revision": state["revision"]})
    assert active.status_code == 200 and active.json()["index_state"] == "active"
    assert restored.get(f"/api/conversations/{identifier}/messages", headers=fresh_headers).json()[0] == original


def test_management_is_owner_scoped_and_pagination_cursors_are_checked(tmp_path):
    _, client, headers, identifier = fixture(tmp_path)
    originals = [saved(client, headers, identifier, f"第{index}条独立原文") for index in range(4)]
    first = client.get(f"/api/conversations/{identifier}/memory?limit=3", headers=headers)
    assert first.status_code == 200 and first.json()["has_more"]
    second = client.get(f"/api/conversations/{identifier}/memory", headers=headers,
                        params={"limit": 3, "before": first.json()["next_before"]})
    assert second.status_code == 200
    assert not set(row["message_id"] for row in first.json()["messages"]) & set(
        row["message_id"] for row in second.json()["messages"])
    invalid = client.get(f"/api/conversations/{identifier}/memory?before=unknown", headers=headers)
    assert invalid.status_code == 404
    login = client.post("/api/auth/login", json={"provider_id": "demo", "api_key": "foreign-memory-api"})
    foreign = {"Authorization": "Bearer " + login.json()["token"]}
    endpoint = f"/api/conversations/{identifier}/memory/messages/{originals[0]['id']}"
    assert client.get(endpoint, headers=foreign).status_code == 404
    assert client.put(endpoint, headers=foreign,
                      json={"tier": "permanent", "expected_revision": 0}).status_code == 404


@pytest.mark.parametrize("body", [{"expected_revision": True, "tier": "mid"},
                                 {"expected_revision": 0, "tier": "deleted"},
                                 {"expected_revision": 0}, {"tier": "long"},
                                 {"expected_revision": 0, "tier": "mid", "role": "user"}])
def test_management_rejects_invalid_input_without_original_write(tmp_path, body):
    _, client, headers, identifier = fixture(tmp_path)
    original = saved(client, headers, identifier)
    response = client.put(f"/api/conversations/{identifier}/memory/messages/{original['id']}",
                          headers=headers, json=body)
    assert response.status_code == 422
    assert client.get(f"/api/conversations/{identifier}/messages", headers=headers).json()[0] == original


def test_confirm_independent_episode_requires_explicit_user_action(tmp_path):
    _, client, headers, identifier = fixture(tmp_path)
    original = saved(client, headers, identifier)
    endpoint = f"/api/conversations/{identifier}/memory/messages/{original['id']}"
    assert client.get(endpoint, headers=headers).json()["can_confirm_independent"] is True
    result = client.put(endpoint, headers=headers, json={"confirm_independent": True, "expected_revision": 0})
    assert result.status_code == 200 and result.json()["can_confirm_independent"] is False


def test_opt_in_literal_annotation_setting_never_adds_paid_model_calls(tmp_path):
    configured = replace(Settings(data_dir=tmp_path, allow_demo=True), auto_memory_annotations=True)
    app = create_app(configured)
    assert app.state.chat.auto_annotations is True
