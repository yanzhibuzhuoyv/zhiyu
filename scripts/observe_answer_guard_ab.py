"""Separate A/B retrieval, drafts and semantic audits on frozen synthetic cases."""

import argparse
import asyncio
import json
import sys
import time
from datetime import UTC, datetime
from pathlib import Path

from scripts.answer_guard_flow import guard_answer
from scripts.eval_memory_retrieval_ab import DEFAULT_MODEL, ORDERS, distractors, verify_refs
from scripts.eval_memory_route import ROOT, file_hash, model_hash, write
from scripts.memory_answer_guard import evidence_pack
from scripts.memory_answer_guard_cases import build_cases
from scripts.memory_retrieval_ab import ResearchEncoder, RetrievalIndex, original_digest
from scripts.observe_memory_retrieval_ab import EXTENDED, FIXED_UTC, prompt_for
from scripts.observe_memory_route_live import write_report

sys.path.insert(0, str(ROOT / "scripts"))
from deepseek_flash_eval import load_credentials  # noqa: E402 - standalone adapter
from deepseek_flash_stream import run_stream_trial  # noqa: E402


def paired(case, i, kind, **settings):
    orders = ORDERS[:2] if i % 2 == 0 else tuple(reversed(ORDERS[:2]))
    return [{"case_id": case["id"], "order": o, "kind": kind, **settings} for o in orders]


def schedule(bank):
    trials = [t for i, c in enumerate(bank)
              for t in paired(c, i, "generated", mode="disabled", extended=False)]
    categories = list(dict.fromkeys(c["category"] for c in bank))
    firsts = [next(c for c in bank if c["category"] == cat) for cat in categories]
    for i, c in enumerate(firsts[:4]):
        trials.extend(paired(c, i, "generated", mode="enabled", extended=True))
    for i, c in enumerate(firsts):
        for challenge in c["challenge_drafts"]:
            trials.extend(paired(c, i, "challenge", mode="disabled", extended=False,
                                 challenge_id=challenge["id"]))
    return trials


def clock_schedule(bank):
    selected = [c for c in bank if c["id"] in {"guard-known-03", "guard-change-01", "guard-allowed-04"}]
    return [t for i, c in enumerate(selected) for challenge in c["challenge_drafts"]
            for t in paired(c, i, "challenge", mode="disabled", extended=False, challenge_id=challenge["id"])]


def prepare(output, model):
    output.mkdir(parents=True, exist_ok=False)
    bank = build_cases()
    paths = sorted((ROOT / "scripts").glob("*.py")) + sorted((ROOT / "backend/app").rglob("*.py"))
    hashes = {p.relative_to(ROOT).as_posix(): file_hash(p) for p in paths}
    for path in paths:
        target = output / "frozen-source" / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(path.read_bytes())
    write(output / "frozen-inputs.json", bank)
    identity, files = model_hash(model)
    write(output / "manifest.json", {"sources": hashes, "model_sha256": identity,
                                    "model_files": files, "case_sha256": file_hash(output / "frozen-inputs.json"),
                                    "started_utc": datetime.now(UTC).isoformat()})
    return bank, hashes


def availability(case, refs):
    roles = {m["id"]: m["role"] for m in case["history"]}
    available = [identifier for identifier, quote in case["expected_quotes"].items()
                 if any(r["message_id"] == identifier and r["role"] == roles[identifier]
                        and quote in r["text"] for r in refs)]
    return {"needed_ids": list(case["expected_ids"]), "available_ids": available,
            "all_needed_available": bool(case["expected_ids"]) and len(available) == len(case["expected_ids"])}


def trial_case(identifier, messages):
    return {"id": identifier, "category": "reply", "messages": messages,
            "required_checks": [], "forbidden_checks": []}


