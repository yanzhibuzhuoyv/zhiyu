"""Authorized paired full-answer trials over synthetic, immutable originals."""

import argparse
import asyncio
import hashlib
import json
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from unittest.mock import patch

from scripts.eval_memory_retrieval_ab import (
    DEFAULT_MODEL,
    ORDERS,
    distractors,
    prepare,
    source_availability,
    verify_refs,
)
from scripts.eval_memory_route import file_hash, write
from scripts.memory_retrieval_ab import ResearchEncoder, RetrievalIndex, original_digest
from scripts.observe_memory_route_live import write_report

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "backend"), str(ROOT / "scripts")]
from deepseek_flash_eval import load_credentials  # noqa: E402 - standalone script adapter
from deepseek_flash_stream import run_stream_trial  # noqa: E402

FIXED_UTC = datetime(2026, 10, 7, 0, 0, tzinfo=UTC)
EXTENDED = "请自然地陪我慢慢聊，写1200到1800个汉字，先回答刚才的问题再接着聊今天，别编造旧事。"


class FrozenTime:
    @staticmethod
    def now(_zone):
        return FIXED_UTC


def prompt_for(query, references):
    from app.chat_routes import prompt

    recent = [
        {"role": "user", "content": "今天忙了一阵，刚坐下来。"},
        {"role": "assistant", "content": "我在，陪你歇一会儿。"},
    ]
    with patch("app.chat_routes.datetime", FrozenTime):
        return prompt(
            recent,
            query,
            [],
            references,
            {"mode": "preset", "style": "gentle", "role": "partner", "name": "小语"},
            True,
        )


def schedule(bank):
    trials = []
    for i, case in enumerate(bank):
        order = ORDERS[:2] if i % 2 == 0 else tuple(reversed(ORDERS[:2]))
        trials.extend(
            {"case_id": case["id"], "order": o, "mode": "disabled", "extended": False}
            for o in order
        )
    long_case = max(bank, key=lambda c: max(len(m["text"]) for m in c["history"]))
    selected = [
        long_case,
        *[
            next(c for c in bank if c["category"] == category)
            for category in ("ai_guess", "multi_fact", "user_question_unknown")
        ],
    ]
    for i, case in enumerate(selected):
        identifier = case["id"]
        order = ORDERS[:2] if i % 2 == 0 else tuple(reversed(ORDERS[:2]))
        trials.extend(
            {"case_id": identifier, "order": o, "mode": "enabled", "extended": True} for o in order
        )
    return trials


async def call_one(client, key, case, trial, index):
    query = case["query"] + ("\n" + EXTENDED if trial["extended"] else "")
    start = time.perf_counter()
    # Output length is an experiment setting, not part of the original memory question.
    # This does not claim to parse arbitrary mixed user requests in production.
    search_query = case["query"]
    retrieval = index.retrieve(search_query, trial["order"], scope=case["id"])
    verify_refs([m.__dict__ for m in index.originals], retrieval["references"])
    messages = prompt_for(query, retrieval["references"])
    result = await run_stream_trial(
        client,
        key,
        {
            "case": {
                "id": case["id"],
                "category": "reply",
                "messages": messages,
                "required_checks": [],
                "forbidden_checks": [],
            },
            "mode": trial["mode"],
            "repeat": 1,
        },
    )
    return {
        **trial,
        "query": query,
        "search_query": search_query,
        "retrieval": retrieval,
        "messages": messages,
        "prompt_sha256": original_digest(messages),
        "history_sha256": original_digest([m.__dict__ for m in index.originals]),
        "answer_expectation": case["answer_expectation"],
        **source_availability(case, retrieval["references"]),
        "total_wait_seconds": time.perf_counter() - start,
        **result,
        "category": case["category"],
        "transport_category": result.get("category", "reply"),
        "manual_review_required": True,
    }


def case_index(current, index, case, encoder):
    if current == case["id"]:
        return current, index
    if index is not None:
        index.db.close()
    return case["id"], RetrievalIndex(
        distractors(16, "noise") + case["history"], encoder, scope=case["id"]
    )


async def execute(args, bank, credentials, trials, hashes):
    import httpx

    encoder = ResearchEncoder(args.model)
    case_map = {c["id"]: c for c in bank}
    report = {
        "trials": [],
        "complete": False,
        "meaning": "SSE completion is not factual correctness; synthetic experiment, not production deployment.",
    }
    timeout = httpx.Timeout(None, connect=15, write=30, pool=30)
    async with httpx.AsyncClient(
        timeout=timeout, trust_env=False, follow_redirects=False
    ) as client:
        current, index = None, None
        try:
            for trial in trials:
                case = case_map[trial["case_id"]]
                current, index = case_index(current, index, case, encoder)
                row = await call_one(client, credentials.key, case, trial, index)
                report["trials"].append(row)
                write_report(args.output, report, credentials.key)
                print(
                    json.dumps(
                        {
                            "id": row["case_id"],
                            "order": row["order"],
                            "extended": row["extended"],
                            "complete_sse": row["grade"]["usable_pass"],
                        }
                    ),
                    flush=True,
                )
                if not row["grade"]["usable_pass"]:
                    raise RuntimeError("实际测试未收到完整回答，保留已取得结果")
        finally:
            if index is not None:
                index.db.close()
    finalize(args, credentials, hashes, report)


def finalize(args, credentials, hashes, report):
    if file_hash(args.credentials) != credentials.file_sha256 or any(
        file_hash(ROOT / p) != h for p, h in hashes.items()
    ):
        raise RuntimeError("测试中凭据或冻结代码发生变化")
    report.update(
        complete=True,
        credentials_unchanged=True,
        sources_unchanged=True,
        returned_calls=len(report["trials"]),
        results_sha256_before_final=hashlib.sha256(
            (args.output / "results.json").read_bytes()
        ).hexdigest(),
    )
    write_report(args.output, report, credentials.key)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--credentials", type=Path, default=ROOT.parent / "rag-system/.env")
    parser.add_argument("--extended-only", action="store_true")
    args = parser.parse_args()
    bank, hashes = prepare(args.output, args.model, 1, [])
    trials = schedule(bank)
    if args.extended_only:
        trials = [t for t in trials if t["extended"]]
    credentials = load_credentials(args.credentials)
    write(args.output / "schedule.json", trials)
    write(
        args.output / "live-settings.json",
        {
            "official_url": "https://api.deepseek.com/chat/completions",
            "model": "deepseek-flash",
            "max_output_tokens": 393216,
            "read_timeout": None,
            "whole_request_deadline": None,
            "key_file_sha256": credentials.file_sha256,
            "maximum_calls": len(trials),
            "output_setting_separate_from_search": True,
            "fixed_prompt_utc": FIXED_UTC.isoformat(),
            "paired": "Same originals/recent/prompt settings; returned answers never enter either history.",
        },
    )
    asyncio.run(execute(args, bank, credentials, trials, hashes))


if __name__ == "__main__":
    main()
