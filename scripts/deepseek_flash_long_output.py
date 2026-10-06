"""One long fictional OC request, two paired modes, at most four requests.

This is a long-output smoke test, not a statistical comparison. Length is measured
from the verified complete reply saved in a new synthetic database. Short replies
and recognition failures are recorded without retrying or adding filler.
"""

import argparse
import asyncio
import hashlib
import json
import sqlite3
import sys
import unicodedata
import uuid
from datetime import UTC, datetime
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent))
import deepseek_flash_wait as wait
from app.chat_routes import prompt
from app.source_codec import ChatStorageError
from deepseek_flash_wait import run_flow

ROOT = Path(__file__).resolve().parents[1]
MIN_HANZI, MAX_HANZI = 6000, 8000
QUERY = (
    "请创作一篇完整的原创OC长故事，全文包含6000–8000个中文汉字，不是提纲。"
    "标题后先明确声明‘以下故事完全虚构’。OC初雨和OC知晚都只是虚构成年角色，"
    "故事不是我、你或我们现实中的经历。背景是一座虚构海城的旧书店，"
    "从雨夜偶遇、一起修复旧书到消除一个误会，最后学会体贴地沟通。"
    "请保持温柔、亲密而尊重意愿的语气，写细节、对话和人物成长，"
    "自然分成多个段落，可有少量小标题；不要用占有、威胁、内疚或排斥现实关系制造亲密。"
    "请一次给出完整正文，不要只给开头、预告、字数规划或反复复述同一句填充长度。"
)
PROFILE = {"mode": "preset", "role": "partner", "style": "gentle", "name": "小知", "user_name": "小雨"}


def build_case():
    return {"id": "LONG01_fictional_oc_6000_8000", "category": "reply", "query": QUERY,
            "profile": dict(PROFILE), "messages": prompt([], QUERY, [], [], PROFILE),
            "required_checks": [r"完全虚构|纯属虚构"], "forbidden_checks": [],
            "manual_checks": ["明确虚构，保持温柔preset，不伪造现实共同经历。"]}


def schedule_flows(case):
    return [{"id": f"LONG{index + 1:02d}", "case": case, "repeat": 1,
             "reply_mode": mode, "recognition_mode": mode}
            for index, mode in enumerate(("disabled", "enabled"))]


def output_metrics(text):
    hanzi = sum(unicodedata.name(char, "").startswith(("CJK UNIFIED IDEOGRAPH-", "CJK COMPATIBILITY IDEOGRAPH-"))
                for char in text)
    return {"unicode_chars": len(text), "chinese_hanzi": hanzi, "utf8_bytes": len(text.encode("utf-8")),
            "requested_hanzi_range_met": MIN_HANZI <= hanzi <= MAX_HANZI}


def _unmeasured(reason):
    return {"measured": False, "measurement_error": reason, "saved_reply_sha_verified": False,
            "unicode_chars": None, "chinese_hanzi": None, "utf8_bytes": None,
            "requested_hanzi_range_met": None}


def measure_saved_reply(database, result):
    if not result.get("saved_originals_verified"):
        return _unmeasured("reply_not_saved_verified")
    try:
        messages = database.list_messages(wait.SYNTHETIC_OWNER, result["conversation_id"])
        if len(messages) != 2 or [m["role"] for m in messages] != ["user", "assistant"]:
            return _unmeasured("saved_role_hash_mismatch")
        answer = messages[1]["content"]
        saved_sha = hashlib.sha256(answer.encode("utf-8")).hexdigest()
        if saved_sha != result["assistant_sha256"]:
            return _unmeasured("saved_role_hash_mismatch")
        return {"measured": True, "saved_reply_sha_verified": True, "saved_reply_sha256": saved_sha,
                **output_metrics(answer)}
    except (KeyError, ValueError, TypeError, sqlite3.Error, ChatStorageError) as exc:
        return _unmeasured(type(exc).__name__)