async def call_one(client, key, case, trial, index):
    start = time.perf_counter()
    retrieved = index.retrieve(case["query"], trial["order"], scope=case["id"])
    verify_refs([m.__dict__ for m in index.originals], retrieved["references"])
    query = case["query"] + ("\n" + EXTENDED if trial["extended"] else "")
    messages = prompt_for(query, retrieved["references"])
    calls = []

    async def generate(prompt, phase, mode):
        result = await run_stream_trial(client, key, {"case": trial_case(case["id"], prompt),
                                                     "mode": mode, "repeat": 1})
        calls.append({"phase": phase, "messages": prompt, "prompt_sha256": original_digest(prompt),
                      "result": result})
        return result

    if trial["kind"] == "challenge":
        challenge = next(c for c in case["challenge_drafts"] if c["id"] == trial["challenge_id"])
        draft, draft_complete_seconds = challenge["text"], time.perf_counter() - start
    else:
        result = await generate(messages, "draft", trial["mode"])
        draft, draft_complete_seconds = result["content"], time.perf_counter() - start
        if not result["grade"]["usable_pass"]:
            return record(case, trial, index, retrieved, messages, calls, start,
                          {"status": "draft_incomplete", "draft_content": draft, "final_content": None})
    evidence = evidence_pack(query, retrieved["references"])
    guarded = await guard_answer(query, draft, evidence, messages, generate, trial["mode"], reference_time=FIXED_UTC.isoformat())
    return {**record(case, trial, index, retrieved, messages, calls, start, guarded),
            "evidence": evidence, "draft_complete_seconds": draft_complete_seconds}


def record(case, trial, index, retrieved, messages, calls, start, guarded):
    return {**trial, "category": case["category"], "query": case["query"], "retrieval": retrieved,
            "messages": messages, "prompt_sha256": original_digest(messages),
            "history_sha256": original_digest([m.__dict__ for m in index.originals]),
            **availability(case, retrieved["references"]), "calls": calls, "guard": guarded,
            "total_wait_seconds": time.perf_counter() - start, "manual_review_required": True}


def final_check(args, credentials, hashes, report):
    if file_hash(args.credentials) != credentials.file_sha256:
        raise RuntimeError("测试中凭据文件发生变化")
    if any(file_hash(ROOT / p) != h for p, h in hashes.items()):
        raise RuntimeError("测试中冻结代码发生变化")
    report.update(complete=True, sources_unchanged=True, credentials_unchanged=True,
                  returned_calls=sum(len(r["calls"]) for r in report["trials"]))
    write_report(args.output, report, credentials.key)


async def execute(args, bank, credentials, trials, hashes):
    import httpx

    load_start = time.perf_counter()
    encoder = ResearchEncoder(args.model)
    report = {"trials": [], "complete": False, "model_load_seconds": time.perf_counter() - load_start,
              "meaning": "Synthetic research; model_checked is not an independent correctness grade. No production change."}
    cases = {c["id"]: c for c in bank}
    timeout = httpx.Timeout(None, connect=15, write=30, pool=30)
    async with httpx.AsyncClient(timeout=timeout, trust_env=False, follow_redirects=False) as client:
        current, index = None, None
        try:
            for trial in trials:
                case = cases[trial["case_id"]]
                if current != case["id"]:
                    if index is not None:
                        index.db.close()
                    index = RetrievalIndex(distractors(16, "noise") + case["history"], encoder, scope=case["id"])
                    current = case["id"]
                row = await call_one(client, credentials.key, case, trial, index)
                report["trials"].append(row)
                write_report(args.output, report, credentials.key)
                print(json.dumps({"id": row["case_id"], "order": row["order"], "kind": row["kind"],
                                  "status": row["guard"]["status"], "calls": len(row["calls"])}), flush=True)
        finally:
            if index is not None:
                index.db.close()
    final_check(args, credentials, hashes, report)
    return all(r["guard"]["status"].startswith("model_checked") for r in report["trials"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--credentials", type=Path, default=ROOT.parent / "rag-system/.env")
    parser.add_argument("--clock-regression", action="store_true")
    args = parser.parse_args()
    bank, hashes = prepare(args.output, args.model)
    trials = clock_schedule(bank) if args.clock_regression else schedule(bank)
    credentials = load_credentials(args.credentials)
    write(args.output / "schedule.json", trials)
    write(args.output / "settings.json", {"url": "https://api.deepseek.com/chat/completions", "model": "deepseek-flash",
          "max_output_tokens": 393216, "read_timeout": None, "whole_deadline": None,
          "key_file_sha256": credentials.file_sha256, "review_thinking": "disabled",
          "max_rewrites": 1, "max_calls": sum(4 if t["kind"] == "generated" else 3 for t in trials),
          "fixed_utc": FIXED_UTC.isoformat(), "drafts_never_enter_another_trial": True,
          "audit_current_utc_provided": True, "known_clock_regression": args.clock_regression,
          "minimum_guard_calls_per_completed_draft": 1, "no_presentation_until_checked": True})
    success = asyncio.run(execute(args, bank, credentials, trials, hashes))
    raise SystemExit(0 if success else 2)


if __name__ == "__main__":
    main()
