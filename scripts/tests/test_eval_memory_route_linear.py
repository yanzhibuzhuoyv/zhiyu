"""Third-candidate pipeline with a fake encoder/head, never real intent gold."""

import json
import sys
from types import SimpleNamespace

import pytest

from scripts import eval_memory_route as evaluation
from scripts import eval_memory_route_linear as linear
from scripts import memory_route_linear_training as training


def cases(split, count):
    return [
        {
            "id": split + str(i),
            "category": "invented-vector-contract",
            "text": ("current" if i % 2 == 0 else "history") + str(i),
            "recent_context": [],
            "gold": "present" if i % 2 == 0 else "retrieve",
        }
        for i in range(count)
    ]


def test_third_candidate_has_fixed_head_and_excludes_old_holdout_from_reader():
    spec = linear.experiment()
    assert (
        spec.version == "memory-route-synthetic-v3" and spec.context_prototypes is False
    )
    assert len(spec.sources) == 10 and dict(training.FIT_PARAMETERS)["C"] == 1
    assert dict(training.FIT_PARAMETERS)["l1_ratio"] == 0


def test_full_fake_pipeline_freezes_head_before_test_and_keeps_parameters(
    tmp_path, monkeypatch
):
    model = tmp_path / "model"
    model.mkdir()
    (model / "model.safetensors").write_bytes(b"FAKE WEIGHTS")
    output, accessed, fitting = tmp_path / "output", [], []

    def split(name):
        accessed.append(name)
        if name == "test":
            profile = json.loads(
                (output / "frozen-profile.json").read_text(encoding="utf-8")
            )
            assert profile["head"] == {"coefficients": [8, -8], "intercept": 0}
            assert profile["positive_class"] == "present=1"
        return cases(name, {"train": 6, "validation": 4, "test": 40}[name])

    def fit(prototypes):
        fitting.append(prototypes)
        assert len(prototypes) == 6
        return [8, -8], 0

    monkeypatch.setattr(linear, "cases_for_split", split)
    monkeypatch.setattr(training, "fit_head", fit)
    monkeypatch.setattr(
        evaluation,
        "BgeEncoder",
        lambda path: SimpleNamespace(
            fits_input=lambda text: len(text) <= 512,
            encode_documents=lambda texts: [
                [1, 0] if text.startswith("current") else [0, 1] for text in texts
            ],
            model=SimpleNamespace(max_seq_length=512),
        ),
    )
    monkeypatch.setattr(linear.importlib.metadata, "version", lambda name: "fake")
    result = linear.run(model, output)
    assert accessed == ["train", "validation", "test"] and len(fitting) == 1
    assert result["test"]["count"] == 40 and result["stress"]["count"] == 1600
    assert result["length"]["reason_counts"] == {"input_limit": 48}
    assert result["gate"]["passed"] and result["source_files_unchanged"]
    assert result["packages"]["scikit-learn"] == "fake" and result["cloud_calls"] == 0
    assert result["training_parameters"] == dict(training.FIT_PARAMETERS)


def test_incomplete_training_encoding_rejected_before_fit(monkeypatch):
    encoder = SimpleNamespace(
        fits_input=lambda text: text != "current0",
        encode_documents=lambda texts: [[1, 0] for text in texts],
    )
    monkeypatch.setattr(
        training, "fit_head", lambda _: pytest.fail("must not fit partial train")
    )
    with pytest.raises(ValueError):
        training.calibration(encoder, cases("train", 6), cases("validation", 4))


def test_cli_errors_only_print_type(monkeypatch, capsys):
    def fail(*args):
        raise ValueError("PRIVATE synthetic detail")

    monkeypatch.setattr(linear, "run", fail)
    monkeypatch.setattr(sys, "argv", ["eval-linear", "--model", "synthetic"])
    with pytest.raises(SystemExit) as error:
        linear.main()
    assert error.value.code == 1 and "PRIVATE" not in capsys.readouterr().out
