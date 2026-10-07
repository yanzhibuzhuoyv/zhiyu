"""Actual prior replies remain independent and no gold is sent to the model."""

import json
from types import SimpleNamespace

import pytest

# isort: split
from scripts import observe_companion_temperament as continuous

live = continuous.live


@pytest.mark.asyncio
@pytest.mark.parametrize("fail", [False, True])
async def test_continuous_history_keeps_actual_answers_and_stops_on_failure(tmp_path, monkeypatch, fail):
    seen = []

    async def generate(client, key, trial):
        seen.append(trial)
        return {"http_status": 200, "done_received": not fail, "finish_reason": "stop",
                "content": f"合成回答{len(seen)}"}

    monkeypatch.setattr(live, "run_stream_trial", generate)
    bank = continuous.load_steps()
    report = {"trials": []}
    if fail:
        with pytest.raises(RuntimeError, match="continuous_response_unavailable"):
            await continuous.conversation(None, "synthetic-key", "disabled", bank, tmp_path, report)
        assert len(report["trials"]) == 1
        assert not report["trials"][0]["technical_complete"]
        return
    await continuous.conversation(None, "synthetic-key", "disabled", bank, tmp_path, report)
    assert len(report["trials"]) == 4
    for index, row in enumerate(report["trials"]):
        assert "warm_v2" in row["messages"][0]["content"]
        assert len(row["messages"]) == 2 + index * 2
        for earlier in range(index):
            assert row["messages"][2 + earlier * 2] == {"role": "assistant", "content": f"合成回答{earlier + 1}"}
        text = json.dumps(row["messages"], ensure_ascii=False)
        assert not any(e in text for step in bank["steps"] for e in step["expected"])


@pytest.mark.asyncio
async def test_execution_freezes_before_both_modes(tmp_path, monkeypatch):
    keyfile = tmp_path / "credential"
    keyfile.write_text("synthetic")
    credentials = SimpleNamespace(key="synthetic-key", file_sha256=live.file_hash(keyfile))
    monkeypatch.setattr(live, "load_credentials", lambda _: credentials)
    monkeypatch.setattr(continuous, "FROZEN", ())
    modes = []

    async def fake_conversation(client, key, mode, bank, output, report):
        modes.append(mode)

    monkeypatch.setattr(continuous, "conversation", fake_conversation)
    args = SimpleNamespace(credentials=keyfile, output=tmp_path / "run")
    await continuous.run(args)
    saved = json.loads((args.output / "results.json").read_text(encoding="utf-8"))
    assert saved["complete"] and modes == ["disabled", "enabled"]
