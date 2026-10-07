"""Frozen-head observation on continuous synthetic chat; never activates routing."""

import argparse
import hashlib
import json
import time
import uuid
from collections import Counter
from dataclasses import asdict
from datetime import UTC, datetime
from pathlib import Path
from statistics import median

from scripts import eval_memory_route as evaluation
from scripts.memory_route_linear import LinearPolicy, LinearRouter
from scripts.replay_memory_route_linear import load_profile

PROFILE_SHA = "d0bc19180783fbfadd80cb85301298d6edb28eca3a0ae122af07f6469877ab90"


def reference_evidence(references, originals):
    ids = []
    for ref in references:
        source = originals.get(ref["message_id"])
        start, end = ref["char_start"], ref["char_end"]
        if (
            source is None
            or type(start) is not int
            or type(end) is not int
            or not 0 <= start < end <= len(source["content"])
            or ref["role"] != source["role"]
            or ref["created_at"] != source["created_at"]
            or ref["text"] != source["content"][start:end]
        ):
            raise ValueError("来源必须精确对应本会话独立原文")
        ids.append(source["id"])
    return ids


def latency_summary(rows):
    return {
        field: {
            "p50": median([r[field] for r in rows]),
            "p95": evaluation.percentile([r[field] for r in rows], 0.95),
        }
        for field in ("classifier_ms", "baseline_ms", "recent_only_ms")
    }


def summarize_rows(rows):
    if not rows:
        raise ValueError("没有已观测对话")
    daily = [r for r in rows if r["gold"] == "present"]

    def skipped(r):
        return r["decision"]["route"] == "present"

    wrong = sum(r["gold"] == "retrieve" and skipped(r) for r in rows)

    def missing(r):
        return set(r["needed_ids"]) - set(r["provided_ids"])

    gaps = sum(bool(missing(r)) for r in rows)
    added = sum(
        bool(set(r["needed_ids"]) & set(r["provided_ids"])) and skipped(r) for r in rows
    )
    latency = latency_summary(rows)
    return {
        "count": len(rows),
        "gold_present": len(daily),
        "gold_retrieve": len(rows) - len(daily),
        "important_false_skip": wrong,
        "daily_skipped": sum(skipped(r) for r in daily),
        "daily_skip_rate": sum(skipped(r) for r in daily) / len(daily) if daily else 0,
        "baseline_fact_gap_turns": gaps,
        "skip_added_fact_gap_turns": added,
        "daily_reference_chars": sum(r["reference_chars"] for r in daily),
        "daily_skip_removed_chars": sum(
            r["reference_chars"] for r in daily if skipped(r)
        ),
        "reason_counts": dict(Counter(r["decision"]["reason"] for r in rows)),
        "latency_ms": latency,
        "zero_important_false_skip": wrong == 0,
        "classifier_cost_lower_than_lookup": (
            latency["classifier_ms"]["p95"] + latency["recent_only_ms"]["p95"]
            < latency["baseline_ms"]["p95"]
        ),
        "production_skip_enabled": False,
        "fresh_independent_user_samples": False,
    }


def save_pair(chat, owner, identifier, user, assistant, saved):
    result = chat.save_turn(owner, identifier, user, assistant, [])
    pair = [result["user_message"], result["assistant_message"]]
    saved.extend(pair)
    return pair


def seed_pairs(flow):
    old = flow["old_messages"]
    pairs, position = [], 0
    while position < len(old):
        user = old[position]
        following = old[position + 1] if position + 1 < len(old) else None
        if user["role"] != "user":
            raise ValueError("合成来源必须从user开始成对保存")
        if following and following["role"] == "assistant":
            pairs.append((user, following))
            position += 2
        else:
            assistant = {
                "text": "我听到了这句分享。",
                "id": user["id"] + "-seed-assistant",
                "role": "assistant",
                "created_at": user["created_at"],
            }
            pairs.append((user, assistant))
            position += 1
    return pairs


def seed_flow(chat, owner, identifier, flow):
    saved, mapping = [], {}
    for user, assistant in seed_pairs(flow):
        pair = save_pair(
            chat, owner, identifier, user["text"], assistant["text"], saved
        )
        with chat.connection() as db:
            for message, source in zip(pair, (user, assistant), strict=True):
                db.execute(
                    "UPDATE messages SET created_at=? WHERE id=?",
                    (source["created_at"], message["id"]),
                )
                message["created_at"] = source["created_at"]
                mapping[source["id"]] = message["id"]
    for bridge in flow["bridge_turns"]:
        save_pair(chat, owner, identifier, bridge["user"], bridge["assistant"], saved)
    return saved, mapping


def timed_recent(chat, owner, identifier):
    from app.conversation_memory import RECENT_CHARS, RECENT_TURNS, recent_history

    start = time.perf_counter()
    history, _ = recent_history(
        chat.recent_turns(owner, identifier, RECENT_TURNS, RECENT_CHARS)
    )
    return history, (time.perf_counter() - start) * 1000


