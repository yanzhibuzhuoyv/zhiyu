"""Frozen 2×2 single-call experiment; no generated reply mutates histories."""

import argparse
import asyncio
import json
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from unittest.mock import patch

from scripts.eval_memory_retrieval_ab import DEFAULT_MODEL, ORDERS, distractors, verify_refs
from scripts.eval_memory_route import ROOT, file_hash, model_hash, write
from scripts.evidence_reply import parse_reply, structured_messages
from scripts.evidence_reply_cases import build_cases
from scripts.memory_retrieval_ab import ResearchEncoder, RetrievalIndex, original_digest
from scripts.observe_answer_guard_ab import availability, trial_case
from scripts.observe_memory_retrieval_ab import EXTENDED, prompt_for
from scripts.observe_memory_route_live import write_report

sys.path.insert(0, str(ROOT / "scripts"))
from deepseek_flash_eval import load_credentials  # noqa: E402
from deepseek_flash_stream import run_stream_trial  # noqa: E402


class FormatClient:
    """Reuse the frozen SSE adapter, adding JSON only for the candidate arm."""

    def __init__(self, client, structured):
        self.client, self.structured = client, structured

    def stream(self, method, url, **kwargs):
        body = kwargs["json"]
        if self.structured:
            body = {**body, "response_format": {"type": "json_object"}}
        return self.client.stream(method, url, **{**kwargs, "json": body})


def group(case, i, extended):
    orders = ORDERS[:2] if i % 2 == 0 else tuple(reversed(ORDERS[:2]))
    result = []
    for j, order in enumerate(orders):
        methods = ("baseline", "structured") if (i + j) % 2 == 0 else ("structured", "baseline")
        result.extend(
            {
                "case_id": case["id"],
                "order": order,
                "method": method,
                "mode": "enabled" if extended else "disabled",
                "extended": extended,
            }
            for method in methods
        )
    return result


def schedule(bank):
    trials = [t for i, case in enumerate(bank) for t in group(case, i, False)]
    if not bank:
        return trials
    longest = max(bank, key=lambda c: max(len(m["text"]) for m in c["history"]))
    categories = list(dict.fromkeys(c["category"] for c in bank))
    selected = [
        longest,
        *[
            next(c for c in bank if c["category"] == cat)
            for cat in categories
            if cat != longest["category"]
        ],
    ]
    return [*trials, *[t for i, case in enumerate(selected) for t in group(case, i, True)]]


def prepare(output, model):
    output.mkdir(parents=True, exist_ok=False)
    bank = build_cases()
    paths = sorted((ROOT / "scripts").glob("*.py")) + sorted((ROOT / "backend/app").rglob("*.py"))
    paths += [
        ROOT / "docs/EVIDENCE_REPLY_AB_PROTOCOL.md",
        ROOT / "scripts/evidence_reply_cases.json",
    ]
    paths += sorted((ROOT / "scripts/tests").glob("*evidence_reply*.py"))
    hashes = {p.relative_to(ROOT).as_posix(): file_hash(p) for p in paths}
    for path in paths:
        target = output / "frozen-source" / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(path.read_bytes())
    write(output / "frozen-inputs.json", bank)
    identity, files = model_hash(model)
    write(
        output / "manifest.json",
        {
            "sources": hashes,
            "model_sha256": identity,
            "model_files": files,
            "case_sha256": file_hash(output / "frozen-inputs.json"),
            "started_utc": datetime.now(UTC).isoformat(),
        },
    )
    return bank, hashes


def messages_for(case, query, refs, method):
    frozen_clock = datetime.fromisoformat(case["current_utc"])
    with patch("scripts.observe_memory_retrieval_ab.FIXED_UTC", frozen_clock):
        base = prompt_for(query, refs)
    return structured_messages(base, refs, case["current_utc"]) if method == "structured" else base


def presentation(result, refs, method):
    if not result["grade"]["usable_pass"]:
        finished = (
            result.get("http_status") == 200
            and result.get("done_received")
            and result.get("finish_reason") == "stop"
            and not result.get("error")
        )
        status = (
            "upstream_empty_content"
            if finished and not result["content"].strip()
            else "transport_incomplete"
        )
        return {"rendered": None, "status": status, "citations": []}
    if method == "baseline":
        return {"rendered": result["content"], "status": "free_answer", "citations": []}
    try:
        parsed = parse_reply(result["content"], refs)
    except ValueError as exc:
        return {"rendered": None, "status": "invalid_structure", "error": str(exc), "citations": []}
    return {**parsed, "status": "structure_valid_semantics_unverified"}


