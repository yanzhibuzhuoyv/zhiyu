"""Accounting contracts are independent of model accuracy."""

import asyncio
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from scripts import eval_memory_retrieval_ab as evaluation
from scripts import observe_memory_retrieval_ab as live
from scripts.memory_retrieval_ab import ResearchEncoder, checked_spans, checked_vectors
from scripts.tests.test_memory_retrieval_ab import Encoder, messages


def case():
    return {
        "id": "test",
        "category": "exact_identifier",
        "history": messages(),
        "query": "蓝陶课程",
        "expected_ids": ["u"],
        "expected_quotes": {"u": "蓝陶课程"},
        "forbidden_ids": ["a"],
        "answer_expectation": "只按用户原话核对。",
    }


def test_sources_require_quote_not_only_id_and_unknown_is_not_success():
    item = case()
    refs = [{"message_id": "u", "role": "user", "text": "我报名"}]
    assert not evaluation.source_availability(item, refs)["all_needed_available"]
    refs[0]["text"] = "蓝陶课程"
    assert evaluation.source_availability(item, refs)["all_needed_available"]
    item["expected_quotes"] = {}
    assert not evaluation.source_availability(item, refs)["all_needed_available"]


@pytest.mark.parametrize(
    "alteration",
    [
        {"role": "assistant"},
        {"created_at": "wrong"},
        {"text": "改写"},
        {"char_start": -1},
        {"message_id": "foreign"},
    ],
)
def test_reference_verification_rejects_wrong_sources(alteration):
    ref = {
        "message_id": "u",
        "role": "user",
        "created_at": messages()[0]["created_at"],
        "char_start": 0,
        "char_end": 2,
        "text": "我报",
    }
    with pytest.raises(ValueError):
        evaluation.verify_refs(messages(), [{**ref, **alteration}])


def test_case_and_growth_roundtrip_only_factual_metrics(tmp_path):
    rows = evaluation.evaluate_case(case(), Encoder(), tmp_path, 2)
    report = evaluation.summarize(rows)
    assert len(rows) == 3 and report["both"]["all_needed_available"] == 1
    assert all(r["originals_identical"] for r in rows)
    assert (tmp_path / "test.sqlite").exists()
    result = evaluation.growth_probe(10, case(), Encoder(), tmp_path, 2)
    assert result["messages"] == 22 and result["originals_identical"]
    assert result["files_bytes"]["derived-vectors.npy"] > result["vector_payload_bytes"]


def test_document_cache_and_actual_token_windows(monkeypatch):
    class Base:
        def __init__(self, _directory):
            self.model = self
            self.encoder = self
            self._query_prefix = "指令："
            self.calls = []

        def tokenizer(self, text, **kwargs):
            return {"offset_mapping": [(i, i + 1) for i in range(len(text))]}

        def fits_input(self, text):
            return len(text) <= 512

        def encode_documents(self, texts):
            self.calls.append(texts)
            return [[1, 0] for _ in texts]

        def encode_query(self, text):
            return [1, 0]

    monkeypatch.setattr("scripts.eval_memory_route.BgeEncoder", Base)
    encoder = ResearchEncoder(Path("synthetic"))
    text = "完整原文" * 500
    spans = checked_spans(text, encoder)
    assert spans[0][0] == 0 and spans[-1][1] == len(text)
    assert checked_spans(text, encoder, query=True) == spans
    assert encoder.encode_documents(["甲", "甲", "乙"]) == [[1, 0]] * 3
    encoder.encode_documents(["乙"])
    assert encoder.base.calls == [["甲", "乙"]]
    assert encoder.encode_query("查询") == [1, 0]
    encoder.base.fits_input = lambda _: False
    with pytest.raises(ValueError):
        encoder.spans("拒绝截尾")


@pytest.mark.parametrize("spans", [[], [(1, 2)], [(0, 1)], [(0, 2), (1, 2)], [(False, 2)]])
def test_incomplete_overlapping_or_bad_windows_are_rejected(spans):
    class Broken:
        def spans(self, text, **kwargs):
            return spans

    with pytest.raises(ValueError):
        checked_spans("原文", Broken())


@pytest.mark.parametrize("values", [[], [[0, 0]], [[2, 0]], [[1, float("inf")]], [[1]]])
def test_nonfinite_unnormalized_and_wrong_dimension_are_rejected(values):
    with pytest.raises(ValueError):
        checked_vectors(values, 1, 2)


