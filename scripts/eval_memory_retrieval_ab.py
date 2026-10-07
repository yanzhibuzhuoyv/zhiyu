"""Frozen retrieval-order research: source availability is not answer accuracy."""

import argparse
import json
import os
import statistics
import time
import zlib
from datetime import UTC, datetime
from pathlib import Path

os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
from scripts.eval_memory_route import file_hash, model_hash, percentile, write
from scripts.memory_retrieval_ab import ResearchEncoder, RetrievalIndex, original_digest
from scripts.memory_retrieval_cases import build_cases

ROOT = Path(__file__).resolve().parents[1]
ORDERS = ("lexical-first", "semantic-first", "both")
DEFAULT_MODEL = (
    Path.home()
    / ".cache/huggingface/hub/models--BAAI--bge-small-zh-v1.5/snapshots/7999e1d3359715c523056ef9478215996d62a620"
)


def distractors(turns, prefix):
    topics = (
        "擦了窗台",
        "换了桌布",
        "整理抽屉",
        "浇了绿植",
        "叠好毛巾",
        "洗了杯子",
        "晒了被单",
        "收好信封",
    )
    result = []
    for i in range(turns):
        for role, text in (
            ("user", f"今天{topics[i % len(topics)]}，这是日常记录第{i}条。"),
            ("assistant", f"日常记录第{i}条收到了，陪你歇一会儿。"),
        ):
            result.append(
                {
                    "id": f"{prefix}-{i}-{role}",
                    "role": role,
                    "text": text,
                    "created_at": "2026-08-01T01:00:00+00:00",
                }
            )
    return result


def verify_refs(originals, refs):
    sources = {m["id"]: m for m in originals}
    for ref in refs:
        source = sources.get(ref["message_id"])
        if source is None or any(ref[k] != source[k] for k in ("role", "created_at")):
            raise ValueError("引用跨来源或metadata变化")
        start, end = ref["char_start"], ref["char_end"]
        if (
            type(start) is not int
            or type(end) is not int
            or not 0 <= start < end <= len(source["text"])
        ):
            raise ValueError("引用span无效")
        if source["text"][start:end] != ref["text"]:
            raise ValueError("引用被改写")


def source_availability(case, refs):
    needed = case["expected_quotes"]
    available = [
        identifier
        for identifier, quote in needed.items()
        if any(
            r["message_id"] == identifier and r["role"] == "user" and quote in r["text"]
            for r in refs
        )
    ]
    return {
        "needed_ids": list(needed),
        "available_needed_ids": available,
        "all_needed_available": bool(needed) and len(available) == len(needed),
        "unsupported_source_candidates": sorted(
            set(case["forbidden_ids"]) & {r["message_id"] for r in refs}
        ),
        "meaning": "Candidate presence does not assert any fact; unknown answers require manual review.",
    }


def latency(values):
    return {
        "count": len(values),
        "median_ms": statistics.median(values),
        "p95_ms": percentile(values, 0.95),
    }


def summarize(rows):
    result = {}
    for order in ORDERS:
        chosen = [r for r in rows if r["order"] == order]
        known = [r for r in chosen if r["needed_ids"]]
        result[order] = {
            "cases": len(chosen),
            "known_cases": len(known),
            "all_needed_available": sum(r["all_needed_available"] for r in known),
            "missing_cases": [r["id"] for r in known if not r["all_needed_available"]],
            "first_stage_stops": sum(r["stopped_after_first"] for r in chosen),
            "stopped_but_missing": [
                r["id"] for r in known if r["stopped_after_first"] and not r["all_needed_available"]
            ],
            "query_latency": latency([v for r in chosen for v in r["timed_query_ms"]]),
        }
    return result


def evaluate_case(case, encoder, output, repeats):
    originals = distractors(16, "noise") + case["history"]
    digest = original_digest(originals)
    start = time.perf_counter()
    with RetrievalIndex(originals, encoder, scope=case["id"]) as index:
        built = (time.perf_counter() - start) * 1000
        # Each mode gets a warm-up; timed query embeddings are never cached.
        observed = {
            order: index.retrieve(case["query"], order, scope=case["id"]) for order in ORDERS
        }
        for result in observed.values():
            verify_refs(originals, result["references"])
        measurements = {order: [] for order in ORDERS}
        for repeat in range(repeats):
            order_list = ORDERS if repeat % 2 == 0 else tuple(reversed(ORDERS))
            for order in order_list:
                probe = index.retrieve(case["query"], order, scope=case["id"])
                if probe["references"] != observed[order]["references"]:
                    raise ValueError("同索引重复检索不一致")
                measurements[order].append(probe["elapsed_ms"])
        index.save_index(output / f"{case['id']}.sqlite")
        rows = [
            {
                "id": case["id"],
                "category": case["category"],
                "original_sha256": digest,
                "originals_identical": original_digest(originals) == digest,
                "index_build_ms": built,
                "vector_bytes": index.vector_bytes,
                "fragment_count": len(index.fragments),
                **observed[order],
                **source_availability(case, observed[order]["references"]),
                "timed_query_ms": measurements[order],
            }
            for order in ORDERS
        ]
    both_run = [r for r in rows[:2] if len(r["stages"]) == 2]
    if any(r["references"] != rows[2]["references"] for r in both_run):
        raise ValueError("两路必查结果受执行顺序影响")
    return rows


