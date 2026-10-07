"""Frozen-head replay integrity and plumbing without real model inference."""

import sys
from types import SimpleNamespace

import pytest

from scripts import eval_memory_route as evaluation
from scripts import eval_memory_route_linear as linear
from scripts import replay_memory_route_linear as replay
from scripts.memory_route_linear_training import FIT_PARAMETERS


def profile_files(tmp_path):
    model = tmp_path / "model"
    model.mkdir()
    (model / "model.safetensors").write_bytes(b"FAKE WEIGHTS")
    profile = {
        "version": "memory-route-synthetic-v3",
        "positive_class": "present=1",
        "training_parameters": dict(FIT_PARAMETERS),
        "model_sha256": evaluation.model_hash(model)[0],
        "source_sha256": {
            p: evaluation.file_hash(evaluation.ROOT / p) for p in linear.SOURCE_PATHS
        },
        "prototypes": [["present", [1, 0]], ["retrieve", [0, 1]]],
        "head": {"coefficients": [8, -8], "intercept": 0},
        "policy": {"min_similarity": 0.5, "min_present_affinity": 0.61},
    }
    path = tmp_path / "profile.json"
    evaluation.write(path, profile)
    return model, path, profile


def test_immutable_profile_loads_without_refitting(tmp_path):
    model, path, profile = profile_files(tmp_path)
    assert replay.load_profile(path, evaluation.file_hash(path), model) == profile


@pytest.mark.parametrize(
    "change",
    [
        "digest",
        "model",
        "source",
        "source_digest",
        "version",
        "mapping",
        "parameters",
        "weights",
    ],
)
def test_changed_or_invalid_profile_cannot_be_replayed(tmp_path, change):
    model, path, profile = profile_files(tmp_path)
    expected = evaluation.file_hash(path)
    if change == "digest":
        expected = "0" * 64
    elif change == "model":
        profile["model_sha256"] = "wrong"
    elif change == "source":
        profile["source_sha256"] = {"../private.env": "wrong"}
    elif change == "source_digest":
        profile["source_sha256"][linear.SOURCE_PATHS[0]] = "wrong"
    elif change == "version":
        profile["version"] = "different-version"
    elif change == "mapping":
        profile["positive_class"] = "retrieve=1"
    elif change == "parameters":
        profile["training_parameters"]["C"] = 10
    else:
        profile["head"]["coefficients"] = [1]
    evaluation.write(path, profile)
    if change != "digest":
        expected = evaluation.file_hash(path)
    with pytest.raises(ValueError):
        replay.load_profile(path, expected, model)


def test_known_cases_have_separate_ids_and_cannot_be_reported_as_fresh_gold():
    rows = replay.known_cases()
    assert len(rows) == 75 and len({r["id"] for r in rows}) == 75
    assert sum(r["gold"] == "retrieve" for r in rows) == 45
    assert all(r["id"].startswith(("v1-", "v2-")) for r in rows)


def test_fake_replay_has_no_fit_or_threshold_selection(tmp_path, monkeypatch):
    model, path, profile = profile_files(tmp_path)
    monkeypatch.setattr(
        evaluation,
        "BgeEncoder",
        lambda directory: SimpleNamespace(
            fits_input=lambda text: True,
            encode_documents=lambda texts: [[0, 1] for text in texts],
        ),
    )
    result = replay.run(model, path, evaluation.file_hash(path), tmp_path / "output")
    assert (
        result["summary"]["count"] == 75
        and result["summary"]["important_false_skip"] == 0
    )
    assert result["regression_zero_important_false_skip"] and result["cloud_calls"] == 0
    assert result["profile_unchanged"] and result["source_files_unchanged"]
    assert (
        result["policy"] == profile["policy"]
        and "Known regression" in result["meaning"]
    )


def test_cli_failure_is_sanitized(monkeypatch, capsys):
    def fail(*args):
        raise ValueError("PRIVATE synthetic detail")

    monkeypatch.setattr(replay, "run", fail)
    monkeypatch.setattr(
        sys, "argv", ["replay", "--model", "m", "--profile", "p", "--expected-sha", "s"]
    )
    with pytest.raises(SystemExit) as error:
        replay.main()
    assert error.value.code == 1 and "PRIVATE" not in capsys.readouterr().out


def test_oversized_profile_is_rejected_before_json_decoding(tmp_path):
    model, path, _ = profile_files(tmp_path)
    path.write_bytes(b" " * (5 * 1024 * 1024 + 2))
    with pytest.raises(ValueError):
        replay.load_profile(path, evaluation.file_hash(path), model)


def test_profile_changed_during_load_cannot_reach_encoder(tmp_path, monkeypatch):
    model, path, _ = profile_files(tmp_path)
    original, loader = evaluation.file_hash(path), replay.load_profile

    def change(*args):
        profile = loader(*args)
        path.write_bytes(b"CHANGED TEST COPY")
        return profile

    monkeypatch.setattr(replay, "load_profile", change)
    monkeypatch.setattr(
        replay, "replay_rows", lambda *args: pytest.fail("must not encode changed head")
    )
    with pytest.raises(RuntimeError):
        replay.run(model, path, original, tmp_path / "output")
    assert not (tmp_path / "output" / "results.json").exists()


def test_profile_changed_during_replay_cannot_write_results(tmp_path, monkeypatch):
    model, path, _ = profile_files(tmp_path)
    original = evaluation.file_hash(path)

    def change(*args):
        path.write_bytes(b"CHANGED TEST COPY")
        return []

    monkeypatch.setattr(replay, "replay_rows", change)
    with pytest.raises(RuntimeError):
        replay.run(model, path, original, tmp_path / "output")
    assert not (tmp_path / "output" / "results.json").exists()
