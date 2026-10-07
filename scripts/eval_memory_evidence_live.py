"""Full-answer, source-grounded synthetic regression after a recall prompt change."""

import argparse
import asyncio
import hashlib
import json
import os
import re
import sys
import uuid
from datetime import UTC, datetime
from pathlib import Path

from scripts import eval_memory_route as evaluation
from scripts.eval_memory_route_flows import reference_evidence, save_pair
from scripts.observe_memory_route_live import make_provider, write_report

ROOT = evaluation.ROOT


def screen(case, answer):
    required = any(re.search(p, answer, re.DOTALL) for p in case["required_patterns"])
    forbidden = [
        i
        for i, p in enumerate(case["forbidden_patterns"])
        if re.search(p, answer, re.DOTALL)
    ]
    return {
        "rule_screen_pass": required and not forbidden,
        "required_any_matched": required,
        "forbidden_indices": forbidden,
        "manual_review_required": True,
    }


def user_quote_available(quote, messages, refs):
    if quote is None:
        return None
    return any(
        m["role"] == "user" and quote in m["content"] for m in messages[1:-1]
    ) or any(r["role"] == "user" and quote in r["text"] for r in refs)


def known_regressions(path, expected):
    with Path(path).open("rb") as source:
        raw = source.read(5 * 1024 * 1024 + 1)
    if len(raw) > 5 * 1024 * 1024 or hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError("已知回归来源SHA或大小无效")
    report = json.loads(raw)
    mode = next(m for m in report["modes"] if m["mode"] == "disabled")
    seeds = [(t["input"], t["answer"]) for t in mode["turns"][:10]]
    if len(seeds) != 10 or len(mode["turns"]) != 11:
        raise ValueError("已知回归来源不完整")
    values = [
        (
            "payment",
            mode["turns"][9]["input"],
            r"你(?:之前|当时)?说(?:过|的)?(?:是)?现金",
        ),
        (
            "notice",
            mode["turns"][7]["input"],
            r"你(?:确实|当时|之前)?(?:没有|未|没)收到(?:明确)?通知",
        ),
    ]
    bank = [
        {
            "id": "known-" + name + "-ai-echo",
            "seeds": seeds,
            "recent_seeds": [],
            "query": query,
            "category": "known_regression",
            "expected_known": False,
            "expected_user_quote": None,
            "required_patterns": [
                r"无法确认|不能确定|没有.{0,8}(?:记录|依据|证据)|未.{0,5}确认|没法.{0,5}(?:确认|判断)|不确定"
            ],
            "forbidden_patterns": [forbidden],
            "notes": "Previously unsupported AI answer must not prove user facts.",
        }
        for name, query, forbidden in values
    ]
    return [*bank, extended_regression(seeds, mode["turns"][10]["input"])]


def extended_regression(seeds, query):
    return {
        "id": "known-extended-ai-echo",
        "seeds": seeds,
        "recent_seeds": [],
        "query": query,
        "category": "known_regression",
        "expected_known": False,
        "expected_user_quote": None,
        "required_patterns": [r"(?s).+"],
        "forbidden_patterns": [
            r"取件日(?:期)?(?:也)?(?:还没|未)定",
            r"通知(?:还没|没)来",
        ],
        "notes": "Long reply must not propagate unsupported earlier AI claims; manual review required.",
    }


def seed_case(chat, owner, cid, case):
    saved = []
    for user, assistant in case["seeds"]:
        save_pair(chat, owner, cid, user, assistant, saved)
    for number in range(22):
        save_pair(
            chat,
            owner,
            cid,
            f"合成桥接第{number}轮，今天整理了桌边。",
            "陪你慢慢整理。",
            saved,
        )
    for user, assistant in case["recent_seeds"]:
        save_pair(chat, owner, cid, user, assistant, saved)
    return saved


def capture_provider():
    provider = make_provider("disabled")
    complete = provider.complete
    provider.prompts = []

    async def captured(provider_id, key, model, messages, max_tokens=2048):
        provider.prompts.append(messages)
        return await complete(provider_id, key, model, messages, max_tokens)

    provider.complete = captured
    return provider


async def probe(app, client, headers, owner, case):
    cid = app.state.chat.create_conversation(
        owner,
        case["id"],
        "deepseek-flash",
        False,
        {"mode": "preset", "style": "gentle", "role": "partner", "name": "小语"},
    )["id"]
    saved = seed_case(app.state.chat, owner, cid, case)
    response = await client.post(
        f"/api/conversations/{cid}/messages",
        headers=headers,
        json={"content": case["query"]},
    )
    if response.status_code != 200:
        raise RuntimeError("证据测试未取得完整回复")
    payload = response.json()
    if payload["memory_recognition"]["status"] != "disabled":
        raise RuntimeError("本组只测回复证据，不增加后台云调用")
    refs = payload["memory_refs"]
    reference_evidence(refs, {m["id"]: m for m in saved})
    saved.extend([payload["user_message"], payload["assistant_message"]])
    if app.state.chat.list_messages(owner, cid) != saved:
        raise RuntimeError("证据测试完整原文变化")
    answer = payload["assistant_message"]["content"]
    return {
        "id": case["id"],
        "category": case["category"],
        "query": case["query"],
        "expected_known": case["expected_known"],
        "answer": answer,
        "source_quote_available": user_quote_available(
            case["expected_user_quote"], app.state.provider.prompts[-1], refs
        ),
        "references": refs,
        "history_sha256": hashlib.sha256(
            json.dumps(saved, ensure_ascii=False, sort_keys=True).encode()
        ).hexdigest(),
        "message_count": len(saved),
        "originals_identical": True,
        **screen(case, answer),
    }