async def call_one(client, key, case, trial, index):
    start = time.perf_counter()
    retrieval = index.retrieve(case["query"], trial["order"], scope=case["id"])
    verify_refs([m.__dict__ for m in index.originals], retrieval["references"])
    query = case["query"] + ("\n" + EXTENDED if trial["extended"] else "")
    messages = messages_for(case, query, retrieval["references"], trial["method"])
    result = await run_stream_trial(
        FormatClient(client, trial["method"] == "structured"),
        key,
        {"case": trial_case(case["id"], messages), "mode": trial["mode"], "repeat": 1},
    )
    shown = presentation(result, retrieval["references"], trial["method"])
    return {
        **trial,
        "category": case["category"],
        "query": query,
        "search_query": case["query"],
        "retrieval": retrieval,
        "messages": messages,
        "prompt_sha256": original_digest(messages),
        "history_sha256": original_digest([m.__dict__ for m in index.originals]),
        **availability(case, retrieval["references"]),
        "result": result,
        **shown,
        "total_wait_seconds": time.perf_counter() - start,
        "manual_review_required": True,
    }


def finish(args, credentials, hashes, report):
    if file_hash(args.credentials) != credentials.file_sha256:
        raise RuntimeError("测试中凭据文件发生变化")
    if any(file_hash(ROOT / p) != h for p, h in hashes.items()):
        raise RuntimeError("测试中冻结代码发生变化")
    report.update(
        complete=True,
        sources_unchanged=True,
        credentials_unchanged=True,
        returned_calls=len(report["trials"]),
    )
    write_report(args.output, report, credentials.key)


async def execute(args, bank, credentials, trials, hashes):
    import httpx

    start = time.perf_counter()
    encoder = ResearchEncoder(args.model)
    report = {
        "trials": [],
        "complete": False,
        "model_load_seconds": time.perf_counter() - start,
        "meaning": "Single-call synthetic research; structure/source validity is not semantic correctness.",
    }
    cases = {c["id"]: c for c in bank}
    timeout = httpx.Timeout(None, connect=15, write=30, pool=30)
    async with httpx.AsyncClient(
        timeout=timeout, trust_env=False, follow_redirects=False
    ) as client:
        current, index = None, None
        try:
            for trial in trials:
                case = cases[trial["case_id"]]
                if current != case["id"]:
                    if index is not None:
                        index.db.close()
                    index = RetrievalIndex(
                        distractors(16, "noise") + case["history"], encoder, scope=case["id"]
                    )
                    current = case["id"]
                row = await call_one(client, credentials.key, case, trial, index)
                report["trials"].append(row)
                write_report(args.output, report, credentials.key)
                print(
                    json.dumps(
                        {k: row[k] for k in ("case_id", "order", "method", "extended", "status")}
                    ),
                    flush=True,
                )
        finally:
            if index is not None:
                index.db.close()
    finish(args, credentials, hashes, report)
    return all(r["rendered"] is not None for r in report["trials"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--credentials", type=Path, default=ROOT.parent / "rag-system/.env")
    args = parser.parse_args()
    credentials = load_credentials(args.credentials)
    bank, hashes = prepare(args.output, args.model)
    trials = schedule(bank)
    write(args.output / "schedule.json", trials)
    write(
        args.output / "settings.json",
        {
            "url": "https://api.deepseek.com/chat/completions",
            "model": "deepseek-flash",
            "max_output_tokens": 393216,
            "read_timeout": None,
            "whole_deadline": None,
            "max_calls": len(trials),
            "key_file_sha256": credentials.file_sha256,
            "drafts_never_enter_another_trial": True,
            "one_call_per_trial": True,
            "json_response_format_only_structured": True,
        },
    )
    success = asyncio.run(execute(args, bank, credentials, trials, hashes))
    raise SystemExit(0 if success else 2)


if __name__ == "__main__":
    main()
