"""Evidence experiment contracts without real keys or cloud calls."""

import asyncio
import hashlib
import json
from types import SimpleNamespace

import httpx
import pytest

from scripts import eval_memory_evidence_live as live


def arguments(tmp_path):
    credential = tmp_path / "synthetic.env"
    credential.write_text(
        "RAG_LLM_BASE_URL=https://api.deepseek.com\nRAG_LLM_API_KEY=synthetic-key\n"
    )
    return SimpleNamespace(
        output=tmp_path / "output",
        credentials=credential,
        known_results=None,
        expected_known_sha=None,
    )


def test_sources_exclude_assistant_assertions_and_current_question():
    messages = [
        {"role": "system", "content": "现金"},
        {"role": "assistant", "content": "现金"},
        {"role": "user", "content": "现金？"},
    ]
    assert live.user_quote_available("现金", messages, []) is False
    assert live.user_quote_available(None, messages, []) is None
    assert (
        live.user_quote_available(
            "现金", messages, [{"role": "assistant", "text": "现金"}]
        )
        is False
    )
    assert (
        live.user_quote_available("现金", messages, [{"role": "user", "text": "现金"}])
        is True
    )
    messages.insert(1, {"role": "user", "content": "我付了现金。"})
    assert live.user_quote_available("现金", messages, []) is True


def test_screen_is_only_a_screen_and_forbidden_match_wins():
    case = {
        "required_patterns": ["无法确认", "不确定"],
        "forbidden_patterns": ["你付了现金"],
    }
    assert live.screen(case, "不确定") == {
        "rule_screen_pass": True,
        "required_any_matched": True,
        "forbidden_indices": [],
        "manual_review_required": True,
    }
    assert live.screen(case, "无法确认，但你付了现金")["rule_screen_pass"] is False
    assert live.screen(case, "嗯")["rule_screen_pass"] is False


def known_file(tmp_path, count=11):
    path = tmp_path / "known.json"
    path.write_text(
        json.dumps(
            {
                "modes": [
                    {
                        "mode": "disabled",
                        "turns": [
                            {"input": str(i), "answer": "合成旧回复"}
                            for i in range(count)
                        ],
                    }
                ]
            }
        )
    )
    return path, hashlib.sha256(path.read_bytes()).hexdigest()


def test_known_regressions_are_hashed_and_not_fresh_gold(tmp_path):
    path, sha = known_file(tmp_path)
    bank = live.known_regressions(path, sha)
    assert [c["id"] for c in bank] == [
        "known-payment-ai-echo",
        "known-notice-ai-echo",
        "known-extended-ai-echo",
    ]
    assert all(
        c["category"] == "known_regression" and len(c["seeds"]) == 10 for c in bank
    )
    with pytest.raises(ValueError, match="SHA"):
        live.known_regressions(path, "0" * 64)
    path, sha = known_file(tmp_path, count=1)
    with pytest.raises(ValueError, match="不完整"):
        live.known_regressions(path, sha)


def test_preparation_freezes_fresh_bank_and_all_source_dependencies(tmp_path):
    args = arguments(tmp_path)
    output, bank, credentials, hashes = live.prepare(args)
    assert len(bank) == 24
    assert all(c["sample_kind"] == "fresh_synthetic" for c in bank)
    manifest = json.loads((output / "manifest.json").read_bytes())
    assert manifest["maximum_calls"] == 25
    assert manifest["max_output_tokens"] == 393216
    assert manifest["read_timeout"] is None
    assert any(p.endswith("deepseek_flash_stream.py") for p in hashes)
    assert all(
        hashlib.sha256((output / "frozen-source" / p).read_bytes()).hexdigest() == h
        for p, h in hashes.items()
    )
    assert credentials.key == "synthetic-key"


def test_known_bank_schedule_and_unpaired_sha_are_validated(tmp_path):
    args = arguments(tmp_path)
    path, sha = known_file(tmp_path)
    args.known_results, args.expected_known_sha = path, sha
    assert len(live.prepare(args)[1]) == 27
    other = SimpleNamespace(
        output=tmp_path / "bad",
        credentials=args.credentials,
        known_results=None,
        expected_known_sha=sha,
    )
    with pytest.raises(ValueError, match="对应"):
        live.prepare(other)


