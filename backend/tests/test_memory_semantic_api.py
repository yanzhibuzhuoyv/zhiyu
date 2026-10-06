import json

import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider, login

from app.config import Settings
from app.main import create_app
from app.providers import ProviderError


def claim(quote, kind="profile", topic="user:birthday", **extra):
    return {"quote": quote, "kind": kind, "subject": "user", "polarity": "affirm",
            "topic_key": topic, "confidence": 0.96, "explicit_keep": False,
            "correction": False, "promise_state": None, "date_basis": None,
            "event_date": None, **extra}


class SemanticProvider(FakeProvider):
    def __init__(self, payload):
        super().__init__()
        self.payload = payload
        self.identities = []

    async def complete(self, provider_id, key, model, messages, max_tokens=2048):
        self.calls.append(messages)
        self.identities.append((provider_id, key, model))
        if len(self.calls) % 2 == 1:
            return "我愿意认真听你说。"
        if isinstance(self.payload, Exception):
            raise self.payload
        return self.payload if isinstance(self.payload, str) else json.dumps(self.payload, ensure_ascii=False)


def setup(tmp_path, payload, *, enabled=True):
    provider = SemanticProvider(payload)
    app = create_app(Settings(data_dir=tmp_path, auto_memory_annotations=enabled,
                              memory_annotation_mode="semantic", allow_demo=True), provider_client=provider)
    client = TestClient(app)
    headers = login(client)
    cid = client.post("/api/conversations", headers=headers, json={"use_rag": False}).json()["id"]
    return client, headers, cid, provider, app


def test_semantic_paraphrase_becomes_source_tag_with_same_selected_identity_and_raw_text(tmp_path):
    text = "今天聊得好开心。十月六号是我生日，以后我们一起过吧。"
    quote = "十月六号是我生日"
    client, headers, cid, provider, app = setup(tmp_path, {"user": [claim(quote)], "assistant": []})
    response = client.post(f"/api/conversations/{cid}/messages", headers=headers, json={"content": text})
    assert response.status_code == 200
    assert response.json()["user_message"]["content"] == text
    assert response.json()["memory_recognition"] == {"mode": "semantic", "status": "ok"}
    assert len(provider.calls) == 2 and provider.identities[0] == provider.identities[1]
    mid = response.json()["user_message"]["id"]
    meta = client.get(f"/api/conversations/{cid}/memory/messages/{mid}", headers=headers).json()
    assert meta["tier"] == "permanent" and meta["recognition_mode"] == "semantic"
    item = meta["annotations"][0]
    assert text[item["span_start"]:item["span_end"]] == quote and item["date_basis"] is None
    assert client.get(f"/api/conversations/{cid}/messages", headers=headers).json()[0]["content"] == text
    assert app.state.chat.annotation_mode == "semantic"


@pytest.mark.parametrize("payload", ["not JSON", "", ProviderError(429, "PRIVATE-PROVIDER-DETAIL")])
def test_failed_recognition_keeps_successful_chat_and_reports_safe_status(tmp_path, payload):
    client, headers, cid, provider, _ = setup(tmp_path, payload)
    response = client.post(f"/api/conversations/{cid}/messages", headers=headers,
                           json={"content": "不同说法的原话仍然保存"})
    assert response.status_code == 200 and "PRIVATE" not in response.text
    assert response.json()["memory_recognition"]["status"] == "failed"
    meta = client.get(f"/api/conversations/{cid}/memory/messages/{response.json()['user_message']['id']}", headers=headers).json()
    assert meta["tier"] == "mid" and not meta["annotations"] and meta["recognition_status"] == "failed"
    assert len(provider.calls) == 2


def test_semantic_disabled_does_not_add_call(tmp_path):
    client, headers, cid, provider, _ = setup(tmp_path, {}, enabled=False)
    response = client.post(f"/api/conversations/{cid}/messages", headers=headers, json={"content": "普通原话"})
    assert response.status_code == 200 and response.json()["memory_recognition"]["status"] == "disabled"
    assert len(provider.calls) == 1


def test_demo_never_pretends_rule_matching_is_semantic_model(tmp_path):
    client, _, _, provider, _ = setup(tmp_path, {})
    headers = login(client, key="demo-semantic-synthetic", provider="demo")
    cid = client.post("/api/conversations", headers=headers, json={"use_rag": False}).json()["id"]
    response = client.post(f"/api/conversations/{cid}/messages", headers=headers,
                           json={"content": "我的生日是3月6日"})
    assert response.status_code == 200 and response.json()["memory_recognition"]["status"] == "unavailable"
    assert len(provider.calls) == 1


