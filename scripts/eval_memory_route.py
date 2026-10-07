"""Offline BGE routing experiment with a frozen holdout and fail-closed gate."""

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import sys
import time
import uuid
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from statistics import median

from scripts.memory_route_cases import VERSION, cases_for_split, split_statistics
from scripts.memory_route_policy import PrototypeRouter, RoutePolicy, route_input

ROOT = Path(__file__).resolve().parents[1]
SOURCE_PATHS = (
    "scripts/memory_route_policy.py",
    "scripts/memory_route_cases.py",
    "scripts/eval_memory_route.py",
    "backend/app/rag_semantic.py",
)


@dataclass(frozen=True)
class Experiment:
    version: str
    case_reader: object
    stats_reader: object
    sources: tuple
    router_type: type = PrototypeRouter
    context_prototypes: bool = True
    strategy: str = "maximum similarity; current query with contextual veto"


def default_experiment():
    return Experiment(VERSION, cases_for_split, split_statistics, SOURCE_PATHS)


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def model_hash(directory):
    files = {
        str(p.relative_to(directory)): file_hash(p)
        for p in sorted(directory.rglob("*"))
        if p.is_file()
    }
    if not files or "model.safetensors" not in files:
        raise ValueError("完整本地BGE模型不存在")
    return hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(), files


class BgeEncoder:
    def __init__(self, directory):
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["TRANSFORMERS_OFFLINE"] = "1"
        os.environ["TOKENIZERS_PARALLELISM"] = "false"
        import torch

        torch.set_num_threads(2)
        torch.set_num_interop_threads(1)
        sys.path.insert(0, str(ROOT / "backend"))
        from app.rag_semantic import LocalSemanticEncoder

        self.encoder = LocalSemanticEncoder(str(directory))
        self.model = self.encoder._model

    def fits_input(self, text):
        # Includes special tokens; never classify a silently truncated tail.
        tokens = self.model.tokenizer(text, add_special_tokens=True, truncation=False)
        return len(tokens["input_ids"]) <= self.model.max_seq_length

    def encode_documents(self, texts):
        return self.encoder.encode_documents(
            texts
        )  # Symmetric task: no retrieval instruction.


class CachedEncoder:
    """Only synthetic calibration inputs; never caches application user texts."""

    def __init__(self, encoder, cache):
        self.encoder, self.cache = encoder, cache

    def fits_input(self, text):
        return text in self.cache

    def encode_documents(self, texts):
        return [self.cache[text] for text in texts]


def percentile(values, fraction):
    if not values:
        return None
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def summarize(results):
    counts = Counter((r["gold"], r["decision"]["route"]) for r in results)
    present = sum(r["gold"] == "present" for r in results)
    measured = [r["milliseconds"] for r in results if r["milliseconds"] is not None]
    return {
        "count": len(results),
        "gold_present": present,
        "gold_retrieve": len(results) - present,
        "important_false_skip": counts["retrieve", "present"],
        "present_skipped": counts["present", "present"],
        "present_skip_rate": counts["present", "present"] / present if present else 0,
        "retrieval_count": sum(r["decision"]["route"] == "retrieve" for r in results),
        "reason_counts": dict(Counter(r["decision"]["reason"] for r in results)),
        "milliseconds_p50": median(measured) if measured else None,
        "milliseconds_p95": percentile(measured, 0.95),
    }


def evaluate(router, cases):
    results = []
    for case in cases:
        start = time.perf_counter()
        decision = router.decide(case["text"], case["recent_context"])
        results.append(
            {
                "id": case["id"],
                "category": case["category"],
                "gold": case["gold"],
                "decision": asdict(decision),
                "milliseconds": (time.perf_counter() - start) * 1000,
            }
        )
    return results


def calibration_cache(encoder, train, validation):
    texts = list(
        dict.fromkeys(
            text
            for case in (*train, *validation)
            for text in (
                case["text"],
                route_input(case["text"], case["recent_context"]),
            )
        )
    )
    fitting = [text for text in texts if encoder.fits_input(text)]
    vectors = encoder.encode_documents(fitting)
    return dict(zip(fitting, vectors, strict=True))


def training_prototypes(train, cache, context_prototypes):
    return [
        (case["gold"], cache[text])
        for case in train
        for text in dict.fromkeys(
            (case["text"], route_input(case["text"], case["recent_context"]))
            if context_prototypes
            else (case["text"],)
        )
        if text in cache
    ]


def validation_scores(router, validation, cache):
    scored = []
    for case in validation:
        inputs = (
            [case["text"], route_input(case["text"], case["recent_context"])]
            if case["recent_context"]
            else [case["text"]]
        )
        scored.append(
            [router.scores(cache[text]) for text in inputs]
            if all(text in cache for text in inputs)
            else None
        )
    return scored


def validation_rows(router, validation, scored):
    return [
        {
            "id": case["id"],
            "category": case["category"],
            "gold": case["gold"],
            "decision": asdict(router.from_scores(score))
            if score is not None
            else {"route": "retrieve", "reason": "input_limit"},
            "milliseconds": None,  # Cached sweep is not a timed inference.
        }
        for case, score in zip(validation, scored, strict=True)
    ]


