"""Replay a frozen head on previously seen case banks; no fitting or tuning."""

import argparse
import hashlib
import json
import uuid
from datetime import UTC, datetime
from pathlib import Path

from scripts import eval_memory_route as evaluation
from scripts import eval_memory_route_linear as linear
from scripts.memory_route_cases import cases_for_split as first_cases
from scripts.memory_route_cases_v2 import cases_for_split as second_cases
from scripts.memory_route_linear import LinearPolicy, LinearRouter
from scripts.memory_route_linear_training import FIT_PARAMETERS


def load_profile(path, expected_sha, model):
    path, directory = Path(path), Path(model).resolve(strict=True)
    with path.open("rb") as source:
        raw = source.read(5 * 1024 * 1024 + 1)
    if len(raw) > 5 * 1024 * 1024 or hashlib.sha256(raw).hexdigest() != expected_sha:
        raise ValueError("冻结分类头SHA或大小无效")
    profile = json.loads(raw)
    if (
        not isinstance(profile, dict)
        or profile.get("version") != "memory-route-synthetic-v3"
    ):
        raise ValueError("冻结分类头版本无效")
    if profile.get("positive_class") != "present=1" or profile.get(
        "training_parameters"
    ) != dict(FIT_PARAMETERS):
        raise ValueError("分类头类别或固定训练参数变更")
    sources = profile.get("source_sha256")
    if not isinstance(sources, dict) or set(sources) != set(linear.SOURCE_PATHS):
        raise ValueError("冻结来源集合无效")
    if any(
        evaluation.file_hash(evaluation.ROOT / p) != digest
        for p, digest in sources.items()
    ):
        raise ValueError("冻结来源SHA变更")
    if profile.get("model_sha256") != evaluation.model_hash(directory)[0]:
        raise ValueError("模型SHA与冻结时不一致")
    LinearRouter(
        None,
        profile["prototypes"],
        profile["head"]["coefficients"],
        profile["head"]["intercept"],
        LinearPolicy(**profile["policy"]),
    )
    return profile


def known_cases():
    return [{**case, "id": "v1-" + case["id"]} for case in first_cases("test")] + [
        {**case, "id": "v2-" + case["id"]} for case in second_cases("test")
    ]


def replay_rows(directory, output, profile):
    encoder = evaluation.BgeEncoder(directory)
    router = LinearRouter(
        encoder,
        profile["prototypes"],
        profile["head"]["coefficients"],
        profile["head"]["intercept"],
        LinearPolicy(**profile["policy"]),
    )
    inputs = known_cases()
    evaluation.write(output / "frozen-known-inputs.json", inputs)
    for case in inputs[:5]:
        router.decide(case["text"], case["recent_context"])
    return evaluation.evaluate(router, inputs)


def finish(directory, output, manifest, profile, expected_sha, replay_sha, rows):
    summary = evaluation.summarize(rows)
    result = {
        "status": "completed",
        "cloud_calls": 0,
        "version": manifest["version"],
        "meaning": "Known regression only; not an independent heldout or production activation.",
        "frozen_input_profile_sha256": expected_sha,
        "policy": profile["policy"],
        "summary": summary,
        "results": rows,
        "regression_zero_important_false_skip": summary["important_false_skip"] == 0,
        **evaluation.integrity_checks(directory, output, manifest, replay_sha),
    }
    evaluation.write(output / "results.json", result)
    print(
        json.dumps(
            {
                "status": result["status"],
                "summary": summary,
                "regression_zero_important_false_skip": result[
                    "regression_zero_important_false_skip"
                ],
            }
        ),
        flush=True,
    )
    return result


def run(model, profile_path, expected_sha, output):
    profile = load_profile(profile_path, expected_sha, model)
    spec = evaluation.Experiment(
        "memory-route-v3-known-regression",
        None,
        lambda: {"test": {"count": 75}},
        linear.SOURCE_PATHS + ("scripts/replay_memory_route_linear.py",),
        context_prototypes=False,
        strategy="frozen v3 head; known v1/v2 regression only",
    )
    directory, output, manifest = evaluation.prepare_experiment(model, output, spec)
    (output / "frozen-profile.json").write_bytes(Path(profile_path).read_bytes())
    replay_sha = evaluation.file_hash(output / "frozen-profile.json")
    if (
        replay_sha != expected_sha
        or json.loads((output / "frozen-profile.json").read_bytes()) != profile
    ):
        raise RuntimeError("冻结策略在加载与复制期间变更")
    rows = replay_rows(directory, output, profile)
    if evaluation.file_hash(profile_path) != expected_sha:
        raise RuntimeError("原冻结策略在重放期间变更")
    return finish(directory, output, manifest, profile, expected_sha, replay_sha, rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument(
        "--output",
        default=str(
            evaluation.ROOT
            / "artifacts"
            / (
                "memory-route-regression-"
                + datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
                + "-"
                + uuid.uuid4().hex[:6]
            )
        ),
    )
    args = parser.parse_args()
    try:
        run(args.model, args.profile, args.expected_sha, args.output)
    except Exception as error:  # noqa: BLE001 -- Do not log input-bearing exception messages.
        print(
            json.dumps({"status": "failed", "error_type": type(error).__name__}),
            flush=True,
        )
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