def observe_turn(chat, owner, identifier, turn, router, originals, mapping, index):
    from app.conversation_memory import conversation_context_result

    # Alternate read order to expose order/cache effects; not a fair LLM speed ratio.
    if index % 2 == 0:
        history, recent_ms = timed_recent(chat, owner, identifier)
    start = time.perf_counter()
    context = conversation_context_result(chat, owner, identifier, turn["text"])
    baseline_ms = (time.perf_counter() - start) * 1000
    if index % 2:
        history, recent_ms = timed_recent(chat, owner, identifier)
    if history != context.history:
        raise RuntimeError("连续对话近期读取不一致")
    start = time.perf_counter()
    decision = router.decide(turn["text"], history[-4:])
    classifier_ms = (time.perf_counter() - start) * 1000
    provided = reference_evidence(context.references, originals)
    needed = [mapping[x] for x in turn["needed_source_ids"]]
    return {
        "id": turn["id"],
        "gold": turn["gold"],
        "category": turn["category"],
        "decision": asdict(decision),
        "needed_ids": needed,
        "provided_ids": provided,
        "classifier_ms": classifier_ms,
        "baseline_ms": baseline_ms,
        "recent_only_ms": recent_ms,
        "reference_chars": sum(len(r["text"]) for r in context.references),
        "baseline_search_complete": context.complete,
        "context_sha256": hashlib.sha256(
            json.dumps(history, ensure_ascii=False).encode()
        ).hexdigest(),
        "references": context.references,
    }


def observe_flow(chat, flow, router):
    owner = "synthetic-" + flow["id"]
    identifier = chat.create_conversation(owner, flow["id"], "synthetic", False)["id"]
    saved, mapping = seed_flow(chat, owner, identifier, flow)
    rows = []
    for index, turn in enumerate(flow["turns"]):
        originals = {m["id"]: m for m in saved}
        row = observe_turn(
            chat, owner, identifier, turn, router, originals, mapping, index
        )
        rows.append({**row, "flow_id": flow["id"]})
        save_pair(chat, owner, identifier, turn["text"], turn["assistant"], saved)
        if chat.list_messages(owner, identifier) != saved:
            raise RuntimeError("逐轮原文/作者/时间/来源/ID变化")
    history_sha = hashlib.sha256(
        json.dumps(saved, ensure_ascii=False, sort_keys=True).encode()
    ).hexdigest()
    return rows, {
        "id": flow["id"],
        "messages": len(saved),
        "history_sha256": history_sha,
        "originals_identical_each_turn": True,
    }


def prepare(model, profile_path, output):
    from scripts.memory_route_flows import DATASET_VERSION, GOLD_ORIGIN, flows

    output, model = Path(output), Path(model).resolve(strict=True)
    output.mkdir(parents=True, exist_ok=False)
    profile = load_profile(profile_path, PROFILE_SHA, model)
    bank = flows()
    evaluation.write(output / "frozen-inputs.json", bank)
    (output / "frozen-profile.json").write_bytes(Path(profile_path).read_bytes())
    if evaluation.file_hash(output / "frozen-profile.json") != PROFILE_SHA:
        raise RuntimeError("策略复制期间变更")
    sources = sorted((evaluation.ROOT / "backend/app").rglob("*.py")) + [
        Path(__file__),
        evaluation.ROOT / "scripts/memory_route_flows.py",
    ]
    hashes = {
        str(p.relative_to(evaluation.ROOT)): evaluation.file_hash(p) for p in sources
    }
    evaluation.write(
        output / "manifest.json",
        {
            "version": DATASET_VERSION,
            "gold_origin": GOLD_ORIGIN,
            "started_utc": datetime.now(UTC).isoformat(),
            "sources": hashes,
            "profile_sha256": PROFILE_SHA,
            "model": evaluation.model_hash(model),
            "meaning": "Shadow observation only; no refitting, threshold changes or application activation.",
        },
    )
    return output, model, profile, bank, hashes


def observe_bank(output, bank, router):
    from app.database import ChatDatabase

    chat = ChatDatabase(output / "synthetic-data")
    rows, histories = [], []
    for flow in bank:
        observed, signature = observe_flow(chat, flow, router)
        rows.extend(observed)
        histories.append(signature)
        print(
            json.dumps({"stage": "flow", "id": flow["id"], "turns": len(observed)}),
            flush=True,
        )
    return rows, histories


def run(model, profile_path, output):
    output, model, profile, bank, hashes = prepare(model, profile_path, output)
    start = time.perf_counter()
    encoder = evaluation.BgeEncoder(model)
    load_seconds = time.perf_counter() - start
    router = LinearRouter(
        encoder,
        profile["prototypes"],
        profile["head"]["coefficients"],
        profile["head"]["intercept"],
        LinearPolicy(**profile["policy"]),
    )
    rows, histories = observe_bank(output, bank, router)
    if any(evaluation.file_hash(evaluation.ROOT / p) != h for p, h in hashes.items()):
        raise RuntimeError("观察期间源码变更")
    load_profile(profile_path, PROFILE_SHA, model)
    report = {
        "status": "completed",
        "cloud_calls": 0,
        "load_seconds": load_seconds,
        "summary": summarize_rows(rows),
        "results": rows,
        "histories": histories,
        "originals_identical": True,
        "finished_utc": datetime.now(UTC).isoformat(),
    }
    evaluation.write(output / "results.json", report)
    print(
        json.dumps({"status": report["status"], "summary": report["summary"]}),
        flush=True,
    )
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument(
        "--output",
        default=str(
            evaluation.ROOT
            / "artifacts"
            / (
                "memory-route-flows-"
                + datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
                + "-"
                + uuid.uuid4().hex[:6]
            )
        ),
    )
    args = parser.parse_args()
    try:
        run(args.model, args.profile, args.output)
    except Exception as error:  # noqa: BLE001 -- Keep source text out of errors.
        print(
            json.dumps({"status": "failed", "error_type": type(error).__name__}),
            flush=True,
        )
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
