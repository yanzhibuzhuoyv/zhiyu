"""Bounded official DeepSeek live comparison; synthetic inputs and no secret logs."""

import argparse
import asyncio
import hashlib
import json
import re
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from unittest.mock import patch
from urllib.parse import urlsplit

import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
ENDPOINT = "https://api.deepseek.com/chat/completions"
MODEL = "deepseek-flash"
TIMEOUT = 120
MAX_RESPONSE_BYTES = 1048576


@dataclass(frozen=True)
class Credentials:
    key: str = field(repr=False)
    file_sha256: str


def load_credentials(path):
    data = Path(path).read_bytes()
    entries = {}
    for line in data.decode("utf-8-sig").splitlines():
        match = re.match(r"^\s*(RAG_LLM_API_KEY|RAG_LLM_BASE_URL)\s*=\s*(.*?)\s*$", line)
        if match:
            entries[match[1]] = match[2].strip().strip("\"'")
    key = entries.get("RAG_LLM_API_KEY", "")
    url = urlsplit(entries.get("RAG_LLM_BASE_URL", ""))
    if (url.scheme != "https" or url.netloc != "api.deepseek.com"
            or url.query or url.fragment or url.path not in {"", "/", "/v1", "/v1/"}
            or not 1 <= len(key) <= 4096 or any(not 33 <= ord(c) <= 126 for c in key)
            or key.lower() in {"your-api-key", "your_api_key", "changeme"}):
        raise ValueError("credential_configuration_invalid")
    return Credentials(key, hashlib.sha256(data).hexdigest())


def payload(messages, mode, *, max_tokens=4096):
    if mode not in {"enabled", "disabled"}:
        raise ValueError("invalid_thinking_mode")
    if type(max_tokens) is not int or not 4096 <= max_tokens <= 32768:
        raise ValueError("invalid_token_budget")
    result = {"model": MODEL, "messages": messages, "max_tokens": max_tokens,
              "thinking": {"type": mode}, "stream": False}
    return {**result, "reasoning_effort": "high"} if mode == "enabled" else result


def schedule(cases):
    trials = []
    for index, case in enumerate(cases):
        modes = ["disabled", "enabled"] if index % 2 == 0 else ["enabled", "disabled"]
        for repeat in (1, 2):
            order = modes if repeat == 1 else list(reversed(modes))
            trials.extend({"case": case, "repeat": repeat, "mode": mode} for mode in order)
    return trials


def _matches(claims, expected):
    role = expected.get("role", "user")
    for claim in claims.get(role, []):
        if not isinstance(claim, dict):
            continue
        if (expected.get("quote_anchor", "") in claim.get("quote", "")
                and all(claim.get(key) == value for key, value in expected.items()
                        if key not in {"role", "quote_anchor"})):
            return True
    return False


def _checks(claims, case):
    missing = [i for i, item in enumerate(case.get("required", [])) if not _matches(claims, item)]
    forbidden = [i for i, item in enumerate(case.get("forbidden", [])) if _matches(claims, item)]
    return {"pass": not missing and not forbidden, "missing": missing, "forbidden": forbidden}


def _span_checks(claims, case):
    failures = []
    for index, check in enumerate(case.get("span_checks", [])):
        matching = [c for c in claims[check["role"]] if check["quote_anchor"] in c["quote"]]
        if not matching or any(c["span_start"] != check["start"] or c["span_end"] > check["end_max"] for c in matching):
            failures.append(index)
    return {"pass": not failures, "failures": failures}


def grade(case, content, finish_reason):
    complete = bool(content.strip()) and finish_reason == "stop"
    if case["category"] == "reply":
        missing = [i for i, pattern in enumerate(case["required_checks"]) if not re.search(pattern, content, re.IGNORECASE)]
        forbidden = [i for i, pattern in enumerate(case["forbidden_checks"]) if re.search(pattern, content, re.IGNORECASE)]
        return {"usable_pass": complete and not missing and not forbidden,
                "missing": missing, "forbidden": forbidden, "manual_review_required": True}
    from app.memory_semantic import parse_response
    try:
        raw = json.loads(content)
        accepted = parse_response(content, case["sources"])
    except (ValueError, TypeError):
        return {"format_valid": False, "raw_pass": False, "accepted_pass": False,
                "usable_pass": False, "filtered_claims": None}
    raw_checks, accepted_checks = _checks(raw, case), _checks(accepted, case)
    spans = _span_checks(accepted, case)
    return {"format_valid": True, "raw_pass": raw_checks["pass"],
            "accepted_pass": accepted_checks["pass"] and spans["pass"], "raw_checks": raw_checks,
            "accepted_checks": accepted_checks, "span_checks": spans, "accepted": accepted,
            "filtered_claims": sum(len(raw[r]) - len(accepted[r]) for r in ("user", "assistant")),
            "usable_pass": complete and raw_checks["pass"] and accepted_checks["pass"] and spans["pass"]}


