"""No network tests for complete answers, secret handling and durable originals."""

import json
from types import SimpleNamespace

import httpx
import pytest

from scripts import observe_memory_route_live as live
from scripts.memory_route_linear import LinearDecision


def event_response(request, *, complete=True):
    body = json.loads(request.content)
    system = body["messages"][0]["content"]
    content = (
        '{"user":[],"assistant":[]}'
        if system.startswith("你负责识别")
        else "这是完整的合成陪伴回复。"
    )
    frame = {
        "model": "deepseek-flash",
        "choices": [
            {
                "delta": {
                    "reasoning_content": "PRIVATE_SYNTHETIC_REASONING",
                    "content": content,
                },
                "finish_reason": "stop" if complete else "length",
            }
        ],
        "usage": {"prompt_tokens": 100, "completion_tokens": 20},
    }
    return httpx.Response(
        200, text="data: " + json.dumps(frame) + "\n\ndata: [DONE]\n\n"
    )


def test_secret_is_rejected_before_any_report_write(tmp_path):
    with pytest.raises(ValueError, match="密钥"):
        live.write_report(tmp_path, {"unexpected": "synthetic-key"}, "synthetic-key")
    assert not (tmp_path / "results.json").exists()


@pytest.mark.asyncio
async def test_full_stream_is_measured_without_saving_thoughts_or_credentials():
    provider = live.make_provider("enabled")
    calls = []
    await provider._client.aclose()

    def transport(request):
        calls.append(json.loads(request.content))
        return event_response(request)

    provider._client = httpx.AsyncClient(transport=httpx.MockTransport(transport))
    try:
        answer = await provider.complete(
            "deepseek",
            "synthetic-key",
            "deepseek-flash",
            [{"role": "user", "content": "陪我聊一会儿。"}],
        )
    finally:
        await provider.aclose()
    assert answer == "这是完整的合成陪伴回复。"
    assert calls[0]["thinking"] == {"type": "enabled"}
    assert calls[0]["max_tokens"] == live.MAX_OUTPUT_TOKENS
    assert provider.events[0]["status"] == "complete"
    assert provider.events[0]["finish_reason"] == "stop"
    assert provider.events[0]["reasoning_chars"] > 0
    serialized = json.dumps(provider.events)
    assert "PRIVATE_SYNTHETIC_REASONING" not in serialized
    assert "synthetic-key" not in serialized


@pytest.mark.asyncio
async def test_partial_final_answer_is_not_accepted_as_complete():
    from app.providers import ProviderError

    provider = live.make_provider("disabled")
    await provider._client.aclose()
    provider._client = httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda request: event_response(request, complete=False)
        )
    )
    try:
        with pytest.raises(ProviderError, match="完整最终"):
            await provider.complete(
                "deepseek",
                "synthetic-key",
                "deepseek-flash",
                [{"role": "user", "content": "完整回答。"}],
            )
    finally:
        await provider.aclose()
    assert provider.events[0]["status"] == "failed"


@pytest.mark.asyncio
async def test_foreign_provider_cannot_be_called_by_this_observer():
    provider = live.make_provider("disabled")
    try:
        with pytest.raises(ValueError, match="仅允许"):
            await provider.complete(
                "qwen",
                "synthetic-key",
                "qwen-plus",
                [{"role": "user", "content": "测试"}],
            )
    finally:
        await provider.aclose()
    assert provider.events == []


class SyntheticRouter:
    def decide(self, text, recent):
        return LinearDecision("present", "synthetic-observation")


@pytest.mark.asyncio
async def test_entire_mode_keeps_retrieval_and_saves_every_original(
    tmp_path, monkeypatch
):
    from scripts.memory_route_flows import flows

    providers = []
    factory = live.make_provider

    def fake_provider(mode):
        result = factory(mode)
        providers.append(result)
        # Close the unused real client; a mock transport is the only active client.
        result._original_test_client = result._client
        result._client = httpx.AsyncClient(
            transport=httpx.MockTransport(event_response)
        )
        return result

    monkeypatch.setattr(live, "make_provider", fake_provider)
    report = {"status": "running", "modes": []}
    try:
        await live.one_mode(
            tmp_path,
            flows()[0],
            SyntheticRouter(),
            SimpleNamespace(key="synthetic-key"),
            "disabled",
            report,
        )
    finally:
        for provider in providers:
            await provider._original_test_client.aclose()
    mode = report["modes"][0]
    assert len(mode["events"]) == 23
    assert mode["message_count"] == 74
    assert len(mode["turns"]) == 11
    assert all(
        t["job"]["status"] == "ok" and t["job"]["attempts"] == 1 for t in mode["turns"]
    )
    assert all(t["originals_identical"] for t in mode["turns"])
    # Even a simulated present observation leaves real earlier references in place.
    assert mode["turns"][1]["references"]
    assert mode["turns"][1]["route_observation"]["route"] == "present"
    assert "synthetic-key" not in (tmp_path / "results.json").read_text(
        encoding="utf-8"
    )