def stream_response(request):
    frame = {
        "model": "deepseek-flash",
        "choices": [
            {
                "delta": {"content": "目前可用的记录无法确认。"},
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 20, "completion_tokens": 20},
    }
    return httpx.Response(
        200, text="data: " + json.dumps(frame) + "\n\ndata: [DONE]\n\n"
    )


@pytest.mark.asyncio
async def test_whole_app_probe_keeps_originals_and_background_disabled(
    tmp_path, monkeypatch
):
    from scripts.memory_evidence_cases import cases

    factory = live.capture_provider
    clients = []

    def fake_provider():
        provider = factory()
        clients.append(provider._client)
        provider._client = httpx.AsyncClient(
            transport=httpx.MockTransport(stream_response)
        )
        return provider

    monkeypatch.setattr(live, "capture_provider", fake_provider)
    case = {**cases()[0], "sample_kind": "fresh_synthetic"}
    report = {"events": [], "results": []}
    try:
        provider = await live.execute_bank(
            tmp_path, [case], SimpleNamespace(key="synthetic-key"), report
        )
    finally:
        for client in clients:
            await client.aclose()
    assert len(provider.events) == 2
    assert all(e["status"] == "complete" for e in provider.events)
    row = report["results"][0]
    assert row["originals_identical"] and row["message_count"] >= 50
    assert row["rule_screen_pass"] and row["source_quote_available"] is None
    assert "synthetic-key" not in (tmp_path / "results.json").read_text(
        encoding="utf-8"
    )


@pytest.mark.parametrize(
    "changed, events",
    [
        (True, [{"status": "complete"}]),
        (False, []),
        (False, [{"status": "failed"}]),
    ],
)
def test_finalization_rejects_changed_inputs_or_incomplete_schedule(
    tmp_path, changed, events
):
    args = arguments(tmp_path)
    sha = hashlib.sha256(args.credentials.read_bytes()).hexdigest()
    credentials = SimpleNamespace(file_sha256=sha)
    if changed:
        args.credentials.write_text("changed")
    with pytest.raises(RuntimeError):
        live.finalize(args, [], {}, credentials, SimpleNamespace(events=events), {})


@pytest.mark.asyncio
async def test_run_failure_preserves_only_error_type(tmp_path, monkeypatch):
    args = arguments(tmp_path)

    async def fail(*args):
        raise RuntimeError("synthetic-key must stay private")

    monkeypatch.setattr(live, "execute_bank", fail)
    with pytest.raises(RuntimeError):
        await live.run(args)
    raw = (args.output / "results.json").read_text(encoding="utf-8")
    assert "synthetic-key" not in raw
    assert json.loads(raw)["error_type"] == "RuntimeError"


@pytest.mark.asyncio
async def test_run_success_validates_calls_and_reports_gap(tmp_path, monkeypatch):
    args = arguments(tmp_path)

    async def completed(output, bank, credentials, report):
        events = [{"status": "complete"}] * (len(bank) + 1)
        report["events"] = events
        report["results"] = [
            {
                "id": "synthetic-gap",
                "rule_screen_pass": False,
                "source_quote_available": False,
            }
        ]
        return SimpleNamespace(events=events)

    monkeypatch.setattr(live, "execute_bank", completed)
    report = await live.run(args)
    assert report["cloud_calls"] == 25 and report["originals_identical"]
    assert report["known_retrieval_gaps"] == ["synthetic-gap"]


def test_cli_failure_does_not_print_exception_text(monkeypatch, capsys):
    monkeypatch.setattr("sys.argv", ["eval", "--credentials", "synthetic.env"])

    def fail(coro):
        coro.close()
        raise ValueError("synthetic-key")

    monkeypatch.setattr(asyncio, "run", fail)
    with pytest.raises(SystemExit) as error:
        live.main()
    assert error.value.code == 1
    assert "synthetic-key" not in capsys.readouterr().out
