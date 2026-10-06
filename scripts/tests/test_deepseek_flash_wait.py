import asyncio
import hashlib
import importlib.util
import json
import sys
from collections import Counter
from pathlib import Path
from types import SimpleNamespace

import pytest

SCRIPT = Path(__file__).parents[1] / "deepseek_flash_wait.py"
SPEC = importlib.util.spec_from_file_location("deepseek_flash_wait", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


def reply_case(identifier="synthetic"):
    return {"id": identifier, "category": "reply", "query": "你还好吗？",
            "messages": [{"role": "user", "content": "你还好吗？"}],
            "profile": None, "required_checks": [], "forbidden_checks": []}


def flow(case=None):
    return {"id": "W01", "case": case or reply_case(), "repeat": 1,
            "reply_mode": "disabled", "recognition_mode": "enabled"}


def phase(content, *, finish="stop", status=200, done=True):
    return {"http_status": status, "finish_reason": finish, "content": content,
            "done_received": done, "first_reasoning_seconds": .001, "first_content_seconds": .002,
            "latency_seconds": .01, "usage": {"completion_tokens": 20},
            "estimated_cny_offpeak": None, "grade": {"usable_pass": True}}


def test_schedule_has_24_flows_and_each_case_combination_twice():
    cases = [reply_case(f"case{i}") for i in range(4)]
    scheduled = module.schedule_flows(cases)
    assert len(scheduled) == 24
    counts = Counter((s["case"]["id"], s["reply_mode"], s["recognition_mode"]) for s in scheduled)
    assert set(counts.values()) == {2}
    assert {(s["reply_mode"], s["recognition_mode"]) for s in scheduled} == {
        ("disabled", "disabled"), ("enabled", "enabled"), ("disabled", "enabled")}
    with pytest.raises(ValueError, match="reply_case_count_invalid"):
        module.schedule_flows(cases[:3])


def test_real_reply_is_recognition_source_and_exact_saved_text(tmp_path, monkeypatch):
    database = module.ChatDatabase(tmp_path / "synthetic-db", auto_annotations=True, annotation_mode="semantic")
    answer = "我答应陪你整理清单。"
    claim = {"quote": answer, "kind": "promise", "subject": "assistant", "polarity": "affirm",
             "topic_key": "assistant:promise:清单", "confidence": .99, "explicit_keep": False,
             "correction": False, "promise_state": "pending", "date_basis": None, "event_date": None}
    calls, progress = [], []

    async def fake_trial(_client, _key, trial, **options):
        calls.append(trial)
        assert options == {}
        assert module.MAX_TOKENS == 393216
        if len(calls) == 1:
            return phase(answer)
        context = json.loads(trial["case"]["messages"][1]["content"])
        assert context["sources"]["assistant"]["original"] == answer
        assert context["sources"]["user"]["original"] == "你还好吗？"
        return phase(json.dumps({"user": [], "assistant": [claim]}, ensure_ascii=False))

    monkeypatch.setattr(module, "run_stream_trial", fake_trial)
    result = asyncio.run(module.run_flow(None, "synthetic-test-key", database, flow(), progress.append))
    stored = database.list_messages(module.SYNTHETIC_OWNER, result["conversation_id"])
    assert [(m["role"], m["content"]) for m in stored] == [("user", "你还好吗？"), ("assistant", answer)]
    assert result["status"] == "complete"
    assert result["saved_originals_verified"] is True
    assert result["recognition_format_and_source_pass"] is True
    assert result["assistant_sha256"] == hashlib.sha256(answer.encode()).hexdigest()
    assert result["reply"]["first_reasoning_seconds"] == .001
    assert result["recognition"]["first_content_seconds"] == .002
    assert result["total_wait_seconds"] >= result["save_turn_seconds"] >= 0
    assert answer not in json.dumps(result, ensure_ascii=False)
    assert all("content" not in p for p in progress)


@pytest.mark.parametrize("reply", [phase("", status=500), phase("截断回复", finish="length"), phase(""),
                                    phase("尚未收到结束标记。", done=False)])
def test_reply_failure_skips_recognition_and_database_save(tmp_path, monkeypatch, reply):
    database = module.ChatDatabase(tmp_path / "synthetic-db", auto_annotations=True, annotation_mode="semantic")
    calls = []

    async def fake_trial(*args, **_options):
        calls.append(args)
        return reply

    monkeypatch.setattr(module, "run_stream_trial", fake_trial)
    result = asyncio.run(module.run_flow(None, "synthetic-test-key", database, flow()))
    assert len(calls) == result["cloud_calls"] == 1
    assert result["status"] == "reply_failed"
    assert result["recognition"] is None
    assert database.list_conversations(module.SYNTHETIC_OWNER) == []


def test_invalid_recognition_safely_saves_reply_and_failed_roles(tmp_path, monkeypatch):
    database = module.ChatDatabase(tmp_path / "synthetic-db", auto_annotations=True, annotation_mode="semantic")
    calls = []

    async def fake_trial(*args, **_options):
        calls.append(args)
        return phase("我在听。" if len(calls) == 1 else "invalid JSON")

    monkeypatch.setattr(module, "run_stream_trial", fake_trial)
    result = asyncio.run(module.run_flow(None, "synthetic-test-key", database, flow()))
    assert result["status"] == "recognition_failed_saved"
    assert result["saved_originals_verified"] is True
    with database.connection() as db:
        assert [row[0] for row in db.execute("SELECT status FROM memory_recognition ORDER BY sequence")] == ["failed", "failed"]


def test_secret_echo_is_removed_before_recognition_and_persistence(tmp_path, monkeypatch):
    database = module.ChatDatabase(tmp_path / "synthetic-db", auto_annotations=True, annotation_mode="semantic")
    calls = []

    async def fake_trial(*args, **_options):
        calls.append(args)
        if len(calls) == 1:
            return phase("你好 synthetic-test-key")
        assert "synthetic-test-key" not in json.dumps(args[2], ensure_ascii=False)
        return phase('{"user":[],"assistant":[]}')

    monkeypatch.setattr(module, "run_stream_trial", fake_trial)
    result = asyncio.run(module.run_flow(None, "synthetic-test-key", database, flow()))
    stored = database.list_messages(module.SYNTHETIC_OWNER, result["conversation_id"])
    assert "synthetic-test-key" not in json.dumps(stored)
    assert "synthetic-test-key" not in json.dumps(result)
    assert "synthetic-test-key" not in module.SYNTHETIC_OWNER


def test_reply_rubric_failure_does_not_artificially_skip_wait(tmp_path, monkeypatch):
    database = module.ChatDatabase(tmp_path / "synthetic-db", auto_annotations=True, annotation_mode="semantic")
    calls = []

    async def fake_trial(*args, **_options):
        calls.append(args)
        result = phase("仍是完整回复。" if len(calls) == 1 else '{"user":[],"assistant":[]}')
        result["grade"] = {"usable_pass": False}
        return result

    monkeypatch.setattr(module, "run_stream_trial", fake_trial)
    result = asyncio.run(module.run_flow(None, "synthetic-test-key", database, flow()))
    assert result["cloud_calls"] == 2
    assert result["reply"]["rubric_pass"] is False
    assert result["saved_originals_verified"] is True


@pytest.mark.parametrize("http_status, flows, calls", [(200, 24, 48), (401, 1, 1)])
def test_execute_bounded_progress_and_auth_abort(tmp_path, monkeypatch, http_status, flows, calls):
    credential_file = tmp_path / "credentials.synthetic"
    credential_file.write_text("RAG_LLM_API_KEY=synthetic-test-key\nRAG_LLM_BASE_URL=https://api.deepseek.com\n")
    monkeypatch.setattr(module, "ROOT", tmp_path)
    monkeypatch.setattr(module, "freeze_cases", lambda: [reply_case(f"case{i}") for i in range(4)])
    monkeypatch.setattr(module, "source_hashes", dict)
    seen, events = [], []

    async def fake_trial(_client, _key, trial, **_options):
        seen.append(trial)
        text = "我在听。" if trial["case"]["category"] == "reply" else '{"user":[],"assistant":[]}'
        return phase(text, status=http_status)

    monkeypatch.setattr(module, "run_stream_trial", fake_trial)
    result_code = asyncio.run(module.execute(credential_file, progress=events.append))
    result_path = next((tmp_path / "artifacts").glob("*/results.json"))
    result = json.loads(result_path.read_text(encoding="utf-8"))
    assert len(result["flows"]) == flows
    assert result["attempted_cloud_calls"] == len(seen) == calls
    assert result_code == (0 if http_status == 200 else 1)
    assert result["credential_file_unchanged"] is True
    assert "synthetic-test-key" not in json.dumps(events)
    assert "synthetic-test-key" not in result_path.read_text(encoding="utf-8")
    assert any(e.get("phase") == "reply" and e.get("event") == "starting" for e in events)


def test_saved_role_or_original_hash_mismatch_is_explicit_failure(tmp_path, monkeypatch):
    database = module.ChatDatabase(tmp_path / "synthetic-db", auto_annotations=True, annotation_mode="semantic")
    calls = []

    async def fake_trial(*args, **_options):
        calls.append(args)
        return phase("原回复。" if len(calls) == 1 else '{"user":[],"assistant":[]}')

    monkeypatch.setattr(module, "run_stream_trial", fake_trial)
    monkeypatch.setattr(database, "list_messages", lambda *_args: [
        {"role": "assistant", "content": "你还好吗？"}, {"role": "user", "content": "被改写。"}])
    result = asyncio.run(module.run_flow(None, "synthetic-test-key", database, flow()))
    assert result["status"] == "storage_failed"
    assert result["saved_originals_verified"] is False
    assert result["save_return_roles_verified"] is True


def test_storage_exception_message_is_never_logged(tmp_path, monkeypatch):
    database = module.ChatDatabase(tmp_path / "synthetic-db", auto_annotations=True, annotation_mode="semantic")
    calls, events = [], []

    async def fake_trial(*args, **_options):
        calls.append(args)
        return phase("完整回复。" if len(calls) == 1 else '{"user":[],"assistant":[]}')

    def broken_save(*_args, **_kwargs):
        raise module.ChatStorageError("private synthetic-test-key message")

    monkeypatch.setattr(module, "run_stream_trial", fake_trial)
    monkeypatch.setattr(database, "save_turn", broken_save)
    result = asyncio.run(module.run_flow(None, "synthetic-test-key", database, flow(), events.append))
    assert result["status"] == "storage_failed"
    assert result["storage_error"] == "ChatStorageError"
    assert "synthetic-test-key" not in json.dumps([result, events])


def test_stream_wrapper_and_unlimited_read_configuration(monkeypatch):
    async def fake_stream(client, key, trial):
        assert (client, key, trial) == (None, "synthetic-test-key", {"id": "mock"})
        return {"done_received": True}

    monkeypatch.setitem(sys.modules, "deepseek_flash_stream", SimpleNamespace(run_stream_trial=fake_stream))
    assert asyncio.run(module.run_stream_trial(None, "synthetic-test-key", {"id": "mock"})) == {"done_received": True}
    assert module.MAX_TOKENS == 393216
    assert module.NETWORK_TIMEOUT.read is None
    assert module.NETWORK_TIMEOUT.connect == module.NETWORK_TIMEOUT.write == module.NETWORK_TIMEOUT.pool == 30


def test_reasoning_alone_or_stream_error_is_never_final_content():
    reasoning_only = phase("") | {"reasoning_content": "private reasoning"}
    failed_stream = phase("partial") | {"error": "ReadError"}
    assert module._complete(reasoning_only) is False
    assert module._complete(failed_stream) is False
    assert "reasoning_content" not in module._phase_metadata(reasoning_only)


def test_large_reply_preserved_but_semantic_source_limit_reported(tmp_path, monkeypatch):
    database = module.ChatDatabase(tmp_path / "synthetic-db", auto_annotations=True, annotation_mode="semantic")
    answer, calls = "a" * 16001, []

    async def fake_stream(*args, **_options):
        calls.append(args)
        if len(calls) == 1:
            return phase(answer)
        context = json.loads(args[2]["case"]["messages"][1]["content"])
        assert context["sources"]["assistant"]["status"] == "skipped_large"
        assert "original" not in context["sources"]["assistant"]
        return phase('{"user":[],"assistant":[]}')

    monkeypatch.setattr(module, "run_stream_trial", fake_stream)
    result = asyncio.run(module.run_flow(None, "synthetic-test-key", database, flow()))
    assert result["status"] == "complete"
    assert result["recognition_status"] == "partial"
    assert result["recognition_role_statuses"]["assistant"] == "skipped_large"
    assert result["saved_originals_verified"] is True

