"""Evidence equality, oracle exclusion, and full-completion criteria."""

import json
from types import SimpleNamespace

import pytest

# isort: split
from scripts import observe_companion_preset as live


def test_schedule_is_balanced_and_evidence_only_differs_by_preset():
    cases = live.load_cases()
    scheduled = live.schedule(cases)
    assert len(scheduled) == 80
    for case in cases:
        group = [t for t in scheduled if t["case_id"] == case["id"]]
        assert {(t["style"], t["mode"]) for t in group} == {
            (s, m) for s in live.STYLES for m in ("disabled", "enabled")}
        gentle, warm = (live.messages_for(case, s) for s in live.STYLES)
        assert gentle[1:] == warm[1:]
        assert "2026-10-07T04:00:00+00:00" in warm[0]["content"]
        for expectation in case["expected"]:
            assert expectation not in json.dumps(warm, ensure_ascii=False)
        assert all(s["text"].replace("<", "\\u003c") in warm[0]["content"] for s in case["sources"])


@pytest.mark.parametrize("field,value", [("http_status", 500), ("done_received", False),
                                        ("finish_reason", "length"), ("content", "   "),
                                        ("error", "stream_incomplete")])
def test_technical_completion_does_not_accept_failure(field, value):
    valid = {"http_status": 200, "done_received": True, "finish_reason": "stop", "content": "回答"}
    assert live.technical(valid)
    assert not live.technical({**valid, field: value})


def test_report_rejects_secret_and_refuses_overwriting_existing_frozen_run(tmp_path):
    with pytest.raises(ValueError, match="secret_in_report"):
        live.write_report(tmp_path, {"content": "synthetic-secret"}, "synthetic-secret")
    assert not (tmp_path / "results.json").exists()
    live.write_report(tmp_path, {"complete": False}, "synthetic-secret")
    assert json.loads((tmp_path / "results.json").read_text()) == {"complete": False}
    live.write_report(tmp_path, {"complete": True}, "synthetic-secret")
    assert json.loads((tmp_path / "results.json").read_text()) == {"complete": True}


def test_freeze_detects_source_and_credential_changes(tmp_path, monkeypatch):
    monkeypatch.setattr(live, "ROOT", tmp_path)
    path = tmp_path / "original"
    path.write_text("data")
    sha = live.file_hash(path)
    credentials = type("Credentials", (), {"file_sha256": sha})()
    live.verify_frozen({"original": sha}, credentials, path)
    path.write_text("changed")
    with pytest.raises(RuntimeError, match="frozen_source_changed"):
        live.verify_frozen({"original": sha}, credentials, path)
    with pytest.raises(RuntimeError, match="credential_file_changed"):
        live.verify_frozen({}, credentials, path)


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", [None, "transport", "credential", "exception"])
async def test_execution_retains_failures_and_complete_outputs(tmp_path, monkeypatch, failure):
    keyfile = tmp_path / "credentials"
    keyfile.write_text("fixture-credentials")
    credentials = SimpleNamespace(key="test-key-only", file_sha256=live.file_hash(keyfile))
    monkeypatch.setattr(live, "load_credentials", lambda _: credentials)
    monkeypatch.setattr(live, "FROZEN", ())
    seen = []

    async def generate(client, key, trial):
        assert key == credentials.key
        seen.append(trial)
        if failure == "exception":
            raise RuntimeError("test_failure")
        if failure == "credential":
            keyfile.write_text("changed")
        return {"http_status": 200, "done_received": True, "finish_reason": "stop",
                "content": "合成回答", "error": "stream_incomplete" if failure == "transport" else None}

    monkeypatch.setattr(live, "run_stream_trial", generate)
    args = SimpleNamespace(credentials=keyfile, output=tmp_path / "run")
    if failure in {"credential", "exception"}:
        with pytest.raises(RuntimeError):
            await live.run(args)
    else:
        assert await live.run(args) is (failure is None)
    report = json.loads((args.output / "results.json").read_text(encoding="utf-8"))
    assert credentials.key not in (args.output / "results.json").read_text(encoding="utf-8")
    assert report["complete"] is (failure in {None, "transport"})
    assert len(report["trials"]) == (0 if failure == "exception" else 1 if failure == "credential" else 80)
    if failure is None:
        assert all(row["han_count"] == 4 for row in report["trials"])
        assert {t["mode"] for t in seen} == {"enabled", "disabled"}
    with pytest.raises(FileExistsError):
        await live.run(args)
