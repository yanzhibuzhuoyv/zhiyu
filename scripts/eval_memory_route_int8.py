"""Frozen desktop INT8 candidate. Never connected to the Android application."""
import hashlib
import importlib.metadata
import json
import platform
import time
from dataclasses import asdict
from pathlib import Path

from scripts import eval_memory_route as evaluation
from scripts.memory_route_int8 import MODEL_IDENTITY, Int8QueryEncoder, ResearchRouter
from scripts.memory_route_int8_cases import (
    CALIBRATION,
    REGRESSION,
    TEST,
    TRAIN,
    VERSION,
    validate_splits,
)
from scripts.memory_route_linear_training import calibration
from scripts.memory_route_policy import route_input

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "artifacts/native-routing-int8-v1-r2"
SOURCES = ("scripts/memory_route_int8.py", "scripts/memory_route_int8_cases.py",
    "scripts/eval_memory_route_int8.py", "scripts/tests/test_memory_route_int8.py",
    "scripts/memory_route_linear.py", "scripts/memory_route_linear_training.py",
    "scripts/memory_route_policy.py", "scripts/eval_memory_route.py")


def write_frozen(path, value):
    raw = json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8")
    if path.exists() and path.read_bytes() != raw:
        raise ValueError("Frozen file differs; use a new research version")
    path.write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


def freeze_inputs():
    stats = validate_splits()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    sources = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in SOURCES}
    value = {"version": VERSION, "model_identity": MODEL_IDENTITY, "statistics": stats,
        "train": TRAIN, "calibration": CALIBRATION, "test": TEST, "seen_regression": REGRESSION,
        "source_sha256": sources, "gate": {"important_false_skip": 0, "daily_min_skip_rate": .20},
        "selection": "Fixed balanced L2 C=1 head; 450 threshold pairs on calibration ONLY; "
        "maximize safe daily skips, then higher affinity, then higher nearest support; "
        "Q independently qualifies and last four complete original context C only vetoes.",
        "hidden_history": "Oracle gold annotation only, NEVER encoded/fitted/classifier input.",
        "test_policy": "One heldout run; no parameter changes after the frozen profile."}
    return write_frozen(OUTPUT / "inputs.json", value)


def freeze_profile(encoder, input_sha):
    start = time.perf_counter()
    prototypes, coefficients, intercept, policy, summary, details = calibration(encoder, TRAIN, CALIBRATION)
    profile = {"version": VERSION, "model_identity": MODEL_IDENTITY, "input_sha256": input_sha,
        "policy": asdict(policy), "prototypes": prototypes, "coefficients": coefficients,
        "intercept": intercept, "calibration_summary": summary, "calibration_details": details,
        "elapsed_ms": round((time.perf_counter() - start) * 1000, 3)}
    # Timings do not define a frozen profile: preserve measured calibration separately.
    elapsed = profile.pop("elapsed_ms")
    sha = write_frozen(OUTPUT / "profile.json", profile)
    return ResearchRouter(encoder, prototypes, coefficients, intercept, policy, MODEL_IDENTITY), sha, elapsed


def category_summary(rows):
    categories = sorted({row["category"] for row in rows})
    return {category: evaluation.summarize([row for row in rows if row["category"] == category])
        for category in categories}


def actual_evaluation(router, rows):
    results = evaluation.evaluate(router, rows)
    return {"summary": evaluation.summarize(results), "by_category": category_summary(results),
        "results": results, "important_mis_skips": [row for row in results
            if row["gold"] == "retrieve" and row["decision"]["route"] == "present"]}


def numpy_scores(vector, matrix, coefficients, intercept):
    import numpy as np
    value = np.asarray(vector, dtype=np.float64)
    value /= np.linalg.norm(value)
    nearest = float(np.max(matrix @ value))
    logit = float(coefficients @ value) + intercept
    affinity = 1. / (1. + np.exp(-logit))
    return {"present_affinity": float(affinity), "nearest_similarity": nearest}


def dot_measurements(router, encoder):
    import numpy as np
    texts = list(dict.fromkeys(text for row in TEST for text in (
        row["text"], route_input(row["text"], row["recent_context"])) if encoder.fits_input(text)))
    vectors = encoder.encode_documents(texts)
    matrix = np.asarray([value for _, value in router.support.prototypes], dtype=np.float64)
    coefficients = np.asarray(router.coefficients, dtype=np.float64)
    scalar, fast = [], []
    # 3 warmups + 20 repeats over all heldout query/context representations.
    for _ in range(3):
        for vector in vectors:
            numpy_scores(vector, matrix, coefficients, router.intercept)
    maximum_error = 0.
    for vector in vectors:
        reference = router.scores(vector)
        actual = numpy_scores(vector, matrix, coefficients, router.intercept)
        maximum_error = max(maximum_error, *(abs(reference[k] - actual[k]) for k in reference))
        if max(abs(reference[k] - actual[k]) for k in reference) > 1e-7:
            raise ValueError("Fast dot path differs from frozen reference")
    for _ in range(20):
        for vector in vectors:
            start = time.perf_counter_ns()
            router.scores(vector)
            scalar.append((time.perf_counter_ns() - start) / 1e6)
            start = time.perf_counter_ns()
            numpy_scores(vector, matrix, coefficients, router.intercept)
            fast.append((time.perf_counter_ns() - start) / 1e6)
    return {"already_encoded_input_count": len(vectors), "measured_each_path": len(fast),
        "scalar_reference_ms": latency(scalar), "numpy_vectorized_ms": latency(fast),
        "max_score_difference": maximum_error, "prototype_bytes_float32": matrix.size * 4,
        "head_bytes_float32": (coefficients.size + 1) * 4,
        "notice": "Desktop-only incremental dots; excludes Q/C encoding, scanning, LLM and phone."}


def latency(values):
    return {"p50": evaluation.percentile(values, .5), "p95": evaluation.percentile(values, .95),
        "max": max(values)}


def main():
    input_sha = freeze_inputs()  # ALL splits and gold fixed before model initialization.
    print(json.dumps({"stage": "inputs_frozen", "sha256": input_sha,
        "statistics": validate_splits()}, ensure_ascii=False), flush=True)
    encoder = Int8QueryEncoder()
    router, profile_sha, calibration_ms = freeze_profile(encoder, input_sha)
    print(json.dumps({"stage": "profile_frozen_before_test", "sha256": profile_sha,
        "policy": asdict(router.policy), "calibration_ms": calibration_ms}), flush=True)
    test = actual_evaluation(router, TEST)
    regression = actual_evaluation(router, REGRESSION)
    dot = dot_measurements(router, encoder)
    passed = test["summary"]["important_false_skip"] == 0 and test["summary"]["present_skip_rate"] >= .20
    report = {"version": VERSION, "input_sha256": input_sha, "profile_sha256": profile_sha,
        "model_identity": MODEL_IDENTITY, "heldout_gate_pass": passed, "test": test,
        "seen_regression_separate": regression, "dot_only": dot,
        "environment": {"platform": platform.platform(), "processor": platform.processor(),
            "python": platform.python_version(), "versions": {name: importlib.metadata.version(name)
                for name in ("numpy", "scikit-learn", "onnxruntime", "transformers")}},
        "limits": "Synthetic desktop only. Zero finite errors is not a universal guarantee. "
        "No Android/production skip/phone latency/full pipeline speedup demonstrated."}
    write_frozen(OUTPUT / "report.json", report)
    print(json.dumps({"stage": "complete", "gate_pass": passed, "test": test["summary"],
        "seen_regression": regression["summary"], "dot_only": dot}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
