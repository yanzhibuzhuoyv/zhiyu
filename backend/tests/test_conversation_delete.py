"""Atomic, isolated deletion of indexed conversations, including late failures."""

import asyncio
from threading import Event, Timer

import httpx
import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider

from app.config import Settings
from app.main import create_app


@pytest.fixture
def populated_app(tmp_path):
    app = create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())
    token, session = app.state.sessions.issue("deepseek", "delete-owner-key", "model")
    foreign_token, foreign = app.state.sessions.issue("deepseek", "foreign-owner-key", "model")
    chat = app.state.chat
    target = chat.create_conversation(session.owner, "target", "model", False)["id"]
    sibling = chat.create_conversation(session.owner, "sibling", "model", False)["id"]
    unrelated = chat.create_conversation(foreign.owner, "unrelated", "model", False)["id"]
    for turn in range(1100):
        chat.save_turn(session.owner, target,
                       f"targetanchor 用户原记录 {turn}。" + "长段落保留全部原文。" * 100,
                       f"targetanchor AI原记录 {turn}。" + "不同作者的回答不合并。" * 100, [])
    for identifier, owner, marker in ((sibling, session.owner, "siblinganchor"),
                                      (unrelated, foreign.owner, "foreignanchor")):
        for turn in range(2):
            chat.save_turn(owner, identifier, f"{marker} 用户 {turn}。",
                           f"{marker} AI {turn}。", [])
    return app, target, sibling, unrelated, {"Authorization": "Bearer " + token}, {
        "Authorization": "Bearer " + foreign_token,
    }


def database_snapshot(chat):
    """Compare canonical originals, provenance, fragment links and real FTS hits."""
    with chat.connection() as db:
        tables = {
            "conversations": [dict(row) for row in db.execute("SELECT * FROM conversations ORDER BY id")],
            "messages": [dict(row) for row in db.execute("SELECT * FROM messages ORDER BY sequence")],
            "fragments": [dict(row) for row in db.execute("SELECT * FROM memory_fragments ORDER BY id")],
            "fts_rowids": [row[0] for row in db.execute("SELECT rowid FROM memory_fts ORDER BY rowid")],
        }
        hits = {}
        for marker in ("targetanchor", "siblinganchor", "foreignanchor"):
            query = "t" + marker.encode().hex()
            hits[marker] = [row[0] for row in db.execute(
                "SELECT rowid FROM memory_fts WHERE memory_fts MATCH ? ORDER BY rowid", (query,)
            )]
        db.execute("INSERT INTO memory_fts(memory_fts) VALUES('integrity-check')")
    return {**tables, "hits": hits}


def without_conversation(snapshot, identifier):
    removed_sequences = {row["sequence"] for row in snapshot["messages"]
                         if row["conversation_id"] == identifier}
    removed_fragments = {row["id"] for row in snapshot["fragments"]
                         if row["message_sequence"] in removed_sequences}
    return {
        "conversations": [row for row in snapshot["conversations"] if row["id"] != identifier],
        "messages": [row for row in snapshot["messages"] if row["sequence"] not in removed_sequences],
        "fragments": [row for row in snapshot["fragments"] if row["id"] not in removed_fragments],
        "fts_rowids": [rowid for rowid in snapshot["fts_rowids"] if rowid not in removed_fragments],
        "hits": {marker: [rowid for rowid in hits if rowid not in removed_fragments]
                 for marker, hits in snapshot["hits"].items()},
    }


def test_large_delete_preserves_other_owner_and_same_owner_sibling(populated_app):
    app, target, sibling, unrelated, headers, foreign_headers = populated_app
    chat = app.state.chat
    before = database_snapshot(chat)
    assert len([row for row in before["messages"] if row["conversation_id"] == target]) == 2200
    assert before["hits"]["targetanchor"]
    with TestClient(app) as client:
        assert client.delete(f"/api/conversations/{target}", headers=foreign_headers).status_code == 404
        assert database_snapshot(chat) == before
        assert client.delete(f"/api/conversations/{target}", headers=headers).status_code == 204
        assert client.get(f"/api/conversations/{target}/history", headers=headers).status_code == 404
        assert client.get(f"/api/conversations/{sibling}/history", headers=headers).status_code == 200
        assert client.get(f"/api/conversations/{unrelated}/history", headers=foreign_headers).status_code == 200
    assert database_snapshot(chat) == without_conversation(before, target)


@pytest.mark.parametrize("failure_position", [1604, 1732], ids=["middle-after-1536-messages", "late-after-1536-messages"])
def test_late_sqlite_failure_rolls_back_all_originals_and_fts_then_retry_succeeds(
        populated_app, failure_position):
    app, target, _, _, headers, _ = populated_app
    chat = app.state.chat
    before = database_snapshot(chat)
    target_rows = [row for row in before["messages"] if row["conversation_id"] == target]
    failure_sequence = target_rows[failure_position]["sequence"]
    with chat.connection() as db:
        db.execute(f"""CREATE TRIGGER test_fail_late_delete BEFORE DELETE ON messages
            WHEN old.sequence={failure_sequence}
            BEGIN SELECT RAISE(ABORT,'PRIVATE-DELETE-FAULT'); END""")
    with TestClient(app, raise_server_exceptions=False) as client:
        failed = client.delete(f"/api/conversations/{target}", headers=headers)
        # This compares all roles' full originals/IDs/times and all scoped FTS hits,
        # including the records deleted before the failing message was reached.
        assert database_snapshot(chat) == before
        assert failed.status_code == 503
        assert "PRIVATE" not in failed.text
        with chat.connection() as db:
            db.execute("DROP TRIGGER test_fail_late_delete")
        assert client.delete(f"/api/conversations/{target}", headers=headers).status_code == 204
    assert database_snapshot(chat) == without_conversation(before, target)


@pytest.mark.asyncio
async def test_health_responds_while_slow_indexed_conversation_delete_is_in_progress(
        populated_app, monkeypatch):
    app, target, _, _, headers, _ = populated_app
    entered, release, finished = Event(), Event(), Event()
    original = app.state.chat.delete_conversation

    def slow_delete(owner, identifier):
        entered.set()
        try:
            if not release.wait(timeout=5):
                raise RuntimeError("Test deletion was not released")
            return original(owner, identifier)
        finally:
            finished.set()

    monkeypatch.setattr(app.state.chat, "delete_conversation", slow_delete)
    # A timer in another thread releases even a blocked event loop on the old route.
    # The assertion checks overlapping work, rather than a speed ratio or SQL text.
    fallback = Timer(3, release.set)
    fallback.daemon = True
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        fallback.start()
        deleting = asyncio.create_task(client.delete(f"/api/conversations/{target}", headers=headers))
        try:
            assert await asyncio.to_thread(entered.wait, 4)
            health = await asyncio.wait_for(client.get("/health"), timeout=1)
            assert health.status_code == 200
            assert health.json()["status"] == "ok"
            assert not finished.is_set(), "The event loop waited for deletion before serving health"
        finally:
            release.set()
            try:
                deleted = await asyncio.wait_for(deleting, timeout=5)
            finally:
                fallback.cancel()
        assert deleted.status_code == 204