def select_policy(cached, prototypes, validation, scored, router_type):
    candidates = []
    for similarity in range(60, 96):
        for margin in range(21):
            policy = RoutePolicy(similarity / 100, margin / 100)
            router = router_type(cached, prototypes, policy)
            score = summarize(validation_rows(router, validation, scored))
            if score["important_false_skip"] == 0:
                candidates.append(
                    (
                        score["present_skipped"],
                        policy.min_margin,
                        policy.min_similarity,
                        policy,
                        score,
                    )
                )
    if not candidates:
        raise ValueError("验证集没有满足保守门禁的候选")
    _, _, _, policy, score = max(candidates, key=lambda row: row[:3])
    return policy, score, len(candidates)


def calibration(
    encoder, train, validation, *, router_type=PrototypeRouter, context_prototypes=True
):
    cache = calibration_cache(encoder, train, validation)
    prototypes = training_prototypes(train, cache, context_prototypes)
    cached = CachedEncoder(encoder, cache)
    baseline = router_type(cached, prototypes, RoutePolicy())
    scored = validation_scores(baseline, validation, cache)
    policy, score, count = select_policy(
        cached, prototypes, validation, scored, router_type
    )
    return (
        prototypes,
        policy,
        score,
        {
            "grid_candidates": 36 * 21,
            "safe_candidates": count,
            "prototype_count": len(prototypes),
            "calibration_inputs": len(cache),
            "validation_results": validation_rows(
                router_type(cached, prototypes, policy), validation, scored
            ),
            "validation_scores": [
                {"id": case["id"], "gold": case["gold"], "scores": value}
                for case, value in zip(validation, scored, strict=True)
            ],
        },
    )


