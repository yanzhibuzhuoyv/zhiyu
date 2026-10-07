"""Second-candidate wiring with synthetic vector contracts, no real model."""

from dataclasses import replace
from types import SimpleNamespace

from scripts import eval_memory_route as evaluation
from scripts.eval_memory_route_v2 import experiment
from scripts.memory_route_consensus import ConsensusRouter


def synthetic_cases(split, count):
    return [
        {
            "id": split + str(index),
            "category": "invented-vector-contract",
            "text": ("current" if index % 2 == 0 else "history") + str(index),
            "recent_context": [],
            "gold": "present" if index % 2 == 0 else "retrieve",
        }
        for index in range(count)
    ]


def test_second_candidate_is_fixed_and_excludes_context_prototypes():
    spec = experiment()
    assert spec.router_type is ConsensusRouter and spec.context_prototypes is False
    assert (
        "third present" in spec.strategy and spec.version == "memory-route-synthetic-v2"
    )
    assert len(spec.sources) == 7 and "scripts/memory_route_cases_v2.py" in spec.sources


def test_v2_fake_full_run_freezes_before_holdout_and_keeps_every_artifact(
    tmp_path, monkeypatch
):
    model = tmp_path / "model"
    model.mkdir()
    (model / "model.safetensors").write_bytes(b"INVENTED WEIGHTS")
    output = tmp_path / "output"
    accessed = []

    def split(name):
        if name == "test":
            assert (output / "frozen-profile.json").is_file()
            accessed.append(name)
        return synthetic_cases(name, {"train": 6, "validation": 4, "test": 50}[name])

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
    monkeypatch.setattr(
        evaluation.importlib.metadata, "version", lambda name: "fake-test"
    )
    result = evaluation.run(
        model, output, spec=replace(experiment(), case_reader=split)
    )
    assert accessed == ["test"] and result["cloud_calls"] == 0
    assert result["test"]["count"] == 50 and result["stress"]["count"] == 2000
    assert result["calibration"]["prototype_count"] == 6
    assert result["gate"]["passed"] and result["source_files_unchanged"]
    assert all(
        (output / "frozen-source" / path).is_file() for path in experiment().sources
    )
