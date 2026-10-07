"""Independent A/B histories, raw failures and prompt gold separation."""

import asyncio
import hashlib
import json
from types import SimpleNamespace

import pytest

from scripts import observe_answer_guard_ab as live
from scripts.memory_answer_guard import VERSION
from scripts.memory_answer_guard_cases import build_cases
from scripts.tests.test_memory_retrieval_ab import Encoder


def test_schedule_balances_separate_generated_long_and_challenges():
    trials = live.schedule(build_cases())
    assert len(trials) == 80
    assert sum(t["kind"] == "generated" and not t["extended"] for t in trials) == 48
    assert sum(t["extended"] for t in trials) == 8
    assert sum(t["kind"] == "challenge" for t in trials) == 24
    for first, second in zip(trials[::2], trials[1::2], strict=True):
        assert first["case_id"] == second["case_id"]
        assert {first["order"], second["order"]} == {"lexical-first", "semantic-first"}
        assert first["kind"] == second["kind"]


def test_clock_regression_is_separate_known_challenge_only():
    trials = live.clock_schedule(build_cases())
    assert len(trials) == 12
    assert {t["case_id"] for t in trials} == {"guard-known-03", "guard-change-01", "guard-allowed-04"}
    assert all(t["kind"] == "challenge" and not t["extended"] for t in trials)


def test_assistant_own_promise_is_available_and_unknown_not_true():
    case = next(c for c in build_cases() if any(
        m["role"] == "assistant" and m["id"] in c["expected_ids"] for m in c["history"]))
    refs = [{"message_id": m["id"], "role": m["role"], "text": m["text"]} for m in case["history"]]
    assert live.availability(case, refs)["all_needed_available"]
    bad = [{**r, "role": "user"} for r in refs]
    assert not live.availability(case, bad)["all_needed_available"]
    unknown = next(c for c in build_cases() if not c["expected_ids"])
    assert live.availability(unknown, refs)["all_needed_available"] is False


@pytest.mark.parametrize("failure", [None, "draft", "parse", "overlong", "credential", "secret"])
def test_actual_loop_preserves_independent_results_and_failures(tmp_path, monkeypatch, failure):
    output = tmp_path / "result"
    output.mkdir()
    credential_file = tmp_path / "env"
    credential_file.write_text("synthetic", encoding="utf-8")
    key = "synthetic-secret-only"
    credentials = SimpleNamespace(key=key, file_sha256=hashlib.sha256(credential_file.read_bytes()).hexdigest())
    args = SimpleNamespace(output=output, credentials=credential_file, model=tmp_path / "model")
    monkeypatch.setattr(live, "ResearchEncoder", lambda _: Encoder())
    observed = []

    async def reply(_client, secret, trial):
        assert secret == key
        prompt = trial["case"]["messages"]
        observed.append(prompt)
        assert not any(word in json.dumps(prompt) for word in ("expected_supported", "answer_expectation", "expected_quotes", "challenge_drafts"))
        if failure == "credential":
            credential_file.write_text("changed", encoding="utf-8")
        if prompt[0]["content"].startswith("你是回答出处"):
            assert json.loads(prompt[-1]["content"])["current_utc"] == live.FIXED_UTC.isoformat()
            content = "not json" if failure == "parse" else json.dumps({"version": VERSION, "reviewed_all": True, "claims": []})
        else:
            content = "长" * 64001 if failure == "overlong" else (key if failure == "secret" else "我陪你。")
        return {"content": content, "grade": {"usable_pass": failure != "draft"},
                "first_content_seconds": .1, "latency_seconds": .2, "finish_reason": "stop", "done_received": True}

    monkeypatch.setattr(live, "run_stream_trial", reply)
    case = build_cases()[0]
    trials = live.paired(case, 0, "generated", mode="disabled", extended=False)
    task = live.execute(args, [case], credentials, trials, {})
    if failure in {"credential", "secret"}:
        with pytest.raises(RuntimeError if failure == "credential" else ValueError):
            asyncio.run(task)
        return
    success = asyncio.run(task)
    assert success == (failure is None)
    report = json.loads((output / "results.json").read_text(encoding="utf-8"))
    assert report["complete"] and report["credentials_unchanged"]
    assert len(report["trials"]) == 2
    first, second = report["trials"]
    assert first["history_sha256"] == second["history_sha256"]
    assert first["calls"][0]["messages"] == first["messages"]
    assert "我陪你。" not in json.dumps(second["messages"], ensure_ascii=False)
    if failure in {"draft", "parse", "overlong"}:
        assert first["guard"]["final_content"] is None
    if failure == "overlong":
        assert first["guard"]["draft_content"] == "长" * 64001
        assert len(first["calls"]) == 1


def test_challenge_only_sends_draft_text_not_challenge_gold(tmp_path, monkeypatch):
    case = build_cases()[0]
    index = live.RetrievalIndex(case["history"], Encoder(), scope=case["id"])
    seen = []

    async def reply(_client, _key, trial):
        seen.append(trial["case"]["messages"])
        return {"content": json.dumps({"version": VERSION, "reviewed_all": True, "claims": []}),
                "grade": {"usable_pass": True}, "latency_seconds": .1}

    monkeypatch.setattr(live, "run_stream_trial", reply)
    trial = live.paired(case, 0, "challenge", mode="disabled", extended=False,
                        challenge_id=case["challenge_drafts"][0]["id"])[0]
    try:
        row = asyncio.run(live.call_one(None, "synthetic", case, trial, index))
    finally:
        index.db.close()
    assert len(seen) == 1
    text = json.dumps(seen, ensure_ascii=False)
    assert "expected_supported" not in text and "rationale" not in text
    assert row["guard"]["draft_content"] == case["challenge_drafts"][0]["text"]
    assert row["kind"] == "challenge"


def test_freeze_sources_and_model_before_results(tmp_path, monkeypatch):
    monkeypatch.setattr(live, "model_hash", lambda _: ("synthetic-model", {"synthetic": "sha"}))
    output = tmp_path / "frozen"
    bank, hashes = live.prepare(output, tmp_path / "model")
    assert len(bank) == 24
    assert "scripts/memory_answer_guard.py" in hashes
    assert not any("\\" in path for path in hashes)
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["case_sha256"] == live.file_hash(output / "frozen-inputs.json")
    for relative, digest in hashes.items():
        assert live.file_hash(output / "frozen-source" / relative) == digest
    with pytest.raises(FileExistsError):
        live.prepare(output, tmp_path / "model")