def test_live_prompt_time_is_fixed_and_order_is_counterbalanced():
    from scripts.memory_retrieval_cases import build_cases

    bank = build_cases()
    trials = live.schedule(bank)
    assert len(trials) == 104
    assert trials[0]["order"] != trials[2]["order"]
    assert all(t["mode"] == "enabled" for t in trials[-8:])
    a, b = live.prompt_for("核对原话", []), live.prompt_for("核对原话", [])
    assert a == b and "2026-10-07T00:00:00+00:00" in a[0]["content"]
    assert "时间" not in json.dumps(trials)


def test_extended_output_setting_does_not_replace_original_search_question(monkeypatch):
    calls = []

    class Index:
        originals = ()

        def retrieve(self, query, order, *, scope):
            calls.append(query)
            return {"references": []}

    async def reply(client, key, trial):
        return {"content": "测试完整回复", "category": "reply"}

    monkeypatch.setattr(live, "run_stream_trial", reply)
    trial = {"case_id": "test", "order": "lexical-first", "mode": "enabled", "extended": True}
    result = asyncio.run(live.call_one(None, "synthetic-key", case(), trial, Index()))
    assert calls == [case()["query"]]
    assert result["search_query"] == case()["query"]
    assert live.EXTENDED in result["messages"][-1]["content"]
    assert live.EXTENDED not in result["search_query"]
    assert result["category"] == case()["category"]
    assert result["transport_category"] == "reply"


def test_prepare_freezes_synthetic_inputs_and_sources(tmp_path, monkeypatch):
    monkeypatch.setattr(evaluation, "build_cases", lambda: [case()])
    monkeypatch.setattr(evaluation, "model_hash", lambda _: ("synthetic-model-hash", {}))
    output = tmp_path / "frozen"
    bank, hashes = evaluation.prepare(output, tmp_path / "model", 2, [10])
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    assert bank == [case()] and manifest["candidate_fragment_budget"] == 16
    assert "scripts/memory_retrieval_ab.py" in hashes
    assert (output / "frozen-source/scripts/memory_retrieval_ab.py").exists()


def test_case_index_changes_scope_and_closes_previous_database():
    first = case()
    current, index = live.case_index(None, None, first, Encoder())
    assert live.case_index(current, index, first, Encoder()) == (current, index)
    second = {**case(), "id": "second"}
    current, replacement = live.case_index(current, index, second, Encoder())
    assert current == "second" and replacement.scope == "second"
    with pytest.raises(Exception, match="closed"):
        index.db.execute("SELECT 1")
    replacement.db.close()


@pytest.mark.parametrize("failure", [False, True, "credential-change", "secret-leak"])
def test_full_live_execution_preserves_complete_or_failed_reports(tmp_path, monkeypatch, failure):
    output = tmp_path / "output"
    output.mkdir()
    credential_file = tmp_path / "synthetic.env"
    credential_file.write_text("synthetic", encoding="utf-8")
    key = "synthetic-secret-key"
    credentials = SimpleNamespace(
        key=key, file_sha256=hashlib.sha256(credential_file.read_bytes()).hexdigest()
    )
    args = SimpleNamespace(output=output, credentials=credential_file, model=tmp_path / "model")
    monkeypatch.setattr(live, "ResearchEncoder", lambda _: Encoder())

    async def reply(client, secret, trial):
        assert secret == key
        if failure == "credential-change":
            credential_file.write_text("changed", encoding="utf-8")
        return {
            "case_id": "test",
            "content": key if failure == "secret-leak" else "蓝陶课程。",
            "grade": {"usable_pass": failure is not True},
            "done_received": failure is not True,
            "finish_reason": "stop" if failure is not True else None,
        }

    monkeypatch.setattr(live, "run_stream_trial", reply)
    trials = [
        {"case_id": "test", "order": o, "mode": "disabled", "extended": False}
        for o in evaluation.ORDERS[:2]
    ]
    task = live.execute(args, [case()], credentials, trials, {})
    if failure:
        with pytest.raises(ValueError if failure == "secret-leak" else RuntimeError):
            asyncio.run(task)
        if failure != "secret-leak":
            assert (
                json.loads((output / "results.json").read_text(encoding="utf-8"))["complete"]
                is False
            )
    else:
        asyncio.run(task)
        report = json.loads((output / "results.json").read_text(encoding="utf-8"))
        assert report["complete"] and report["returned_calls"] == 2
        assert report["trials"][0]["history_sha256"] == report["trials"][1]["history_sha256"]
        assert key not in (output / "results.json").read_text(encoding="utf-8")
