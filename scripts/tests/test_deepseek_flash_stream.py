import asyncio
import importlib.util
import json
import sys
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).parents[1]))
SCRIPT = Path(__file__).parents[1] / "deepseek_flash_stream.py"
SPEC = importlib.util.spec_from_file_location("deepseek_flash_stream", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


def _frame(content=None, reasoning=None, finish=None, usage=None):
    choice = {"delta": {"content": content, "reasoning_content": reasoning}, "finish_reason": finish}
    return {"model": "deepseek-flash", "choices": [choice], "usage": usage}


def _sse(frames, done=True):
    text = ": heartbeat\n\n" + "".join("data: " + json.dumps(f, ensure_ascii=False) + "\n\n" for f in frames)
    return (text + ("data: [DONE]\n\n" if done else "")).encode("utf-8")


def _run(frames, done=True, *, status=200, case=None):
    def handler(request):
        body = json.loads(request.content)
        assert body["max_tokens"] == 393216
        assert body["stream"] is True
        assert body["stream_options"] == {"include_usage": True}
        assert str(request.url) == module.ENDPOINT
        return httpx.Response(status, content=_sse(frames, done), headers={"content-type": "text/event-stream"})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            trial = {"case": case or {"id": "mock", "category": "reply", "messages": [], "required_checks": ["你好"], "forbidden_checks": []}, "mode": "enabled", "repeat": 1}
            return await module.run_stream_trial(client, "synthetic-test-key", trial)
    return asyncio.run(run())


def test_complete_stream_separates_reasoning_and_final_answer_and_usage():
    usage = {"prompt_cache_hit_tokens": 0, "prompt_cache_miss_tokens": 20, "completion_tokens": 100, "completion_tokens_details": {"reasoning_tokens": 80}}
    result = _run([_frame(reasoning="do not save private reasoning"), _frame(content="你"), _frame(content="好"), _frame(finish="stop", usage=usage)])
    assert result["content"] == "你好"
    assert result["done_received"] and result["grade"]["usable_pass"]
    assert result["reasoning_chars"] == len("do not save private reasoning")
    assert result["usage"]["reasoning_tokens"] == 80
    assert result["first_reasoning_seconds"] is not None
    assert result["first_content_seconds"] is not None
    assert "private reasoning" not in json.dumps(result)


@pytest.mark.parametrize("frames,done", [([_frame(reasoning="only thinking"), _frame(finish="stop")], True), ([_frame(content="你好", finish="stop")], False), ([_frame(content="你好", finish="length")], True)])
def test_thinking_only_interrupted_or_truncated_is_not_success(frames, done):
    result = _run(frames, done)
    assert result["grade"]["usable_pass"] is False


def test_http_failure_is_safe_and_has_no_body():
    result = _run([{"private": "synthetic-test-key"}], status=401)
    assert result["http_status"] == 401
    assert result["error"] == "upstream_http_error"
    assert "synthetic-test-key" not in json.dumps(result)


def test_content_key_is_redacted_and_unknown_usage_remains_unknown():
    result = _run([_frame(content="你好 synthetic-test-key"), _frame(finish="stop")])
    assert "synthetic-test-key" not in json.dumps(result)
    assert result["estimated_cny_offpeak"] is None


def test_size_limit_is_explicit(monkeypatch):
    monkeypatch.setattr(module, "MAX_CONTENT_BYTES", 3)
    result = _run([_frame(content="你好")])
    assert result["error"] == "content_size_limit"
    assert result["grade"]["usable_pass"] is False


def test_invalid_frame_is_safe_failure():
    result = _run([_frame(content=["not-string"])])
    assert result["error"] == "TypeError"
    assert not result["done_received"]


def test_empty_choices_usage_frame_is_supported():
    usage = {"completion_tokens": 10, "prompt_cache_hit_tokens": 0, "prompt_cache_miss_tokens": 20}
    result = _run([_frame(content="你好", finish="stop"), {"choices": [], "usage": usage}])
    assert result["grade"]["usable_pass"]
    assert result["usage"]["completion_tokens"] == 10


def test_upstream_error_packet_cannot_be_used_as_an_answer():
    result = _run([{"error": {"message": "private synthetic-test-key"}}])
    assert result["error"] == "ValueError"
    assert not result["grade"]["usable_pass"]
    assert "private" not in json.dumps(result)