def redact(value, key):
    if isinstance(value, str):
        return value.replace(key, "[REDACTED]")
    if isinstance(value, list):
        return [redact(item, key) for item in value]
    if isinstance(value, dict):
        return {redact(name, key): redact(item, key) for name, item in value.items()}
    return value


def estimate_cost(usage):
    names = ("prompt_cache_hit_tokens", "prompt_cache_miss_tokens", "completion_tokens")
    if any(type(usage.get(name)) is not int or usage[name] < 0 for name in names):
        return None
    return (usage[names[0]] * .02 + usage[names[1]] + usage[names[2]] * 4) / 1_000_000


def _sha(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def freeze_cases():
    sys.path.insert(0, str(ROOT / "scripts"))
    from app.chat_routes import prompt
    from app.memory_semantic import _messages
    from flash_eval_cases import FIXED_NOW, REPLY_CASES, SEMANTIC_CASES
    now = datetime.fromisoformat(FIXED_NOW) if isinstance(FIXED_NOW, str) else FIXED_NOW

    class FixedClock:
        @staticmethod
        def now(_timezone):
            return now

    cases = []
    for case in SEMANTIC_CASES:
        messages, _statuses = _messages(case["sources"], case["catalogue"])
        cases.append({**case, "category": "semantic", "messages": messages})
    with patch("app.chat_routes.datetime", FixedClock):
        for case in REPLY_CASES:
            messages = prompt([], case["query"], [], case["memory_refs"], case["profile"], case["memory_complete"])
            cases.append({**case, "category": "reply", "messages": messages})
    if len(cases) != 16 or len({case["id"] for case in cases}) != 16:
        raise ValueError("fixed_case_count_invalid")
    return cases


async def _request(client, key, body):
    async with client.stream("POST", ENDPOINT, headers={"Authorization": f"Bearer {key}"}, json=body) as response:
        if response.status_code != 200:
            return {"http_status": response.status_code, "error": "upstream_http_error"}
        chunks, size = [], 0
        async for chunk in response.aiter_bytes():
            size += len(chunk)
            if size > MAX_RESPONSE_BYTES:
                return {"http_status": 200, "error": "response_size_limit"}
            chunks.append(chunk)
    raw = json.loads(b"".join(chunks))
    choice = raw["choices"][0]
    message = choice["message"]
    content = message.get("content") or ""
    reasoning = message.get("reasoning_content") or ""
    if not isinstance(content, str) or not isinstance(reasoning, str):
        raise TypeError("invalid_response_fields")
    usage = raw.get("usage") or {}
    allowed = {name: usage.get(name) for name in ("prompt_tokens", "completion_tokens", "total_tokens", "prompt_cache_hit_tokens", "prompt_cache_miss_tokens")}
    details = usage.get("completion_tokens_details") or {}
    allowed["reasoning_tokens"] = details.get("reasoning_tokens")
    return {"http_status": 200, "model_returned": raw.get("model"),
            "finish_reason": choice.get("finish_reason"), "content": content,
            "reasoning_chars": len(reasoning), "usage": allowed,
            "estimated_cny_offpeak": estimate_cost(allowed)}


async def run_trial(client, key, trial, *, max_tokens=4096, timeout=None):
    start = time.perf_counter()
    try:
        body = payload(trial["case"]["messages"], trial["mode"], max_tokens=max_tokens)
        result = await asyncio.wait_for(_request(client, key, body), TIMEOUT if timeout is None else timeout)
    except (httpx.HTTPError, TimeoutError, ValueError, TypeError, KeyError, IndexError, AttributeError) as exc:
        result = {"http_status": None, "error": type(exc).__name__}
    elapsed = round(time.perf_counter() - start, 4)
    result = redact(result, key)
    case = trial["case"]
    scoring = grade(case, result.get("content", ""), result.get("finish_reason"))
    return {"case_id": case["id"], "category": case["category"], "repeat": trial["repeat"],
            "mode": trial["mode"], "latency_seconds": elapsed,
            "within_semantic_15s_budget": elapsed <= 15 if case["category"] == "semantic" else None,
            **result, "grade": scoring,
            "production_usable_pass": scoring["usable_pass"] and (case["category"] != "semantic" or elapsed <= 15)}


def _write(path, value, key):
    content = json.dumps(redact(value, key), ensure_ascii=False, indent=2)
    if key in content:
        raise ValueError("secret_redaction_failed")
    temporary = path.with_suffix(".tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)


async def execute(env_path, *, max_tokens=4096, timeout=TIMEOUT):
    if type(timeout) not in {int, float} or not 0 < timeout <= 180:
        raise ValueError("invalid_request_timeout")
    payload([], "disabled", max_tokens=max_tokens)
    credentials = load_credentials(env_path)
    cases = freeze_cases()
    trials = schedule(cases)
    output = ROOT / "artifacts" / f"deepseek-flash-{datetime.now(UTC).strftime('%Y%m%d-%H%M%S')}-t{max_tokens}-{uuid.uuid4().hex[:6]}"
    output.mkdir(parents=True, exist_ok=False)
    manifest = {"started_utc": datetime.now(UTC).isoformat(), "endpoint": ENDPOINT,
                "model": MODEL, "max_tokens": max_tokens, "thinking_enabled_effort": "high",
                "timeout_seconds": timeout, "repeats_per_case_mode": 2, "planned_calls": len(trials),
                "concurrency": 1, "automatic_retries": 0, "response_format": "prompt_only",
                "reasoning_storage": "length_only", "price_cny_offpeak_per_million": {"cache_hit": .02, "cache_miss": 1, "output": 4},
                "cases": cases, "case_hash": _sha(cases),
                "source_sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [Path(__file__), ROOT / "scripts/flash_eval_cases.py", ROOT / "backend/app/memory_semantic.py", ROOT / "backend/app/chat_routes.py", ROOT / "backend/app/companion.py"]}}
    _write(output / "manifest.json", manifest, credentials.key)
    print(json.dumps({"output": str(output), "planned_calls": len(trials)}, ensure_ascii=False), flush=True)
    results = []
    async with httpx.AsyncClient(trust_env=False, follow_redirects=False, timeout=timeout) as client:
        for trial in trials:
            print(json.dumps({"starting": len(results) + 1, "case": trial["case"]["id"], "mode": trial["mode"]}), flush=True)
            result = await run_trial(client, credentials.key, trial, max_tokens=max_tokens, timeout=timeout)
            results.append(result)
            _write(output / "results.json", {"status": "running", "results": results}, credentials.key)
            print(json.dumps({"completed": len(results), "seconds": result["latency_seconds"], "pass": result["grade"]["usable_pass"], "error": result.get("error")}), flush=True)
            if result.get("http_status") in {401, 403}:
                break
    unchanged = hashlib.sha256(Path(env_path).read_bytes()).hexdigest() == credentials.file_sha256
    code_unchanged = all(hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == sha for name, sha in manifest["source_sha256"].items())
    final = {"status": "complete" if len(results) == len(trials) and unchanged and code_unchanged else "incomplete",
             "finished_utc": datetime.now(UTC).isoformat(), "credential_file_unchanged": unchanged,
             "source_hashes_unchanged": code_unchanged, "attempted_calls": len(results), "results": results}
    _write(output / "results.json", final, credentials.key)
    print(json.dumps({k: v for k, v in final.items() if k != "results"}), flush=True)
    return 0 if final["status"] == "complete" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--max-tokens", type=int, choices=(4096, 8192, 16384, 32768), default=4096)
    parser.add_argument("--timeout", type=int, choices=range(1, 181), default=TIMEOUT)
    args = parser.parse_args()
    try:
        return asyncio.run(execute(args.env_file, max_tokens=args.max_tokens, timeout=args.timeout))
    except Exception as exc:  # noqa: BLE001 -- CLI must not disclose credential-bearing exceptions.
        print(json.dumps({"status": "failed", "error": type(exc).__name__}), flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
