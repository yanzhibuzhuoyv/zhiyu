"""Experiment integrity and fail-closed gates; invented vectors, no real model."""

import json
import sys
from types import SimpleNamespace

import pytest

from scripts import eval_memory_route as evaluation
from scripts.memory_route_policy import PrototypeRouter, RoutePolicy


class Encoder:
    def fits_input(self, text):
        return len(text) <= 512

    def encode_documents(self, texts):
        return [[1.0, 0.0] if "present-shape" in text else [0.0, 1.0] for text in texts]


def cases(prefix, count=2):
    return [
        {
            "id": prefix + str(i),
            "category": "synthetic-vector-contract",
            "text": ("present-shape" if i % 2 == 0 else "retrieve-shape") + str(i),
            "recent_context": [],
            "gold": "present" if i % 2 == 0 else "retrieve",
        }
        for i in range(count)
    ]


def test_validation_selection_never_receives_test_data():
    prototypes, policy, score, details = evaluation.calibration(
        Encoder(), cases("tr"), cases("va")
    )
    assert len(prototypes) == 2 and score["important_false_skip"] == 0
    assert score["present_skipped"] == 1 and details["grid_candidates"] == 756
    assert policy.min_margin == 0.20 and policy.min_similarity == 0.95
    assert score["milliseconds_p50"] is None and score["milliseconds_p95"] is None
    assert evaluation.summarize(details["validation_results"]) == score
    assert len(details["validation_scores"]) == 2


def test_unknown_or_overlong_calibration_inputs_cannot_be_skipped():
    encoder = Encoder()
    encoder.fits_input = lambda text: text != "present-shape0"
    _, _, score, _ = evaluation.calibration(encoder, cases("tr", 4), cases("va"))
    assert score["present_skipped"] == 0 and score["important_false_skip"] == 0


def test_scores_counts_do_not_treat_always_retrieve_as_good_optimization():
    rows = [
        {
            "gold": gold,
            "decision": {"route": "retrieve", "reason": "uncertain"},
            "milliseconds": i,
        }
        for i, gold in enumerate(["present", "retrieve", "present"])
    ]
    summary = evaluation.summarize(rows)
    assert summary["important_false_skip"] == 0 and summary["present_skip_rate"] == 0
    assert summary["retrieval_count"] == 3 and summary["milliseconds_p95"] == 2


@pytest.mark.parametrize(
    "field,value",
    [
        ("important_false_skip", 1),
        ("present_skip_rate", 0.19),
        ("milliseconds_p95", 100.001),
        ("milliseconds_p95", None),
    ],
)
def test_independent_gate_rejects_each_test_failure(field, value):
    good = {
        "important_false_skip": 0,
        "present_skip_rate": 0.20,
        "milliseconds_p95": 100,
    }
    result = evaluation.gate(good, {**good, field: value}, good)
    assert not result["passed"]


def test_stress_failure_or_validation_failure_blocks_gate():
    good = {
        "important_false_skip": 0,
        "present_skip_rate": 0.20,
        "milliseconds_p95": 100,
    }
    assert not evaluation.gate({**good, "important_false_skip": 1}, good, good)[
        "passed"
    ]
    assert not evaluation.gate(good, good, {**good, "important_false_skip": 1})[
        "passed"
    ]