def test_failed_semantic_turn_does_not_relabel_or_mutate_existing_source(tmp_path):
    client, headers, cid, provider, _ = setup(tmp_path, {"user": [claim("十月六号是我生日")], "assistant": []})
    original = client.post(f"/api/conversations/{cid}/messages", headers=headers,
                           json={"content": "十月六号是我生日"}).json()["user_message"]
    url = f"/api/conversations/{cid}/memory/messages/{original['id']}"
    before = client.get(url, headers=headers).json()
    provider.payload = "invalid-format"
    response = client.post(f"/api/conversations/{cid}/messages", headers=headers, json={"content": "其实我说错了"})
    assert response.status_code == 200
    assert client.get(url, headers=headers).json() == before


def test_semantic_mode_is_validated_and_environment_explicit(monkeypatch, tmp_path):
    monkeypatch.setenv("ZHIYU_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("ZHIYU_AUTO_MEMORY_ANNOTATIONS", "true")
    monkeypatch.setenv("ZHIYU_MEMORY_ANNOTATION_MODE", "semantic")
    settings = Settings.from_env()
    assert settings.auto_memory_annotations and settings.memory_annotation_mode == "semantic"
    with pytest.raises(ValueError):
        Settings(data_dir=tmp_path, memory_annotation_mode="guessed")


def test_recognition_storage_failure_returns_safe_503_and_rolls_back_originals(tmp_path):
    client, headers, cid, _, app = setup(tmp_path, {"user": [claim("十月六号是我生日")], "assistant": []})
    with app.state.chat.connection() as db:
        db.execute("""CREATE TRIGGER semantic_recognition_fault BEFORE INSERT ON memory_recognition
            BEGIN SELECT RAISE(ABORT,'PRIVATE-KEY-STORAGE'); END""")
    response = client.post(f"/api/conversations/{cid}/messages", headers=headers,
                           json={"content": "十月六号是我生日"})
    assert response.status_code == 503 and "PRIVATE" not in response.text
    with app.state.chat.connection() as db:
        assert db.execute("SELECT count(*) FROM messages").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM memory_fragments").fetchone()[0] == 0


def test_multiple_independent_spans_have_separate_ids_and_survive_app_reconstruction(tmp_path):
    text = "十月六号是我生日。闲下来的时候我总爱读诗。"
    payload = {"user": [claim("十月六号是我生日"),
                        claim("闲下来的时候我总爱读诗", "preference", "like:poetry")], "assistant": []}
    client, headers, cid, _, _ = setup(tmp_path, payload)
    response = client.post(f"/api/conversations/{cid}/messages", headers=headers, json={"content": text})
    assert response.status_code == 200
    mid = response.json()["user_message"]["id"]
    before = client.get(f"/api/conversations/{cid}/memory/messages/{mid}", headers=headers).json()
    assert len(before["annotations"]) == 2 and len({a["id"] for a in before["annotations"]}) == 2
    app = create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())
    rebuilt = TestClient(app)
    fresh = login(rebuilt)
    assert rebuilt.get(f"/api/conversations/{cid}/memory/messages/{mid}", headers=fresh).json() == before


def test_semantic_correction_near_long_source_tail_is_recalled_at_its_exact_offset(tmp_path):
    from app.conversation_memory import conversation_context_result

    client, headers, cid, provider, app = setup(tmp_path, {
        "user": [claim("我的生日其实是2000-03-06")], "assistant": []})
    original = client.post(f"/api/conversations/{cid}/messages", headers=headers,
                           json={"content": "我的生日其实是2000-03-06"}).json()["user_message"]
    corrected_quote = "更正一下，2001-04-07才是我的公历生日"
    text = "平常风景和日记。" * 800 + corrected_quote
    provider.payload = {"user": [claim(corrected_quote, correction=True, date_basis="gregorian")], "assistant": []}
    corrected = client.post(f"/api/conversations/{cid}/messages", headers=headers,
                            json={"content": text}).json()["user_message"]
    owner = app.state.sessions.get(headers["Authorization"][7:]).owner
    for _ in range(25):
        app.state.chat.save_turn(owner, cid, "日常散步", "轻松休息", [])
    context = conversation_context_result(app.state.chat, owner, cid, "2000")
    refs = {row["message_id"]: row for row in context.references}
    assert refs[original["id"]]["source_current"] is False
    ref = refs[corrected["id"]]
    assert corrected_quote in ref["text"] and ref["char_start"] == text.index(corrected_quote)
    assert text[ref["char_start"]:ref["char_end"]] == ref["text"]
