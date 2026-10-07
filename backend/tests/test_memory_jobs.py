import hashlib

import pytest

from app.database import ChatDatabase
from app.memory_management import MemoryConflict, MemoryManagement
from app.memory_schemas import MemoryEdit
from app.memory_semantic import SEMANTIC_VERSION


def setup(tmp_path):
    chat = ChatDatabase(tmp_path, auto_annotations=True, annotation_mode="semantic")
    cid = chat.create_conversation("owner", "新对话", "test-model", False)["id"]
    return chat, cid


def saved(chat, cid, text="完整生日原话", answer="完整回复原话"):
    return chat.save_turn("owner", cid, text, answer, [],
                          deferred_recognition={"provider_id": "deepseek", "model": "test-model"})


def test_pending_originals_and_job_share_transaction_and_no_credentials_or_body_copy(tmp_path):
    chat, cid = setup(tmp_path)
    result = saved(chat, cid)
    with chat.connection() as db:
        job = dict(db.execute("SELECT * FROM memory_jobs").fetchone())
        assert job["status"] == "pending" and job["attempts"] == 0
        assert job["user_sha256"] == hashlib.sha256("完整生日原话".encode()).hexdigest()
        assert job["user_id"] == result["user_message"]["id"]
        assert job["assistant_created_at"] == result["assistant_message"]["created_at"]
        assert all(row[0] == "pending" for row in db.execute("SELECT status FROM memory_recognition"))
        assert not any(word in key for key in job for word in ("api_key", "token", "content", "body"))
    assert chat.memory_jobs.claim("foreign", "deepseek") is None


def test_job_insert_fault_rolls_back_both_originals_metadata_and_index(tmp_path):
    from app.source_codec import ChatStorageError

    chat, cid = setup(tmp_path)
    with chat.connection() as db:
        db.execute("""CREATE TRIGGER fail_job BEFORE INSERT ON memory_jobs
                      BEGIN SELECT RAISE(ABORT,'PRIVATE-KEY'); END""")
    with pytest.raises(ChatStorageError, match="暂时"):
        saved(chat, cid)
    with chat.connection() as db:
        for table in ("messages", "memory_recognition", "memory_fragments", "memory_jobs"):
            assert db.execute(f"SELECT count(*) FROM {table}").fetchone()[0] == 0


def test_owner_fifo_lease_idempotency_and_delete_cascade(tmp_path):
    chat, cid = setup(tmp_path)
    saved(chat, cid)
    saved(chat, cid, "第二条原话")
    first = chat.memory_jobs.claim("owner", "deepseek")
    assert first is not None and first["user_sequence"] == 1
    assert chat.memory_jobs.claim("owner", "deepseek") is None
    recognition = {"status": "ok", "version": SEMANTIC_VERSION, "roles": {"user": "ok", "assistant": "ok"},
                   "claims": {"user": [], "assistant": []}}
    assert chat.memory_jobs.finish("owner", first, recognition)
    assert not chat.memory_jobs.finish("owner", first, recognition)
    assert chat.memory_jobs.claim("owner", "deepseek")["user_sequence"] == 3
    assert chat.delete_conversation("owner", cid)
    assert not chat.memory_jobs.finish("owner", first, recognition)
    with chat.connection() as db:
        assert db.execute("SELECT count(*) FROM memory_jobs").fetchone()[0] == 0


def test_pending_source_cannot_be_cold_and_source_change_rejects_late_annotations(tmp_path):
    chat, cid = setup(tmp_path)
    result = saved(chat, cid)
    management = MemoryManagement(chat)
    mid = result["user_message"]["id"]
    meta = management.get("owner", cid, mid)
    with pytest.raises(MemoryConflict, match="识别"):
        management.update("owner", cid, mid, MemoryEdit(index_state="cold", expected_revision=meta["revision"]))
    task = chat.memory_jobs.claim("owner", "deepseek")
    with chat.connection() as db:
        db.execute("UPDATE messages SET content='changed source' WHERE sequence=?", (task["user_sequence"],))
    assert chat.memory_jobs.sources("owner", task) is None