def test_stress_has_1000_derived_variants_and_does_not_mutate_base_gold():
    original = cases("te", 25)
    before = json.dumps(original)
    stress = evaluation.stress_cases(original)
    assert len(stress) == 1000 and len({r["id"] for r in stress}) == 1000
    assert json.dumps(original) == before
    assert all(r["gold"] == original[i // 40]["gold"] for i, r in enumerate(stress))


def test_profile_written_before_holdout_and_unchanged_after_fake_full_run(
    tmp_path, monkeypatch
):
    model = tmp_path / "model"
    model.mkdir()
    (model / "model.safetensors").write_bytes(b"FAKE TEST WEIGHTS")
    output = tmp_path / "output"
    accessed = []

    def split(name):
        if name == "test":
            assert (output / "frozen-profile.json").is_file()
            accessed.append(name)
        return cases(name, 25 if name == "test" else 2)

    monkeypatch.setattr(evaluation, "cases_for_split", split)
    monkeypatch.setattr(
        evaluation,
        "BgeEncoder",
        lambda path: SimpleNamespace(
            fits_input=Encoder().fits_input,
            encode_documents=Encoder().encode_documents,
            model=SimpleNamespace(max_seq_length=512),
        ),
    )
    monkeypatch.setattr(
        evaluation.importlib.metadata, "version", lambda name: "fake-test"
    )
    result = evaluation.run(model, output)
    assert (
        accessed == ["test"]
        and result["source_files_unchanged"]
        and result["profile_unchanged"]
    )
    assert (
        result["model_files_unchanged"]
        and result["cloud_calls"] == 0
        and result["gate"]["passed"]
    )
    assert result["test"]["count"] == 25 and result["stress"]["count"] == 1000
    assert (
        evaluation.file_hash(output / "frozen-profile.json")
        == result["frozen_profile_sha256"]
    )


def test_existing_output_or_model_directory_never_overwritten(tmp_path):
    model = tmp_path / "model"
    model.mkdir()
    (model / "model.safetensors").write_bytes(b"FAKE")
    original = evaluation.file_hash(model / "model.safetensors")
    for output in (model, tmp_path, model / "child"):
        with pytest.raises(ValueError):
            evaluation.run(model, output)
    assert evaluation.file_hash(model / "model.safetensors") == original


def test_incomplete_model_is_rejected(tmp_path):
    with pytest.raises(ValueError):
        evaluation.model_hash(tmp_path)


def test_bge_wrapper_is_offline_and_checks_actual_special_token_length(
    monkeypatch, tmp_path
):
    calls = []
    model = SimpleNamespace(
        max_seq_length=5,
        tokenizer=lambda text, **kwargs: {"input_ids": [0] * (len(text) + 2)},
    )
    encoder = SimpleNamespace(
        _model=model, encode_documents=lambda texts: calls.append(texts) or [[1.0, 0.0]]
    )
    monkeypatch.setitem(
        sys.modules,
        "torch",
        SimpleNamespace(
            set_num_threads=lambda count: calls.append(("threads", count)),
            set_num_interop_threads=lambda count: calls.append(("interop", count)),
        ),
    )
    monkeypatch.setitem(
        sys.modules,
        "app.rag_semantic",
        SimpleNamespace(LocalSemanticEncoder=lambda path: encoder),
    )
    for key in ("HF_HUB_OFFLINE", "TRANSFORMERS_OFFLINE", "TOKENIZERS_PARALLELISM"):
        monkeypatch.setenv(key, "test")
    wrapper = evaluation.BgeEncoder(tmp_path)
    assert wrapper.fits_input("🙂中a") and not wrapper.fits_input("🙂中ab")
    assert wrapper.encode_documents(["完整原文"]) == [[1.0, 0.0]]
    assert ["完整原文"] in calls and ("threads", 2) in calls and ("interop", 1) in calls
    assert evaluation.os.environ["HF_HUB_OFFLINE"] == "1"


def test_empty_percentile_and_cli_failure_are_safe(monkeypatch, capsys):
    assert evaluation.percentile([], 0.95) is None

    def fail(*args, **kwargs):
        raise ValueError("PRIVATE synthetic detail")

    monkeypatch.setattr(evaluation, "run", fail)
    monkeypatch.setattr(sys, "argv", ["eval-memory-route", "--model", "synthetic"])
    with pytest.raises(SystemExit) as error:
        evaluation.main()
    assert error.value.code == 1 and "PRIVATE" not in capsys.readouterr().out


def test_score_replay_has_same_context_veto_as_live_path():
    router = PrototypeRouter(
        Encoder(), [("present", [1, 0]), ("retrieve", [0, 1])], RoutePolicy()
    )
    assert (
        router.from_scores(
            [{"present": 1, "retrieve": 0}, {"present": 0, "retrieve": 1}]
        ).reason
        == "context_veto"
    )
    for scores in (
        [],
        [{}],
        [{"present": float("nan"), "retrieve": 0}],
        [{"present": 2, "retrieve": 0}],
    ):
        with pytest.raises(ValueError):
            router.from_scores(scores)


def test_context_never_counts_as_another_training_case_for_consensus():
    train = cases("tr", 4)
    train[0]["recent_context"] = [{"role": "assistant", "content": "另一条合成原话"}]
    cache = evaluation.calibration_cache(Encoder(), train, cases("va"))
    assert len(evaluation.training_prototypes(train, cache, False)) == 4
    assert len(evaluation.training_prototypes(train, cache, True)) == 5


def test_frozen_source_copy_tampering_is_explicit_error(tmp_path):
    model = tmp_path / "model"
    model.mkdir()
    (model / "model.safetensors").write_bytes(b"FAKE")
    output = tmp_path / "output"
    directory, destination, manifest = evaluation.prepare_experiment(
        model, output, evaluation.default_experiment()
    )
    evaluation.write(destination / "frozen-profile.json", {"policy": "fake"})
    profile_sha = evaluation.file_hash(destination / "frozen-profile.json")
    path = destination / "frozen-source" / evaluation.SOURCE_PATHS[0]
    path.write_bytes(b"MODIFIED TEST COPY")
    with pytest.raises(RuntimeError):
        evaluation.integrity_checks(directory, destination, manifest, profile_sha)


def test_long_input_suite_covers_current_and_context_without_exceeding_api_limit():
    rows = evaluation.length_cases()
    assert len(rows) == 48 and len({r["id"] for r in rows}) == 48
    assert sum(bool(r["recent_context"]) for r in rows) == 16
    assert all(len(r["text"]) <= 16000 for r in rows)
    assert all(
        len(message["content"]) <= 16000
        for r in rows
        for message in r["recent_context"]
    )
    assert all(
        len(evaluation.route_input(r["text"], r["recent_context"])) > 512 for r in rows
    )
