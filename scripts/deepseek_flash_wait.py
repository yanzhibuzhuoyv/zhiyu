"""Bounded production-path simulation over new, synthetic SQLite conversations.

Calls the official reply and recognition phases sequentially, then the production
save_turn implementation. This excludes phone networking/rendering and does not
change the production 15-second recognition timeout. Generated annotations have
no accuracy gold: report strict format/provenance validity and waiting time only.
"""

import argparse
import asyncio
import hashlib
import json
import sqlite3
import sys
import time
import uuid
from datetime import UTC, datetime
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent))
import deepseek_flash_eval as safe_runner
from app.database import ChatDatabase
from app.memory_semantic import (
    SEMANTIC_VERSION,
    SemanticResponseError,
    _messages,
    parse_response,
)
from app.source_codec import ChatStorageError
from deepseek_flash_eval import freeze_cases, load_credentials

ROOT = Path(__file__).resolve().parents[1]
SYNTHETIC_OWNER = "flash-wait-public-synthetic-owner"
MAX_TOKENS = 393216
NETWORK_TIMEOUT = httpx.Timeout(connect=30, write=30, pool=30, read=None)
COMBINATIONS = (("disabled", "disabled"), ("enabled", "enabled"), ("disabled", "enabled"))


def schedule_flows(cases):
    if (len(cases) != 4 or len({c["id"] for c in cases}) != 4
            or any(c["category"] != "reply" for c in cases)):
        raise ValueError("reply_case_count_invalid")
    flows = []
    for index, case in enumerate(cases):
        offset = index % len(COMBINATIONS)
        order = COMBINATIONS[offset:] + COMBINATIONS[:offset]
        for repeat in (1, 2):
            for reply_mode, recognition_mode in order if repeat == 1 else reversed(order):
                flows.append({"id": f"W{len(flows) + 1:02d}", "case": case, "repeat": repeat,
                              "reply_mode": reply_mode, "recognition_mode": recognition_mode})
    return flows


def _emit(progress, event):
    if progress:
        progress(event)
    else:
        print(json.dumps(event, ensure_ascii=False), flush=True)


