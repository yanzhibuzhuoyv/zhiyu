import asyncio
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

import pytest

SCRIPT = Path(__file__).parents[1] / "deepseek_flash_long_output.py"
SPEC = importlib.util.spec_from_file_location("deepseek_flash_long_output", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


def test_synthetic_request_is_explicit_fiction_and_same_for_two_flows():
    case = module.build_case()
    assert "6000" in case["query"] and "8000" in case["query"]
    assert "完全虚构" in case["query"]
    assert case["profile"]["style"] == "gentle"
    flows = module.schedule_flows(case)
    assert [(f["reply_mode"], f["recognition_mode"]) for f in flows] == [
        ("disabled", "disabled"), ("enabled", "enabled")]
    assert all(f["case"] is case and f["repeat"] == 1 for f in flows)


def test_unicode_hanzi_and_utf8_are_distinct_measurements():
    metrics = module.output_metrics("汉字abc🙂！\n𠀀")
    assert metrics["unicode_chars"] == 9
    assert metrics["chinese_hanzi"] == 3
    assert metrics["utf8_bytes"] == 21
    assert metrics["requested_hanzi_range_met"] is False


@pytest.mark.parametrize("count, met", [(5999, False), (6000, True), (8000, True), (8001, False)])
def test_requested_hanzi_range_is_inclusive_without_padding(count, met):
    text = "字" * count  # Synthetic unit-test boundary data, never used to pad a model reply.
    assert module.output_metrics(text)["requested_hanzi_range_met"] is met
    assert module.output_metrics(text)["unicode_chars"] == count


def test_measure_reads_only_verified_new_database_reply(tmp_path):
    database = module.wait.ChatDatabase(tmp_path / "synthetic-db", auto_annotations=True, annotation_mode="semantic")
    conversation = database.create_conversation(module.wait.SYNTHETIC_OWNER, "mock", "deepseek-flash", False)
    answer = "完全虚构的小故事。"
    recognition = {"version": "semantic-v1", "roles": {"user": "failed", "assistant": "failed"},
                   "claims": {"user": [], "assistant": []}}
    database.save_turn(module.wait.SYNTHETIC_OWNER, conversation["id"], "合成请求", answer, [], recognition=recognition)
    result = {"saved_originals_verified": True, "conversation_id": conversation["id"],
              "assistant_sha256": hashlib.sha256(answer.encode()).hexdigest(), "status": "recognition_failed_saved"}
    metrics = module.measure_saved_reply(database, result)
    assert metrics["measured"] and metrics["saved_reply_sha_verified"]
    assert metrics["unicode_chars"] == len(answer)
    assert metrics["requested_hanzi_range_met"] is False
    assert answer not in json.dumps(metrics, ensure_ascii=False)


def test_failed_or_corrupted_saved_reply_is_not_measured(tmp_path):
    database = module.wait.ChatDatabase(tmp_path / "synthetic-db", auto_annotations=True, annotation_mode="semantic")
    failed = module.measure_saved_reply(database, {"saved_originals_verified": False})
    assert failed["measured"] is False
    assert failed["requested_hanzi_range_met"] is None
    conversation = database.create_conversation(module.wait.SYNTHETIC_OWNER, "mock", "deepseek-flash", False)
    database.save_turn(module.wait.SYNTHETIC_OWNER, conversation["id"], "合成请求", "原文", [])
    corrupted = module.measure_saved_reply(database, {"saved_originals_verified": True,
                                                     "conversation_id": conversation["id"], "assistant_sha256": "wrong"})
    assert corrupted["measured"] is False
    assert corrupted["measurement_error"] == "saved_role_hash_mismatch"


@pytest.mark.parametrize("auth_error, expected_flows, expected_calls", [(False, 2, 4), (True, 1, 1)])
def test_execute_bounded_smoke_no_length_retry_and_recognition_failure_keeps_evidence(
        tmp_path, monkeypatch, auth_error, expected_flows, expected_calls):
    credentials = tmp_path / "credentials.synthetic"
    credentials.write_text("RAG_LLM_API_KEY=synthetic-test-key\nRAG_LLM_BASE_URL=https://api.deepseek.com\n")
    monkeypatch.setattr(module, "ROOT", tmp_path)
    monkeypatch.setattr(module, "source_hashes", dict)
    calls, events = [], []

    async def fake_flow(_client, _key, database, flow, _progress=None):
        calls.append(flow)
        if auth_error:
            return {"status": "reply_failed", "saved_originals_verified": False, "cloud_calls": 1, "auth_error": True}
        conversation = database.create_conversation(module.wait.SYNTHETIC_OWNER, flow["id"], "deepseek-flash", False)
        text = "完全虚构的短故事。"
        recognition = {"version": "semantic-v1", "roles": {"user": "failed", "assistant": "failed"},
                       "claims": {"user": [], "assistant": []}}
        database.save_turn(module.wait.SYNTHETIC_OWNER, conversation["id"], flow["case"]["query"], text, [], recognition=recognition)
        return {"status": "recognition_failed_saved", "saved_originals_verified": True, "cloud_calls": 2,
                "conversation_id": conversation["id"], "assistant_sha256": hashlib.sha256(text.encode()).hexdigest(),
                "auth_error": False, "reply": {"latency_seconds": 3}, "recognition": {"latency_seconds": 2},
                "total_wait_seconds": 5.1}

    monkeypatch.setattr(module, "run_flow", fake_flow)
    result_code = asyncio.run(module.execute(credentials, progress=events.append))
    path = next((tmp_path / "artifacts").glob("*/results.json"))
    result = json.loads(path.read_text(encoding="utf-8"))
    assert len(calls) == result["attempted_flows"] == expected_flows
    assert result["attempted_cloud_calls"] == expected_calls
    assert result_code == (1 if auth_error else 0)
    assert result["credential_file_unchanged"] is True
    assert "synthetic-test-key" not in json.dumps([result, events])
    if not auth_error:
        assert all(f["output"]["requested_hanzi_range_met"] is False for f in result["flows"])
        assert all(f["output"]["saved_reply_sha_verified"] for f in result["flows"])
        assert all(f["status"] == "recognition_failed_saved" for f in result["flows"])


def test_source_hash_includes_long_script_and_base_flow(tmp_path, monkeypatch):
    monkeypatch.setattr(module, "ROOT", tmp_path)
    monkeypatch.setattr(module.wait, "source_hashes", lambda: {"base-flow.py": "frozen-base"})
    path = tmp_path / "scripts" / "deepseek_flash_long_output.py"
    path.parent.mkdir()
    path.write_text("synthetic script source")
    values = module.source_hashes()
    assert values["base-flow.py"] == "frozen-base"
    assert values["scripts/deepseek_flash_long_output.py"] == hashlib.sha256(path.read_bytes()).hexdigest()
