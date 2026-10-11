"""The Android and legacy Python behavior definitions come from one frozen file."""

import ast
import importlib.util
import json
from pathlib import Path

import pytest

from app.companion_contract_generated import CONTRACT_SHA256, RULES
from app.memory_contract import RetrievalState, completed_state, resolve_state
from app.prompt_rules import render_rules

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("generate_contract", ROOT / "shared/generate_companion_contract.py")
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


def test_generated_sources_match_the_single_source_exactly():
    assert GENERATOR.generate(check=True) == CONTRACT_SHA256
    source, _ = GENERATOR.read_contract()
    assert RULES == tuple((rule["id"], tuple(rule["applies_to"]), rule["text"]) for rule in source["rules"])
    assert len(RULES) == len({rule[0] for rule in RULES})


def test_schema_and_ranking_engines_are_explicitly_not_claimed_identical():
    source, _ = GENERATOR.read_contract()
    assert source["implementations"]["android"]["lifecycle"] == "product"
    assert source["implementations"]["python"]["lifecycle"] == "legacy_frozen"
    assert source["implementations"]["android"]["lexical_index"] != source["implementations"]["python"]["lexical_index"]
    assert set(source["retrieval_meanings"]) == {state.value for state in RetrievalState}


def test_stale_output_is_rejected_without_modifying_it(tmp_path, monkeypatch):
    python_file, kotlin_file = tmp_path / "output.py", tmp_path / "Output.kt"
    monkeypatch.setattr(GENERATOR, "PYTHON", python_file)
    monkeypatch.setattr(GENERATOR, "KOTLIN", kotlin_file)
    monkeypatch.setattr(GENERATOR, "ROOT", tmp_path)
    GENERATOR.generate()
    baseline = kotlin_file.read_bytes()
    python_file.write_text("# stale\n", encoding="utf-8")
    with pytest.raises(ValueError, match="stale companion contract"):
        GENERATOR.generate(check=True)
    assert python_file.read_text() == "# stale\n"
    assert kotlin_file.read_bytes() == baseline


def test_generator_preserves_quotes_newlines_and_kotlin_dollar_literal():
    text = '名字 "$abc"\n猫🐈\\字'
    source = {"rules": [{"id": "test", "applies_to": ["chat"], "text": text}]}
    tree = ast.parse(GENERATOR.python_source(source, "a" * 64))
    assert ast.literal_eval(tree.body[-1].value)[0][2] == text
    assert GENERATOR.kotlin_quote(text) == '"名字 \\"\\$abc\\"\\n猫🐈\\\\字"'


@pytest.mark.parametrize("completed,expected", [((True,), "ok"), ((False,), "partial"),
                                               ((True, False, True), "partial")])
def test_aggregating_actual_lookup_path_completion(completed, expected):
    assert completed_state(*completed).value == expected


@pytest.mark.parametrize("completed", [(), (None,), (1,), ("true",)])
def test_completion_observations_do_not_accept_truthy_values(completed):
    with pytest.raises(ValueError):
        completed_state(*completed)


def test_legacy_explicit_bool_is_a_deprecated_projection_and_omission_is_unknown():
    assert resolve_state(None, None) == RetrievalState.NOT_SEARCHED
    assert resolve_state(None, True) == RetrievalState.OK
    assert resolve_state(None, False) == RetrievalState.PARTIAL
    with pytest.raises(ValueError):
        resolve_state("not_searched", True)
    with pytest.raises(ValueError):
        resolve_state(None, "false")


def test_context_filtering_keeps_each_rule_once():
    rules = render_rules({"chat", "knowledge"})
    assert rules.count("[knowledge_scope]") == 1
    assert rules.count("[absence_is_not_evidence]") == 1
    assert "[legacy_memory_metadata]" not in rules
    assert "[knowledge_scope]" not in render_rules({"chat"})
    with pytest.raises(ValueError):
        render_rules({"invalid_context"})


def test_unknown_contract_version_does_not_generate_sources(tmp_path, monkeypatch):
    source = tmp_path / "contract.json"
    source.write_text(json.dumps({"version": 2}), encoding="utf-8")
    monkeypatch.setattr(GENERATOR, "SOURCE", source)
    with pytest.raises(ValueError, match="unsupported companion contract"):
        GENERATOR.read_contract()