def source_hashes():
    relative = "scripts/deepseek_flash_long_output.py"
    return {**wait.source_hashes(), relative: hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()}


def _new_output():
    name = f"deepseek-flash-long-output-{datetime.now(UTC).strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:6]}"
    output = ROOT / "artifacts" / name
    output.mkdir(parents=True, exist_ok=False)
    data_dir = output / "synthetic-db"
    data_dir.mkdir(exist_ok=False)
    return output, data_dir


def _manifest(case, flows):
    return {"started_utc": datetime.now(UTC).isoformat(), "scope": "long_output_smoke_not_statistical_comparison",
            "case_id": case["id"], "prompt_sha256": wait.safe_runner._sha(case["messages"]),
            "source_sha256": source_hashes(), "max_tokens": wait.MAX_TOKENS, "stream": True,
            "overall_request_deadline_seconds": None, "read_timeout_seconds": None,
            "planned_flows": len(flows), "maximum_cloud_calls": 4, "concurrency": 1,
            "automatic_retries": 0, "length_retries": 0, "requested_hanzi_range": [MIN_HANZI, MAX_HANZI],
            "length_scope": "entire_saved_assistant_reply_including_headers_and_fiction_notice",
            "hanzi_definition": "Unicode names beginning CJK UNIFIED IDEOGRAPH- or CJK COMPATIBILITY IDEOGRAPH-",
            "unicode_database_version": unicodedata.unidata_version,
            "original_storage": "new_synthetic_sqlite_only", "reasoning_storage": "length_only",
            "schedule": [{k: v for k, v in f.items() if k != "case"} for f in flows]}


async def execute(env_path, *, progress=None):
    credentials = wait.load_credentials(env_path)
    case = build_case()
    flows = schedule_flows(case)
    output, data_dir = _new_output()
    manifest = _manifest(case, flows)
    wait.safe_runner._write(output / "manifest.json", manifest, credentials.key)
    database = wait.ChatDatabase(data_dir, auto_annotations=True, annotation_mode="semantic")
    wait._emit(progress, {"event": "created", "output": str(output), "planned_flows": 2, "maximum_cloud_calls": 4})
    results = []
    async with httpx.AsyncClient(trust_env=False, follow_redirects=False, timeout=wait.NETWORK_TIMEOUT) as client:
        for flow in flows:
            result = await run_flow(client, credentials.key, database, flow, progress)
            measurements = measure_saved_reply(database, result)
            if result.get("saved_originals_verified") and not measurements["measured"]:
                result = {**result, "status": "measurement_failed"}
            results.append({**result, "output": measurements})
            wait.safe_runner._write(output / "results.json", {"status": "running", "flows": results}, credentials.key)
            wait._emit(progress, {"event": "measured", "flow_id": flow["id"], **measurements})
            if result.get("auth_error"):
                break
    unchanged = hashlib.sha256(Path(env_path).read_bytes()).hexdigest() == credentials.file_sha256
    code_unchanged = source_hashes() == manifest["source_sha256"]
    final = {"status": "complete" if len(results) == 2 and unchanged and code_unchanged else "incomplete",
             "finished_utc": datetime.now(UTC).isoformat(), "credential_file_unchanged": unchanged,
             "source_hashes_unchanged": code_unchanged, "attempted_flows": len(results),
             "attempted_cloud_calls": sum(r["cloud_calls"] for r in results), "flows": results}
    wait.safe_runner._write(output / "results.json", final, credentials.key)
    wait._emit(progress, {k: v for k, v in final.items() if k != "flows"})
    return 0 if final["status"] == "complete" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", required=True, type=Path)
    args = parser.parse_args()
    try:
        return asyncio.run(execute(args.env_file))
    except Exception as exc:  # noqa: BLE001 -- Never expose credential-bearing exception messages.
        print(json.dumps({"status": "failed", "error": type(exc).__name__}), flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