def stress_cases(test):
    """40 whitespace/punctuation/emoji renderings per base case, not new user gold."""
    cases = []
    for case in test:
        for variation in range(40):
            padding = (" " * (variation % 5)) + ("\n" * (variation % 3))
            suffix = ("", "。", "🙂", "……")[variation // 10]
            cases.append(
                {
                    **case,
                    "id": case["id"] + "-v" + str(variation),
                    "text": padding + case["text"] + suffix + (" " * (variation // 5)),
                }
            )
    return cases


def length_cases():
    """Structural limits, not additional independent semantic intent gold."""
    rows = []
    for index in range(48):
        length = (512, 1024, 4096, 15950)[index % 4]
        long_text = ("甲" if index % 2 else "中") * length
        rows.append(
            {
                "id": "length-" + str(index),
                "category": "token-limit-boundary",
                "text": long_text + "，请查我从前提过的那件事。"
                if index < 32
                else "先陪我聊聊今天。",
                "recent_context": []
                if index < 32
                else [{"role": "user", "content": long_text}],
                "gold": "retrieve",
            }
        )
    return rows


def gate(validation, test, stress):
    checks = {
        "validation_zero_important_false_skip": validation["important_false_skip"] == 0,
        "test_zero_important_false_skip": test["important_false_skip"] == 0,
        "test_present_skip_at_least_20_percent": test["present_skip_rate"] >= 0.20,
        "test_warm_p95_at_most_100_ms": test["milliseconds_p95"] is not None
        and test["milliseconds_p95"] <= 100,
        "stress_zero_important_false_skip": stress["important_false_skip"] == 0,
    }
    return {
        "passed": all(checks.values()),
        "checks": checks,
        "meaning": "Eligibility for further controlled testing, not automatic production activation.",
    }


def write(path, report):
    encoded = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)
    path.write_text(encoded + "\n", encoding="utf-8")


def prepare_experiment(model, output, spec):
    directory = Path(model).resolve(strict=True)
    output = Path(output).resolve()
    if output.exists() or output == directory or directory in output.parents:
        raise ValueError("评测必须使用独立新输出目录")
    output.mkdir(parents=True)
    model_digest, model_files = model_hash(directory)
    sources = {path: file_hash(ROOT / path) for path in spec.sources}
    manifest = {
        "version": spec.version,
        "source_sha256": sources,
        "model_sha256": model_digest,
        "model_files": model_files,
        "model_path": str(directory),
        "dataset": spec.stats_reader(),
        "cpu_threads": 2,
        "interop_threads": 1,
        "cloud_calls": 0,
        "started_utc": datetime.now(UTC).isoformat(),
        "gates": {
            "validation_false_skip": 0,
            "test_false_skip": 0,
            "test_present_skip_min": 0.20,
            "test_warm_p95_max_ms": 100,
            "stress_false_skip": 0,
            "stress_cases": spec.stats_reader()["test"]["count"] * 40,
            "length_cases": 48,
            "length_all_input_limit": True,
        },
        "selection": "train prototypes; validation thresholds; heldout only after profile write",
        "strategy": spec.strategy,
        "context_prototypes": spec.context_prototypes,
        "scope": "offline experimental old-memory routing; no app switch or private data",
    }
    write(output / "manifest.json", manifest)
    for path in spec.sources:
        destination = output / "frozen-source" / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes((ROOT / path).read_bytes())
    return directory, output, manifest


def freeze_profile(output, spec, manifest, encoder):
    train, validation = spec.case_reader("train"), spec.case_reader("validation")
    prototypes, policy, validation_score, details = calibration(
        encoder,
        train,
        validation,
        router_type=spec.router_type,
        context_prototypes=spec.context_prototypes,
    )
    profile = {
        "version": spec.version,
        "strategy": spec.strategy,
        "policy": asdict(policy),
        "prototypes": prototypes,
        "model_sha256": manifest["model_sha256"],
        "source_sha256": manifest["source_sha256"],
        "calibration": details,
        "validation": validation_score,
        "frozen_before_test": True,
    }
    write(output / "frozen-profile.json", profile)
    print(
        json.dumps(
            {
                "stage": "profile_frozen",
                "policy": asdict(policy),
                "validation": validation_score,
            }
        ),
        flush=True,
    )
    return profile, file_hash(output / "frozen-profile.json"), train


def heldout_and_stress(router, output, case_reader):
    test = case_reader("test")  # Access only after profile is saved.
    test_results = evaluate(router, test)
    stress = stress_cases(test)
    write(output / "frozen-stress-inputs.json", stress)
    boundaries = length_cases()
    write(output / "frozen-length-inputs.json", boundaries)
    stress_results = []
    for offset in range(0, len(stress), 100):
        stress_results.extend(evaluate(router, stress[offset : offset + 100]))
        print(
            json.dumps({"stage": "stress", "completed": len(stress_results)}),
            flush=True,
        )
    return {
        "test_cases": test,
        "test_results": test_results,
        "stress_results": stress_results,
        "length_results": evaluate(router, boundaries),
    }


def integrity_checks(directory, output, manifest, profile_sha):
    checks = {
        "profile_unchanged": profile_sha == file_hash(output / "frozen-profile.json"),
        "model_files_unchanged": model_hash(directory)[0] == manifest["model_sha256"],
        "source_files_unchanged": all(
            file_hash(ROOT / path) == value
            and file_hash(output / "frozen-source" / path) == value
            for path, value in manifest["source_sha256"].items()
        ),
    }
    if not all(checks.values()):
        raise RuntimeError("评测源文件、模型或冻结策略已变更")
    return checks


def final_report(
    directory, output, manifest, profile, profile_sha, encoder, elapsed, rows
):
    test_score, stress_score = (
        summarize(rows["test_results"]),
        summarize(rows["stress_results"]),
    )
    acceptance = gate(profile["validation"], test_score, stress_score)
    acceptance["checks"]["length_all_input_limit"] = all(
        row["decision"]["route"] == "retrieve"
        and row["decision"]["reason"] == "input_limit"
        for row in rows["length_results"]
    )
    acceptance["passed"] = all(acceptance["checks"].values())
    return {
        "status": "completed",
        "version": profile["version"],
        "strategy": profile["strategy"],
        "cloud_calls": 0,
        "policy": profile["policy"],
        "model_load_seconds": elapsed,
        "dimension": len(profile["prototypes"][0][1]),
        "model_token_limit": encoder.model.max_seq_length,
        "calibration": profile["calibration"],
        "validation": profile["validation"],
        "test": test_score,
        "stress": stress_score,
        "gate": acceptance,
        "length": summarize(rows["length_results"]),
        **rows,
        "frozen_profile_sha256": profile_sha,
        **integrity_checks(directory, output, manifest, profile_sha),
        "packages": {
            name: importlib.metadata.version(name)
            for name in ("sentence-transformers", "torch", "transformers", "numpy")
        },
        "finished_utc": datetime.now(UTC).isoformat(),
    }


def run(model, output, *, spec=None):
    spec = spec or default_experiment()
    directory, output, manifest = prepare_experiment(model, output, spec)
    start = time.perf_counter()
    encoder = BgeEncoder(directory)
    load_seconds = time.perf_counter() - start
    profile, profile_sha, train = freeze_profile(output, spec, manifest, encoder)
    router = spec.router_type(
        encoder, profile["prototypes"], RoutePolicy(**profile["policy"])
    )
    for case in train[:5]:
        router.decide(case["text"], case["recent_context"])
    rows = heldout_and_stress(router, output, spec.case_reader)
    result = final_report(
        directory, output, manifest, profile, profile_sha, encoder, load_seconds, rows
    )
    write(output / "results.json", result)
    print(
        json.dumps({k: result[k] for k in ("status", "test", "stress", "gate")}),
        flush=True,
    )
    return result


def main(*, spec=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument(
        "--output",
        default=str(
            ROOT
            / "artifacts"
            / (
                "memory-route-"
                + datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
                + "-"
                + uuid.uuid4().hex[:6]
            )
        ),
    )
    args = parser.parse_args()
    try:
        run(args.model, args.output, spec=spec)
    except Exception as error:  # noqa: BLE001 -- CLI reports type only, never input-bearing exception text.
        print(
            json.dumps({"status": "failed", "error_type": type(error).__name__}),
            flush=True,
        )
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
