"""One sample per version/mode: full final answers with identical synthetic evidence."""

import argparse
import asyncio
import hashlib
import json
import re
import sys
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path
from unittest.mock import patch

import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
sys.path.insert(0, str(ROOT / "scripts"))

from deepseek_flash_eval import MODEL, load_credentials  # noqa: E402
from deepseek_flash_stream import MAX_TOKENS, run_stream_trial  # noqa: E402

from app.chat_routes import prompt  # noqa: E402
from app.schemas import CompanionProfile  # noqa: E402

BANK = ROOT / "scripts/companion_preset_cases.json"
CLOCK = datetime.fromisoformat("2026-10-07T04:00:00+00:00")
STYLES = ("gentle", "warm_v1")
FROZEN = ("backend/app/companion.py", "backend/app/companion_presets.py",
          "backend/app/schemas.py", "backend/app/chat_routes.py", "scripts/deepseek_flash_eval.py",
          "scripts/deepseek_flash_stream.py", "scripts/observe_companion_preset.py",
          "scripts/companion_preset_cases.json", "docs/COMPANION_PRESET_EVAL_PROTOCOL.md")


class FrozenClock:
    @staticmethod
    def now(tz):
        return CLOCK.astimezone(tz)


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_cases(path=BANK):
    value = json.loads(path.read_text(encoding="utf-8"))
    cases = value["cases"]
    if (value["version"] != "companion-preset-independent-v1" or len(cases) != 20
            or len({c["id"] for c in cases}) != 20
            or Counter(c["category"] for c in cases) != {"daily": 5, "memory": 6, "uncertain": 4, "persona": 5}):
        raise ValueError("invalid_case_bank")
    for case in cases:
        if not isinstance(case["query"], str) or not 1 <= len(case["query"]) <= 16000:
            raise ValueError("invalid_query")
        if len(case["recent"]) > 2 or len(case["sources"]) > 6:
            raise ValueError("invalid_case_size")
        for source in case["sources"]:
            when = datetime.fromisoformat(source["created_at"])
            if (source["author"] not in {"user", "assistant"} or when.tzinfo is None
                    or when >= CLOCK or not 1 <= len(source["text"]) <= 16000):
                raise ValueError("invalid_source")
    return cases


def schedule(cases):
    trials = []
    for index, case in enumerate(cases):
        styles = STYLES if index % 2 == 0 else tuple(reversed(STYLES))
        modes = ("disabled", "enabled") if index % 2 == 0 else ("enabled", "disabled")
        trials.extend({"case_id": case["id"], "style": style, "mode": mode}
                      for mode in modes for style in styles)
    return trials


def messages_for(case, style):
    selected = CompanionProfile(mode="preset", style=style, name="小语").model_dump()
    refs = [{"message_id": s["source_id"], "role": s["author"], "text": s["text"],
             "created_at": s["created_at"], "truncated": False} for s in case["sources"]]
    with patch("app.chat_routes.datetime", FrozenClock):
        return prompt(case["recent"], case["query"], [], refs, selected)


def technical(result):
    return bool(result.get("http_status") == 200 and result.get("done_received")
                and result.get("finish_reason") == "stop" and result.get("content", "").strip()
                and not result.get("error"))


def write_report(output, report, key):
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if key in text:
        raise ValueError("secret_in_report")
    temporary = output / "results.pending.json"
    temporary.write_text(text + "\n", encoding="utf-8")
    temporary.replace(output / "results.json")


async def one(client, key, case, trial):
    messages = messages_for(case, trial["style"])
    result = await run_stream_trial(client, key, {
        "case": {"id": case["id"], "category": "reply", "messages": messages,
                 "required_checks": [], "forbidden_checks": []}, "mode": trial["mode"], "repeat": 1})
    return {**trial, "category": case["category"], "messages": messages,
            "prompt_sha256": digest(messages), "evidence_sha256": digest(case),
            "result": result, "technical_complete": technical(result),
            "han_count": len(re.findall(r"[\u3400-\u9fff]", result["content"])),
            "manual_semantic_review_required": True}


def verify_frozen(hashes, credentials, credential_path):
    if any(file_hash(ROOT / p) != sha for p, sha in hashes.items()):
        raise RuntimeError("frozen_source_changed")
    if file_hash(credential_path) != credentials.file_sha256:
        raise RuntimeError("credential_file_changed")


async def run(args):
    bank = load_cases()
    credentials = load_credentials(args.credentials)
    args.output.mkdir(parents=True, exist_ok=False)
    hashes = {p: file_hash(ROOT / p) for p in FROZEN}
    trials = schedule(bank)
    report = {"complete": False, "started_utc": datetime.now(UTC).isoformat(), "cases": bank,
              "frozen": hashes, "schedule": trials, "model": MODEL, "max_tokens": MAX_TOKENS,
              "clock": CLOCK.isoformat(), "credential_file_sha256": credentials.file_sha256,
              "read_deadline": None, "trials": [], "production_token_budget": 2048,
              "meaning": "Controlled evidence, AI semantic review needed; no retrieval-order comparison."}
    write_report(args.output, report, credentials.key)
    cases = {c["id"]: c for c in bank}
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(None, connect=15, write=30, pool=30),
                                     trust_env=False, follow_redirects=False) as client:
            for index, trial in enumerate(trials):
                verify_frozen(hashes, credentials, args.credentials)
                row = await one(client, credentials.key, cases[trial["case_id"]], trial)
                report["trials"].append(row)
                write_report(args.output, report, credentials.key)
                print(json.dumps({"index": index, **trial, "complete": row["technical_complete"]}), flush=True)
        verify_frozen(hashes, credentials, args.credentials)
        report.update(complete=True, finished_utc=datetime.now(UTC).isoformat())
    except BaseException as error:
        report["error_type"] = type(error).__name__
        write_report(args.output, report, credentials.key)
        raise
    write_report(args.output, report, credentials.key)
    return all(row["technical_complete"] for row in report["trials"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--credentials", type=Path, default=ROOT.parent / "rag-system/.env")
    args = parser.parse_args()
    try:
        ok = asyncio.run(run(args))
    except BaseException as error:
        print(json.dumps({"error_type": type(error).__name__}), flush=True)
        raise SystemExit(1) from None
    raise SystemExit(0 if ok else 2)


if __name__ == "__main__":
    main()