def _hash(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _complete(phase):
    return (phase.get("http_status") == 200 and not phase.get("error")
            and phase.get("done_received") is True
            and phase.get("finish_reason") == "stop"
            and isinstance(phase.get("content"), str) and bool(phase["content"].strip()))


def _phase_metadata(result):
    names = ("http_status", "finish_reason", "latency_seconds", "usage", "model_returned",
             "reasoning_chars", "estimated_cny_offpeak", "error", "done_received",
             "first_reasoning_seconds", "first_content_seconds")
    return {**{name: result.get(name) for name in names},
            "rubric_pass": result.get("grade", {}).get("usable_pass", False)}


async def run_stream_trial(client, key, trial):
    from deepseek_flash_stream import run_stream_trial as stream_trial
    return await stream_trial(client, key, trial)


async def _phase(client, key, flow, case, mode, phase, progress):
    _emit(progress, {"event": "starting", "flow_id": flow["id"], "phase": phase, "mode": mode})
    trial = {"case": case, "repeat": flow["repeat"], "mode": mode}
    result = await run_stream_trial(client, key, trial)
    result = safe_runner.redact(result, key)
    _emit(progress, {"event": "completed", "flow_id": flow["id"], "phase": phase,
                     "seconds": result.get("latency_seconds"), "http_status": result.get("http_status")})
    return result


def _recognition(result, sources, statuses):
    try:
        if not _complete(result):
            raise SemanticResponseError("recognition_incomplete")
        claims = parse_response(result["content"], sources)
    except SemanticResponseError:
        return {"status": "failed", "roles": {r: "failed" if s == "ok" else s for r, s in statuses.items()},
                "claims": {"user": [], "assistant": []}, "version": SEMANTIC_VERSION}
    return {"status": "partial" if "skipped_large" in statuses.values() else "ok",
            "roles": statuses, "claims": claims, "version": SEMANTIC_VERSION}


def _saved_hashes(messages):
    return [{"role": m["role"], "sha256": _hash(m["content"])} for m in messages]


def _save(database, flow, sources, recognition):
    conversation = database.create_conversation(SYNTHETIC_OWNER, flow["id"], safe_runner.MODEL,
                                                False, flow["case"].get("profile"))
    expected = [{"role": role, "sha256": _hash(sources[role]["content"])} for role in ("user", "assistant")]
    started = time.perf_counter()
    saved = database.save_turn(SYNTHETIC_OWNER, conversation["id"], sources["user"]["content"],
                               sources["assistant"]["content"], [], recognition=recognition)
    save_seconds, save_finished = time.perf_counter() - started, time.perf_counter()
    returned = _saved_hashes([saved["user_message"], saved["assistant_message"]])
    check_started = time.perf_counter()
    stored = _saved_hashes(database.list_messages(SYNTHETIC_OWNER, conversation["id"]))
    return {"conversation_id": conversation["id"], "save_turn_seconds": round(save_seconds, 6),
            "save_return_roles_verified": returned == expected,
            "saved_originals_verified": stored == expected, "saved_hashes": stored,
            "verification_seconds": round(time.perf_counter() - check_started, 6),
            "save_finished_monotonic": save_finished}


async def _recognize_and_save(client, key, database, flow, sources, progress):
    messages, statuses = _messages(sources, ())
    case = {"id": f"{flow['case']['id']}-actual-turn", "category": "semantic",
            "sources": sources, "messages": messages, "required": [], "forbidden": []}
    result = await _phase(client, key, flow, case, flow["recognition_mode"], "recognition", progress)
    recognition = _recognition(result, sources, statuses)
    valid = recognition["status"] in {"ok", "partial"}
    _emit(progress, {"event": "starting", "flow_id": flow["id"], "phase": "save_turn"})
    try:
        saved = await asyncio.to_thread(_save, database, flow, sources, recognition)
    except (ValueError, TypeError, KeyError, sqlite3.Error, ChatStorageError) as exc:
        saved = {"save_return_roles_verified": False, "saved_originals_verified": False,
                 "storage_error": type(exc).__name__}
    _emit(progress, {"event": "completed", "flow_id": flow["id"], "phase": "save_turn",
                     "verified": saved["saved_originals_verified"]})
    return {"recognition": _phase_metadata(result), "recognition_format_and_source_pass": valid,
            "recognition_status": recognition["status"],
            "recognition_role_statuses": recognition["roles"],
            "accepted_claim_counts": {r: len(v) for r, v in recognition["claims"].items()},
            "auth_error": result.get("http_status") in {401, 403}, **saved}


async def run_flow(client, key, database, flow, progress=None):
    started, user_created = time.perf_counter(), datetime.now(UTC).isoformat()
    reply = await _phase(client, key, flow, flow["case"], flow["reply_mode"], "reply", progress)
    result = {"flow_id": flow["id"], "case_id": flow["case"]["id"], "repeat": flow["repeat"],
              "reply_mode": flow["reply_mode"], "recognition_mode": flow["recognition_mode"],
              "reply": _phase_metadata(reply), "cloud_calls": 1,
              "user_sha256": _hash(flow["case"]["query"])}
    if not _complete(reply):
        return {**result, "status": "reply_failed", "recognition": None,
                "recognition_format_and_source_pass": False, "saved_originals_verified": False,
                "total_wait_seconds": round(time.perf_counter() - started, 6),
                "auth_error": reply.get("http_status") in {401, 403}}
    sources = {"user": {"content": flow["case"]["query"], "created_at": user_created},
               "assistant": {"content": reply["content"], "created_at": datetime.now(UTC).isoformat()}}
    storage = await _recognize_and_save(client, key, database, flow, sources, progress)
    finished = storage.pop("save_finished_monotonic", time.perf_counter())
    verified = storage["saved_originals_verified"] and storage.get("save_return_roles_verified", False)
    status = "complete" if storage["recognition_format_and_source_pass"] else "recognition_failed_saved"
    return {**result, **storage, "status": status if verified else "storage_failed",
            "cloud_calls": 2, "assistant_sha256": _hash(reply["content"]),
            "total_wait_seconds": round(finished - started, 6)}


def source_hashes():
    files = ("scripts/deepseek_flash_wait.py", "scripts/deepseek_flash_eval.py", "scripts/flash_eval_cases.py",
             "scripts/deepseek_flash_stream.py",
             "backend/app/database.py", "backend/app/memory_semantic.py", "backend/app/chat_routes.py",
             "backend/app/companion.py")
    return {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in files}


def _new_output():
    name = f"deepseek-flash-wait-{datetime.now(UTC).strftime('%Y%m%d-%H%M%S')}-t{MAX_TOKENS}-{uuid.uuid4().hex[:6]}"
    output = ROOT / "artifacts" / name
    output.mkdir(parents=True, exist_ok=False)
    data_dir = output / "synthetic-db"
    data_dir.mkdir(exist_ok=False)
    return output, data_dir


def _manifest(cases, flows):
    return {"started_utc": datetime.now(UTC).isoformat(), "endpoint": safe_runner.ENDPOINT,
            "model": safe_runner.MODEL, "max_tokens": MAX_TOKENS, "stream": True, "include_usage": True,
            "overall_request_deadline_seconds": None,
            "network_timeouts_seconds": {"connect": 30, "write": 30, "pool": 30, "read": None},
            "planned_flows": len(flows), "maximum_cloud_calls": len(flows) * 2,
            "concurrency": 1, "automatic_retries": 0, "synthetic_owner": SYNTHETIC_OWNER,
            "scope": "official_calls_plus_production_save_turn_excludes_phone_network_and_rendering",
            "reasoning_storage": "length_only", "original_storage": "new_synthetic_sqlite_only",
            "annotation_metrics": "strict_format_and_source_only_no_accuracy_gold",
            "production_timeout_changed": False, "source_sha256": source_hashes(),
            "case_ids": [c["id"] for c in cases], "prompt_hashes": {c["id"]: safe_runner._sha(c["messages"]) for c in cases},
            "schedule": [{k: v for k, v in f.items() if k != "case"} | {"case_id": f["case"]["id"]} for f in flows]}


async def execute(env_path, *, progress=None):
    credentials = load_credentials(env_path)
    cases = [c for c in freeze_cases() if c["category"] == "reply"]
    flows = schedule_flows(cases)
    output, data_dir = _new_output()
    manifest = _manifest(cases, flows)
    safe_runner._write(output / "manifest.json", manifest, credentials.key)
    database = ChatDatabase(data_dir, auto_annotations=True, annotation_mode="semantic")
    _emit(progress, {"event": "created", "output": str(output), "planned_flows": len(flows), "maximum_cloud_calls": 48})
    results = []
    async with httpx.AsyncClient(trust_env=False, follow_redirects=False, timeout=NETWORK_TIMEOUT) as client:
        for flow in flows:
            result = await run_flow(client, credentials.key, database, flow, progress)
            results.append(result)
            safe_runner._write(output / "results.json", {"status": "running", "flows": results}, credentials.key)
            if result.get("auth_error"):
                break
    unchanged = hashlib.sha256(Path(env_path).read_bytes()).hexdigest() == credentials.file_sha256
    code_unchanged = source_hashes() == manifest["source_sha256"]
    final = {"status": "complete" if len(results) == 24 and unchanged and code_unchanged else "incomplete",
             "finished_utc": datetime.now(UTC).isoformat(), "credential_file_unchanged": unchanged,
             "source_hashes_unchanged": code_unchanged, "attempted_flows": len(results),
             "attempted_cloud_calls": sum(r["cloud_calls"] for r in results), "flows": results}
    safe_runner._write(output / "results.json", final, credentials.key)
    _emit(progress, {k: v for k, v in final.items() if k != "flows"})
    return 0 if final["status"] == "complete" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", required=True, type=Path)
    args = parser.parse_args()
    try:
        return asyncio.run(execute(args.env_file))
    except Exception as exc:  # noqa: BLE001 -- Never print credential-bearing exception messages.
        print(json.dumps({"status": "failed", "error": type(exc).__name__}), flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
