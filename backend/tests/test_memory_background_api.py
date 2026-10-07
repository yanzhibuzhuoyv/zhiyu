import asyncio
import json
import threading

from fastapi.testclient import TestClient
from test_api import FakeProvider, login

from app.config import Settings
from app.main import create_app


class BlockingRecognition(FakeProvider):
    def __init__(self):
        super().__init__()
        self.started = threading.Event()
        self.release = threading.Event()
        self.recognitions = []
        self.payload = {"user": [], "assistant": []}

    async def complete(self, provider_id, key, model, messages, max_tokens=2048):
        self.calls.append(messages)
        if max_tokens != 4096:
            return "我愿意认真听你说。"
        self.recognitions.append(json.loads(messages[-1]["content"]))
        self.started.set()
        while not self.release.is_set():
            await asyncio.sleep(0.005)
        return json.dumps(self.payload, ensure_ascii=False)


def app_for(tmp_path, provider):
    return create_app(Settings(data_dir=tmp_path, auto_memory_annotations=True,
                               memory_annotation_mode="semantic"), provider_client=provider)


def test_http_returns_full_originals_pending_and_second_chat_while_recognition_is_blocked(tmp_path):
    provider = BlockingRecognition()
    app = app_for(tmp_path, provider)
    with TestClient(app) as client:
        try:
            headers = login(client)
            cid = client.post("/api/conversations", headers=headers, json={"use_rag": False}).json()["id"]
            url = f"/api/conversations/{cid}/messages"
            response = client.post(url, headers=headers, json={"content": "第一条完整原文"})
            assert response.status_code == 200
            assert response.json()["memory_recognition"] == {"mode": "semantic", "status": "pending"}
            assert provider.started.wait(2)
            second = client.post(url, headers=headers, json={"content": "第二条完整原文"})
            assert second.status_code == 200 and not provider.release.is_set()
            assert len(provider.recognitions) == 1  # No parallel same-owner/correction reordering.
            assert len(client.get(url, headers=headers).json()) == 4
            provider.release.set()
            client.portal.call(app.state.memory_worker.wait_idle)
            assert len(provider.recognitions) == 2
        finally:
            provider.release.set()


def test_restart_does_not_call_or_scan_owner_tasks_until_valid_authentication(tmp_path):
    provider = BlockingRecognition()
    app = app_for(tmp_path, provider)
    with TestClient(app) as client:
        headers = login(client)
        session = app.state.sessions.get(headers["Authorization"][7:])
        cid = app.state.chat.create_conversation(session.owner, "新对话", "test-model", False)["id"]
        saved = app.state.chat.save_turn(session.owner, cid, "旧完整原文", "旧完整回复", [],
                    deferred_recognition={"provider_id": "deepseek", "model": "test-model"})
    rebuilt_provider = BlockingRecognition()
    rebuilt = app_for(tmp_path, rebuilt_provider)
    with TestClient(rebuilt) as client:
        assert client.get("/health").status_code == 200
        assert client.get(f"/api/conversations/{cid}/memory", headers=headers).status_code == 401
        assert not rebuilt_provider.calls
        try:
            fresh = login(client)
            assert rebuilt_provider.started.wait(2)
            rebuilt_provider.release.set()
            client.portal.call(rebuilt.state.memory_worker.wait_idle)
            assert client.get(f"/api/conversations/{cid}/messages", headers=fresh).json()[0] == saved["user_message"]
        finally:
            rebuilt_provider.release.set()


def test_recognition_storage_error_keeps_originals_and_marks_failure_without_retry(tmp_path):
    provider = BlockingRecognition()
    provider.payload = {"user": [{"quote": "十月六号是我生日", "kind": "profile", "subject": "user",
        "polarity": "affirm", "topic_key": "user:birthday", "confidence": 0.96,
        "explicit_keep": False, "correction": False, "promise_state": None,
        "date_basis": None, "event_date": None}], "assistant": []}
    app = app_for(tmp_path, provider)
    with TestClient(app) as client:
        try:
            headers = login(client)
            cid = client.post("/api/conversations", headers=headers, json={"use_rag": False}).json()["id"]
            with app.state.chat.connection() as db:
                db.execute("""CREATE TRIGGER fail_annotation BEFORE INSERT ON memory_annotations
                    BEGIN SELECT RAISE(ABORT,'PRIVATE-KEY'); END""")
            response = client.post(f"/api/conversations/{cid}/messages", headers=headers,
                                   json={"content": "十月六号是我生日"})
            assert response.status_code == 200 and "PRIVATE" not in response.text
            provider.release.set()
            client.portal.call(app.state.memory_worker.wait_idle)
            with app.state.chat.connection() as db:
                assert db.execute("SELECT count(*) FROM messages").fetchone()[0] == 2
                assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 0
                assert db.execute("SELECT count(*) FROM memory_semantic_runs").fetchone()[0] == 0
                assert db.execute("SELECT status FROM memory_jobs").fetchone()[0] == "failed"
                assert {row[0] for row in db.execute("SELECT status FROM memory_recognition")} == {"failed"}
            client.get(f"/api/conversations/{cid}/memory", headers=headers)
            client.portal.call(app.state.memory_worker.wait_idle)
            assert len(provider.recognitions) == 1
        finally:
            provider.release.set()
