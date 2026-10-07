"""Predeclared fixed L2 intent head with a fresh validation and holdout."""

import argparse
import importlib.metadata
import json
import time
import uuid
from dataclasses import asdict
from datetime import UTC, datetime

from scripts import eval_memory_route as evaluation
from scripts.memory_route_cases_v3 import VERSION, cases_for_split, split_statistics
from scripts.memory_route_linear import LinearPolicy, LinearRouter
from scripts.memory_route_linear_training import FIT_PARAMETERS, calibration

SOURCE_PATHS = evaluation.SOURCE_PATHS + (
    "scripts/memory_route_cases_v2.py",
    "scripts/memory_route_cases_v3.py",
    "scripts/memory_route_linear.py",
    "scripts/memory_route_linear_training.py",
    "scripts/eval_memory_route_linear.py",
    "scripts/requirements-memory-route.txt",
)


def experiment():
    return evaluation.Experiment(
        VERSION,
        cases_for_split,
        split_statistics,
        SOURCE_PATHS,
        context_prototypes=False,
        strategy="fixed L2 logistic intent head plus nearest-distance veto; Q plus C veto",
    )


def freeze(output, manifest, encoder):
    train, validation = cases_for_split("train"), cases_for_split("validation")
    prototypes, coefficients, intercept, policy, summary, details = calibration(
        encoder, train, validation
    )
    profile = {
        "version": VERSION,
        "strategy": manifest["strategy"],
        "policy": asdict(policy),
        "prototypes": prototypes,
        "head": {"coefficients": coefficients, "intercept": intercept},
        "training_parameters": dict(FIT_PARAMETERS),
        "positive_class": "present=1",
        "model_sha256": manifest["model_sha256"],
        "source_sha256": manifest["source_sha256"],
        "calibration": details,
        "validation": summary,
        "frozen_before_test": True,
    }
    evaluation.write(output / "frozen-profile.json", profile)
    print(
        json.dumps(
            {"stage": "profile_frozen", "policy": asdict(policy), "validation": summary}
        ),
        flush=True,
    )
    return profile, evaluation.file_hash(output / "frozen-profile.json"), train


def run(model, output):
    directory, output, manifest = evaluation.prepare_experiment(
        model, output, experiment()
    )
    manifest["training_parameters"] = dict(FIT_PARAMETERS)
    manifest["threshold_grid"] = {
        "similarity": [0.50, 0.90, 0.05],
        "affinity": [0.50, 0.99, 0.01],
    }
    evaluation.write(output / "manifest.json", manifest)
    start = time.perf_counter()
    encoder = evaluation.BgeEncoder(directory)
    elapsed = time.perf_counter() - start
    profile, profile_sha, train = freeze(output, manifest, encoder)
    router = LinearRouter(
        encoder,
        profile["prototypes"],
        profile["head"]["coefficients"],
        profile["head"]["intercept"],
        LinearPolicy(**profile["policy"]),
    )
    for case in train[:5]:
        router.decide(case["text"], case["recent_context"])
    rows = evaluation.heldout_and_stress(router, output, cases_for_split)
    result = evaluation.final_report(
        directory, output, manifest, profile, profile_sha, encoder, elapsed, rows
    )
    result["training_parameters"] = profile["training_parameters"]
    result["positive_class"] = profile["positive_class"]
    result["packages"]["scikit-learn"] = importlib.metadata.version("scikit-learn")
    evaluation.write(output / "results.json", result)
    print(
        json.dumps(
            {k: result[k] for k in ("status", "test", "stress", "length", "gate")}
        ),
        flush=True,
    )
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument(
        "--output",
        default=str(
            evaluation.ROOT
            / "artifacts"
            / (
                "memory-route-linear-"
                + datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
                + "-"
                + uuid.uuid4().hex[:6]
            )
        ),
    )
    args = parser.parse_args()
    try:
        run(args.model, args.output)
    except Exception as error:  # noqa: BLE001 -- CLI avoids input-bearing exception messages.
        print(
            json.dumps({"status": "failed", "error_type": type(error).__name__}),
            flush=True,
        )
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
