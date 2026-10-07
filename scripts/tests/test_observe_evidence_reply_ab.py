"""Independent single-call arms, frozen gold, and explicit unusable outputs."""

import asyncio
import hashlib
import json
from types import SimpleNamespace

import pytest

from scripts import observe_evidence_reply_ab as live
from scripts.evidence_reply_cases import build_cases
from scripts.tests.test_memory_retrieval_ab import Encoder


def test_balanced_schedule_and_long_output_settings():
    trials = live.schedule(build_cases())
    assert len(trials) == 144
    assert sum(t["extended"] for t in trials) == 16
    for start in range(0, len(trials), 4):
        group = trials[start : start + 4]
        assert len({t["case_id"] for t in group}) == 1
        assert {(t["order"], t["method"]) for t in group} == {
            (o, m) for o in live.ORDERS[:2] for m in ("baseline", "structured")
        }
        assert all(t["mode"] == ("enabled" if t["extended"] else "disabled") for t in group)


def test_format_adapter_only_changes_response_format():
    seen = []

    class Client:
        def stream(self, method, url, **kwargs):
            seen.append((method, url, kwargs))
            return "synthetic-context"

    body = {"messages": [{"role": "user", "content": "json"}], "stream": True}
    for structured in (False, True):
        assert (
            live.FormatClient(Client(), structured).stream("POST", "synthetic", json=body)
            == "synthetic-context"
        )
    assert body == {"messages": [{"role": "user", "content": "json"}], "stream": True}
    assert seen[0][2]["json"] == body
    assert seen[1][2]["json"] == {**body, "response_format": {"type": "json_object"}}


def fake_generate(keyfile, key, failure, seen):
    async def generate(client, _key, trial):
        messages = trial["case"]["messages"]
        text = json.dumps(messages, ensure_ascii=False)
        assert not any(
            k in text
            for k in ("answer_expectation", "oracle_notes", "expected_quotes", "acceptable_scopes")
        )
        assert "2026-10-07T04:00:00+00:00" in text
        assert "sentinel-generated-answer" not in text
        seen.append(messages)
        if failure == "credential":
            keyfile.write_text("changed", encoding="utf-8")
        natural = key if failure == "secret" else "sentinel-generated-answer"
        if failure == "overlong":
            natural = "长" * 64001
        content = (
            json.dumps(
                {"version": "evidence-reply-v1", "segments": [{"kind": "natural", "text": natural}]}
            )
            if client.structured
            else natural
        )
        if failure == "parse" and client.structured:
            content = "invalid json"
        return {
            "content": content,
            "grade": {"usable_pass": failure != "transport"},
            "latency_seconds": 0.2,
            "first_content_seconds": 0.1,
            "done_received": True,
            "finish_reason": "stop",
        }

    return generate


@pytest.mark.parametrize(
    "failure", [None, "transport", "parse", "overlong", "credential", "secret"]
)
def test_one_call_each_no_gold_or_previous_answer_leak(tmp_path, monkeypatch, failure):
    output = tmp_path / "results"
    output.mkdir()
    keyfile = tmp_path / "env"
    keyfile.write_text("synthetic", encoding="utf-8")
    key = "evidence-synthetic-secret"
    credentials = SimpleNamespace(
        key=key, file_sha256=hashlib.sha256(keyfile.read_bytes()).hexdigest()
    )
    args = SimpleNamespace(output=output, credentials=keyfile, model=tmp_path / "model")
    monkeypatch.setattr(live, "ResearchEncoder", lambda _: Encoder())
    seen = []

    generate = fake_generate(keyfile, key, failure, seen)

    monkeypatch.setattr(live, "run_stream_trial", generate)
    bank = build_cases()[:1]
    trials = live.schedule(bank)[:4]
    task = live.execute(args, bank, credentials, trials, {})
    if failure in {"credential", "secret"}:
        with pytest.raises(RuntimeError if failure == "credential" else ValueError):
            asyncio.run(task)
        return
    success = asyncio.run(task)
    assert success == (failure is None)
    report = json.loads((output / "results.json").read_text(encoding="utf-8"))
    assert report["complete"] and report["returned_calls"] == 4
    assert len(seen) == 4
    assert len({r["history_sha256"] for r in report["trials"]}) == 1
    structured = [r for r in report["trials"] if r["method"] == "structured"]
    if failure in {"transport", "parse", "overlong"}:
        assert all(r["rendered"] is None for r in structured)
    if failure == "overlong":
        assert all(
            len(json.loads(r["result"]["content"])["segments"][0]["text"]) == 64001
            for r in structured
        )


def test_freeze_protocol_inputs_code_and_model_before_calls(tmp_path, monkeypatch):
    monkeypatch.setattr(live, "model_hash", lambda _: ("synthetic-model", {"synthetic": "sha"}))
    output = tmp_path / "freeze"
    bank, hashes = live.prepare(output, tmp_path / "model")
    assert len(bank) == 32
    assert "scripts/evidence_reply.py" in hashes
    assert "docs/EVIDENCE_REPLY_AB_PROTOCOL.md" in hashes
    assert not any("\\" in path for path in hashes)
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["case_sha256"] == live.file_hash(output / "frozen-inputs.json")
    for relative, digest in hashes.items():
        assert live.file_hash(output / "frozen-source" / relative) == digest
    with pytest.raises(FileExistsError):
        live.prepare(output, tmp_path / "model")


def test_completed_whitespace_is_upstream_empty_content_not_broken_sse():
    result = {
        "http_status": 200,
        "done_received": True,
        "finish_reason": "stop",
        "content": " " * 37,
        "grade": {"usable_pass": False},
    }
    row = live.presentation(result, [], "structured")
    assert row["status"] == "upstream_empty_content" and row["rendered"] is None
    assert result["content"] == " " * 37