def growth_probe(turns, case, encoder, output, repeats):
    originals = distractors(turns, "growth") + case["history"]
    raw = json.dumps(originals, ensure_ascii=False, sort_keys=True).encode()
    compressed = zlib.compress(raw, 9)
    path = output / f"growth-{turns}"
    path.mkdir()
    (path / "originals.json.zlib").write_bytes(compressed)
    start = time.perf_counter()
    with RetrievalIndex(originals, encoder, scope="growth") as index:
        built = time.perf_counter() - start
        index.save_index(path / "derived-terms.sqlite")
        import numpy as np

        np.save(path / "derived-vectors.npy", index.vectors, allow_pickle=False)
        observed, timings = {}, {order: [] for order in ORDERS}
        for repeat in range(repeats):
            for order in ORDERS if repeat % 2 == 0 else tuple(reversed(ORDERS)):
                result = index.retrieve(case["query"], order, scope="growth")
                verify_refs(originals, result["references"])
                timings[order].append(result["elapsed_ms"])
                observed[order] = source_availability(case, result["references"])
        if zlib.decompress(compressed) != raw or original_digest(originals) != original_digest(
            json.loads(raw)
        ):
            raise ValueError("完整原文压缩不能还原")
        return {
            "additional_turns": turns,
            "messages": len(originals),
            "fragment_count": len(index.fragments),
            "build_seconds_including_document_cache": built,
            "original_utf8_bytes": len(raw),
            "original_compressed_bytes": len(compressed),
            "vector_payload_bytes": index.vector_bytes,
            "files_bytes": {p.name: p.stat().st_size for p in path.iterdir()},
            "query_latency": {order: latency(timings[order]) for order in ORDERS},
            "last_source_availability": observed,
            "originals_identical": True,
            "meaning": "Repeated performance workload, not independent natural dialogue quality.",
        }


def prepare(output, model, repeats, growth):
    output.mkdir(parents=True, exist_ok=False)
    bank = build_cases()
    write(output / "frozen-inputs.json", bank)
    paths = sorted((ROOT / "scripts").glob("*.py")) + sorted((ROOT / "backend/app").rglob("*.py"))
    hashes = {p.relative_to(ROOT).as_posix(): file_hash(p) for p in paths}
    for p in paths:
        target = output / "frozen-source" / p.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(p.read_bytes())
    identity, files = model_hash(model)
    write(
        output / "manifest.json",
        {
            "started_utc": datetime.now(UTC).isoformat(),
            "sources": hashes,
            "model_sha256": identity,
            "model_files": files,
            "case_sha256": file_hash(output / "frozen-inputs.json"),
            "repeats": repeats,
            "growth_additional_turns": growth,
            "gate": "Final user excerpts >=2 query terms, coverage>=.75; compound/long/uncertain forces fallback.",
            "candidate_fragment_budget": 16,
            "final_pair_budget": 4,
            "reference_char_budget": 6144,
            "score": "Per-pair per-stage max RRF (k=60), equal weights, canonical lexical then semantic tie.",
            "scope": "Independent synthetic experimental index; production cold lifecycle unchanged.",
        },
    )
    return bank, hashes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--growth", type=int, nargs="*", default=[1000, 10000])
    args = parser.parse_args()
    if not 1 <= args.repeats <= 100 or any(n < 1 or n > 10000 for n in args.growth):
        parser.error("无效的实验预算")
    bank, hashes = prepare(args.output, args.model, args.repeats, args.growth)
    start = time.perf_counter()
    encoder = ResearchEncoder(args.model)
    load_ms = (time.perf_counter() - start) * 1000
    rows = []
    indices = args.output / "indices"
    indices.mkdir()
    for case in bank:
        rows.extend(evaluate_case(case, encoder, indices, args.repeats))
        print(json.dumps({"completed_case": case["id"]}), flush=True)
        write(
            args.output / "results.json",
            {"rows": rows, "summary": summarize(rows), "model_load_ms": load_ms},
        )
    # Frozen long-tail scenario checks availability under growth, not chosen by results.
    long_case = max(bank, key=lambda c: max(len(m["text"]) for m in c["history"]))
    growth = [growth_probe(n, long_case, encoder, args.output, 20) for n in args.growth]
    unchanged = all(file_hash(ROOT / p) == expected for p, expected in hashes.items())
    if not unchanged:
        raise ValueError("实验途中代码变化")
    write(
        args.output / "results.json",
        {
            "rows": rows,
            "summary": summarize(rows),
            "growth": growth,
            "model_load_ms": load_ms,
            "sources_unchanged": unchanged,
        },
    )
    print(
        json.dumps({"complete": True, "cases": len(bank), "growth_turns": args.growth}), flush=True
    )


if __name__ == "__main__":
    main()
