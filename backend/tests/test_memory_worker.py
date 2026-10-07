import asyncio
import json
from datetime import UTC, datetime, timedelta

import pytest

from app.auth import Sessions
from app.database import ChatDatabase
from app.memory_worker import MemoryWorker


class Provider:
    def __init__(self):
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.calls = []
        self.active = 0
        self.maximum = 0

    async def complete(self, provider, key, model, messages, max_tokens):
        self.calls.append((provider, key, model, messages))
        self.active += 1
        self.maximum = max(self.maximum, self.active)
        self.started.set()
        try:
            await self.release.wait()
            return json.dumps({"user": [], "assistant": []})
        finally:
            self.active -= 1


def setup(tmp_path):
    sessions = Sessions(tmp_path)
    chat = ChatDatabase(tmp_path, auto_annotations=True, annotation_mode="semantic")
    provider = Provider()
    return sessions, chat, provider, MemoryWorker(chat, sessions, provider)


def turn(sessions, chat, number=0):
    token, session = sessions.issue("deepseek", f"synthetic-key-{number}", "test-model")
    cid = chat.create_conversation(session.owner, "新对话", "test-model", False)["id"]
    result = chat.save_turn(session.owner, cid, "用户原文", "AI原文", [],
                           deferred_recognition={"provider_id": "deepseek", "model": "test-model"})
    return token, session, cid, result


async def wait_for(predicate):
    async with asyncio.timeout(3):
        while not predicate():
            await asyncio.sleep(0.005)


@pytest.mark.asyncio
async def test_background_two_slots_and_foreground_priority_and_owner_isolation(tmp_path):
    sessions, chat, provider, worker = setup(tmp_path)
    inputs = [turn(sessions, chat, n) for n in range(3)]
    try:
        async with worker.foreground():
            for token, session, _, _ in inputs:
                worker.activate(token, session)
            await asyncio.sleep(0.03)
            assert not provider.calls
        await wait_for(lambda: provider.active == 2)
        assert provider.maximum == 2 and len(provider.calls) == 2
        provider.release.set()
        await worker.wait_idle()
        assert len(provider.calls) == 3 and provider.maximum == 2
        with chat.connection() as db:
            assert db.execute("SELECT count(*) FROM memory_semantic_runs").fetchone()[0] == 6
    finally:
        await worker.aclose()


@pytest.mark.asyncio
async def test_logout_cancels_old_credentials_then_new_auth_recovers_without_rewriting(tmp_path):
    sessions, chat, provider, worker = setup(tmp_path)
    token, session, cid, original = turn(sessions, chat)
    try:
        worker.activate(token, session)
        await provider.started.wait()
        sessions.revoke(token)
        await worker.revoke(token)
        await worker.wait_idle()
        with chat.connection() as db:
            assert db.execute("SELECT status FROM memory_jobs").fetchone()[0] == "waiting_auth"
            assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 0
        fresh, connected = sessions.issue("deepseek", "synthetic-key-0", "test-model")
        provider.release.set()
        worker.activate(fresh, connected)
        await worker.wait_idle()
        assert chat.list_messages(session.owner, cid)[0] == original["user_message"]
        with chat.connection() as db:
            assert db.execute("SELECT status FROM memory_jobs").fetchone()[0] == "ok"
    finally:
        await worker.aclose()


@pytest.mark.asyncio
async def test_expiry_stops_model_and_auth_recovery_is_bounded(tmp_path):
    from dataclasses import replace

    sessions, chat, provider, worker = setup(tmp_path)
    token, session, _, _ = turn(sessions, chat)
    session = replace(session, expires_at=datetime.now(UTC) + timedelta(seconds=0.05))
    sessions._items[sessions._digest(token)] = session
    try:
        worker.activate(token, session)
        await provider.started.wait()
        await worker.wait_idle()
        assert provider.active == 0
        with chat.connection() as db:
            assert db.execute("SELECT status FROM memory_jobs").fetchone()[0] == "waiting_auth"
        worker.activate(token, session)
        await worker.wait_idle()
        assert len(provider.calls) == 1
    finally:
        await worker.aclose()


@pytest.mark.asyncio
async def test_delete_during_identification_discards_late_result(tmp_path):
    sessions, chat, provider, worker = setup(tmp_path)
    token, session, cid, _ = turn(sessions, chat)
    try:
        worker.activate(token, session)
        await provider.started.wait()
        assert chat.delete_conversation(session.owner, cid)
        provider.release.set()
        await worker.wait_idle()
        with chat.connection() as db:
            assert db.execute("SELECT count(*) FROM memory_jobs").fetchone()[0] == 0
            assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 0
    finally:
        await worker.aclose()