def prepared_arguments(tmp_path, monkeypatch):
    model = tmp_path / "model"
    model.mkdir()
    (model / "model.safetensors").write_bytes(b"SYNTHETIC WEIGHTS")
    credentials = tmp_path / "synthetic.env"
    credentials.write_text(
        "RAG_LLM_BASE_URL=https://api.deepseek.com\nRAG_LLM_API_KEY=synthetic-key\n"
    )
    profile = {
        "prototypes": [("present", [1, 0]), ("retrieve", [0, 1])],
        "head": {"coefficients": [8, -8], "intercept": 0},
        "policy": {"min_similarity": 0.5, "min_present_affinity": 0.61},
    }
    monkeypatch.setattr(live, "load_profile", lambda *args: profile)
    monkeypatch.setattr(live.evaluation, "BgeEncoder", lambda path: None)
    return SimpleNamespace(
        output=tmp_path / "output",
        model=model,
        profile=tmp_path / "profile.json",
        credentials=credentials,
    )


def test_preparation_freezes_schedule_before_cloud_and_keeps_observer_only(
    tmp_path, monkeypatch
):
    args = prepared_arguments(tmp_path, monkeypatch)
    output, _, _, selected, _, _ = live.prepare(args)
    frozen = json.loads((output / "frozen-inputs.json").read_bytes())
    manifest = json.loads((output / "manifest.json").read_bytes())
    assert frozen["flow"] == selected
    assert len(frozen["live_inputs"]) == 11
    assert manifest["maximum_calls"] == 46
    assert manifest["production_skip_enabled"] is False
    assert manifest["cloud_frontend_classifier_calls"] == 0
    assert "synthetic-key" not in (output / "manifest.json").read_text(encoding="utf-8")


@pytest.mark.asyncio
async def test_final_failure_is_recorded_without_leaking_error_or_secret(
    tmp_path, monkeypatch
):
    args = prepared_arguments(tmp_path, monkeypatch)

    async def fail(*args):
        raise ValueError("synthetic-key PRIVATE INPUT")

    monkeypatch.setattr(live, "one_mode", fail)
    with pytest.raises(ValueError):
        await live.run(args)
    report = json.loads((args.output / "results.json").read_bytes())
    assert report["status"] == "failed"
    assert report["error_type"] == "ValueError"
    assert "PRIVATE INPUT" not in json.dumps(report)
    assert "synthetic-key" not in json.dumps(report)


@pytest.mark.asyncio
async def test_complete_run_checks_source_identity_and_exact_call_schedule(
    tmp_path, monkeypatch
):
    args = prepared_arguments(tmp_path, monkeypatch)

    async def fake_mode(output, flow, router, credentials, mode, report):
        report["modes"].append({"mode": mode, "events": [{} for _ in range(23)]})

    monkeypatch.setattr(live, "one_mode", fake_mode)
    report = await live.run(args)
    assert report["status"] == "completed"
    assert report["cloud_calls"] == 46
    assert report["source_files_unchanged"] is True
    assert report["credential_file_unchanged"] is True
    assert report["production_skip_enabled"] is False


def test_cli_never_prints_input_bearing_failure_text(monkeypatch, capsys):
    monkeypatch.setattr(
        "sys.argv",
        ["observe", "--model", "model", "--profile", "profile", "--credentials", "env"],
    )

    async def fail(*args):
        raise ValueError("PRIVATE CREDENTIAL")

    monkeypatch.setattr(live, "run", fail)
    with pytest.raises(SystemExit):
        live.main()
    assert "PRIVATE CREDENTIAL" not in capsys.readouterr().out