def test_reconstruction_requires_scoped_auth_recovery_and_future_catalogue_excluded(tmp_path):
    chat, cid = setup(tmp_path)
    saved(chat, cid)
    job = chat.memory_jobs.claim("owner", "deepseek")
    rebuilt = ChatDatabase(tmp_path, auto_annotations=True, annotation_mode="semantic")
    with rebuilt.connection() as db:
        assert db.execute("SELECT status FROM memory_jobs").fetchone()[0] == "running"
    rebuilt.memory_jobs.recover("foreign")
    assert rebuilt.memory_jobs.claim("owner", "deepseek") is None
    rebuilt.memory_jobs.recover("owner")
    again = rebuilt.memory_jobs.claim("owner", "deepseek")
    assert again["lease"] != job["lease"] and again["attempts"] == 2
    assert rebuilt.recognition_catalogue("owner", cid, before=1) == []


def test_pending_partner_also_protects_direct_cold_and_visible_management(tmp_path):
    from app.source_codec import ChatStorageError

    chat, cid = setup(tmp_path)
    result = saved(chat, cid)
    with chat.connection() as db:
        db.execute("UPDATE memory_recognition SET status='ok' WHERE sequence=2")
    with pytest.raises(ChatStorageError, match="识别"), chat.connection() as db:
        db.execute("BEGIN IMMEDIATE")
        chat.cold.cool(db, 2, cid, result["assistant_message"]["content"], chat.memory)
    management = MemoryManagement(chat)
    mid = result["assistant_message"]["id"]
    with pytest.raises(MemoryConflict, match="识别"):
        management.update("owner", cid, mid, MemoryEdit(index_state="cold", expected_revision=0))
    with chat.connection() as db:
        assert db.execute("SELECT count(*) FROM memory_cold_directory").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM memory_fragments").fetchone()[0] > 0


def test_exhausted_task_is_final_and_does_not_block_newer_queue(tmp_path):
    chat, cid = setup(tmp_path)
    saved(chat, cid)
    saved(chat, cid, "后来原文")
    with chat.connection() as db:
        db.execute("UPDATE memory_jobs SET attempts=2,status='waiting_auth' WHERE user_sequence=1")
    assert chat.memory_jobs.claim("owner", "deepseek")["user_sequence"] == 3
    with chat.connection() as db:
        assert db.execute("SELECT status FROM memory_jobs WHERE user_sequence=1").fetchone()[0] == "failed"


def test_catalogue_does_not_expose_future_labels(tmp_path):
    import json

    from app.memory_semantic import parse_response

    chat, cid = setup(tmp_path)
    def recognised(text, topic):
        claim = {"quote": text, "kind": "preference", "subject": "user", "polarity": "affirm",
                 "topic_key": topic, "confidence": 0.96, "explicit_keep": False, "correction": False,
                 "promise_state": None, "date_basis": None, "event_date": None}
        sources = {"user": {"content": text, "created_at": "2026-10-06T00:00:00+00:00"},
                   "assistant": {"content": "轻松回复", "created_at": "2026-10-06T00:00:01+00:00"}}
        claims = parse_response(json.dumps({"user": [claim], "assistant": []}), sources)
        return {"status": "ok", "version": SEMANTIC_VERSION, "roles": {"user": "ok", "assistant": "ok"}, "claims": claims}
    chat.save_turn("owner", cid, "我喜欢旧书", "轻松回复", [], recognition=recognised("我喜欢旧书", "like:books"))
    saved(chat, cid)
    chat.save_turn("owner", cid, "我喜欢星星", "轻松回复", [], recognition=recognised("我喜欢星星", "like:stars"))
    assert chat.recognition_catalogue("owner", cid, before=3) == [{"kind": "preference", "subject": "user", "topic_key": "like:books"}]
    assert len(chat.recognition_catalogue("owner", cid)) == 2