def prepare(args):
    from scripts.memory_evidence_cases import DATASET_VERSION, GOLD_ORIGIN, cases

    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    os.environ["ZHIYU_DATA_DIR"] = str(output / "unused-global")
    sys.path[:0] = [str(ROOT / "backend"), str(ROOT / "scripts")]
    from deepseek_flash_eval import load_credentials

    bank = [{**c, "sample_kind": "fresh_synthetic"} for c in cases()]
    if args.known_results:
        bank += [
            {**c, "sample_kind": "known_regression"}
            for c in known_regressions(args.known_results, args.expected_known_sha)
        ]
    elif args.expected_known_sha:
        raise ValueError("回归SHA必须对应指定来源")
    credentials = load_credentials(args.credentials)
    # All project Python modules are frozen here, including the stream adapter.
    files = sorted((ROOT / "backend/app").rglob("*.py")) + sorted(
        (ROOT / "scripts").glob("*.py")
    )
    hashes = {str(p.relative_to(ROOT)): evaluation.file_hash(p) for p in files}
    evaluation.write(output / "frozen-inputs.json", bank)
    for path in files:
        target = output / "frozen-source" / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(path.read_bytes())
    evaluation.write(
        output / "manifest.json",
        {
            "version": DATASET_VERSION,
            "gold_origin": GOLD_ORIGIN,
            "sources": hashes,
            "maximum_calls": len(bank) + 1,
            "thinking": "disabled",
            "max_output_tokens": 393216,
            "read_timeout": None,
            "whole_request_deadline": None,
            "originals": "Synthetic only; background recognition disabled to isolate evidence behavior.",
            "started_utc": datetime.now(UTC).isoformat(),
            "meaning": "Regex screen requires independent qualitative review.",
        },
    )
    return output, bank, credentials, hashes


async def execute_bank(output, bank, credentials, report):
    import httpx  # noqa: I001 - optional app imports follow backend path setup
    from app.config import Settings
    from app.main import create_app

    provider = capture_provider()
    app = create_app(Settings(data_dir=output / "synthetic-data"), provider)
    report["events"] = provider.events
    async with (
        app.router.lifespan_context(app),
        httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://synthetic.invalid",
        ) as client,
    ):
        response = await client.post(
            "/api/auth/login",
            json={
                "provider_id": "deepseek",
                "model": "deepseek-flash",
                "api_key": credentials.key,
            },
        )
        if response.status_code != 200:
            raise RuntimeError("证据测试鉴权失败")
        token = response.json()["token"]
        headers = {"Authorization": "Bearer " + token}
        owner = app.state.sessions.get(token).owner
        for case in bank:
            row = await probe(app, client, headers, owner, case)
            report["results"].append({**row, "sample_kind": case["sample_kind"]})
            write_report(output, report, credentials.key)
            print(
                json.dumps(
                    {
                        "id": case["id"],
                        "rule_screen_pass": row["rule_screen_pass"],
                    }
                ),
                flush=True,
            )
        if (
            await client.delete("/api/auth/session", headers=headers)
        ).status_code != 204:
            raise RuntimeError("证据测试注销失败")
    return provider


def finalize(args, bank, hashes, credentials, provider, report):
    if evaluation.file_hash(args.credentials) != credentials.file_sha256 or any(
        evaluation.file_hash(ROOT / p) != h for p, h in hashes.items()
    ):
        raise RuntimeError("测试期间凭据或源码变更")
    if len(provider.events) != len(bank) + 1 or any(
        e["status"] != "complete" for e in provider.events
    ):
        raise RuntimeError("实际调用日程或完整性不符")
    report.update(
        status="completed",
        cloud_calls=len(provider.events),
        source_files_unchanged=True,
        credential_file_unchanged=True,
        originals_identical=True,
        rule_screen_pass_count=sum(r["rule_screen_pass"] for r in report["results"]),
        known_retrieval_gaps=[
            r["id"] for r in report["results"] if r["source_quote_available"] is False
        ],
        finished_utc=datetime.now(UTC).isoformat(),
    )


async def run(args):
    output, bank, credentials, hashes = prepare(args)
    report = {"status": "running", "events": [], "results": []}
    write_report(output, report, credentials.key)
    try:
        provider = await execute_bank(output, bank, credentials, report)
        finalize(args, bank, hashes, credentials, provider, report)
    except BaseException as error:
        report.update(status="failed", error_type=type(error).__name__)
        write_report(output, report, credentials.key)
        raise
    write_report(output, report, credentials.key)
    print(
        json.dumps(
            {
                k: report[k]
                for k in (
                    "status",
                    "cloud_calls",
                    "rule_screen_pass_count",
                    "known_retrieval_gaps",
                )
            }
        ),
        flush=True,
    )
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--credentials", required=True)
    parser.add_argument("--known-results")
    parser.add_argument("--expected-known-sha")
    parser.add_argument(
        "--output",
        default=str(
            ROOT
            / "artifacts"
            / (
                "memory-evidence-live-"
                + datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
                + "-"
                + uuid.uuid4().hex[:6]
            )
        ),
    )
    args = parser.parse_args()
    try:
        asyncio.run(run(args))
    except BaseException as error:  # noqa: BLE001 - never print secret-bearing exception text
        print(
            json.dumps({"status": "failed", "error_type": type(error).__name__}),
            flush=True,
        )
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
