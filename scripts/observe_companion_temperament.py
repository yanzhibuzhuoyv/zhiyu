"""Two continuous synthetic conversations: stable temperament, temporary concessions."""

import argparse
import asyncio
import json
import sys
from datetime import UTC, datetime
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts import observe_companion_preset as live  # noqa: E402

ROOT = live.ROOT
BANK = ROOT / "scripts/companion_temperament_cases.json"
FROZEN = (*live.FROZEN, "scripts/companion_temperament_cases.json",
          "scripts/observe_companion_temperament.py", "docs/COMPANION_TEMPERAMENT_PROTOCOL.md")


def load_steps():
    value = json.loads(BANK.read_text(encoding="utf-8"))
    if len(value["steps"]) != 4 or len({s["id"] for s in value["steps"]}) != 4:
        raise ValueError("invalid_continuous_steps")
    if datetime.fromisoformat(value["clock"]) != live.CLOCK:
        raise ValueError("invalid_clock")
    return value


async def conversation(client, key, mode, bank, output, report):
    recent = []
    for step in bank["steps"]:
        case = {**step, "category": "temperament", "recent": recent, "sources": []}
        trial = {"case_id": step["id"], "mode": mode, "style": "warm_v2"}
        row = await live.one(client, key, case, trial)
        report["trials"].append(row)
        live.write_report(output, report, key)
        print(json.dumps({**trial, "complete": row["technical_complete"]}), flush=True)
        if not row["technical_complete"]:
            raise RuntimeError("continuous_response_unavailable")
        recent = [*recent, {"role": "user", "content": step["query"]},
                  {"role": "assistant", "content": row["result"]["content"]}]


async def run(args):
    bank = load_steps()
    credentials = live.load_credentials(args.credentials)
    args.output.mkdir(parents=True, exist_ok=False)
    hashes = {p: live.file_hash(ROOT / p) for p in FROZEN}
    report = {"complete": False, "bank": bank, "frozen": hashes, "trials": [],
              "model": live.MODEL, "max_tokens": live.MAX_TOKENS, "read_deadline": None,
              "started_utc": datetime.now(UTC).isoformat(), "preset": "warm_v2",
              "credential_file_sha256": credentials.file_sha256,
              "meaning": "8 targeted continuous replies; not a rerun or general proof of the old 80 cases."}
    live.write_report(args.output, report, credentials.key)
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(None, connect=15, write=30, pool=30),
                                     trust_env=False, follow_redirects=False) as client:
            for mode in ("disabled", "enabled"):
                live.verify_frozen(hashes, credentials, args.credentials)
                await conversation(client, credentials.key, mode, bank, args.output, report)
        live.verify_frozen(hashes, credentials, args.credentials)
        report.update(complete=True, finished_utc=datetime.now(UTC).isoformat())
    except BaseException as error:
        report["error_type"] = type(error).__name__
        live.write_report(args.output, report, credentials.key)
        raise
    live.write_report(args.output, report, credentials.key)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--credentials", type=Path, default=ROOT.parent / "rag-system/.env")
    args = parser.parse_args()
    try:
        asyncio.run(run(args))
    except BaseException as error:
        print(json.dumps({"error_type": type(error).__name__}))
        raise SystemExit(1) from None
