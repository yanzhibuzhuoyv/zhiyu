import asyncio
import importlib.util
import json
import sys
from pathlib import Path

import httpx
import pytest

SCRIPT = Path(__file__).parents[1] / "deepseek_flash_eval.py"
SPEC = importlib.util.spec_from_file_location("deepseek_flash_eval", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


def test_credentials_require_official_https_and_do_not_modify_file(tmp_path):
    path = tmp_path / ".env"
    original = b'RAG_LLM_API_KEY="synthetic-test-key"\nRAG_LLM_BASE_URL=https://api.deepseek.com/v1\n'
    path.write_bytes(original)
    credentials = module.load_credentials(path)
    assert credentials.key == "synthetic-test-key"
    assert path.read_bytes() == original
    assert "synthetic-test-key" not in repr(credentials)


@pytest.mark.parametrize("url", ["http://api.deepseek.com", "https://evil.test", "https://api.deepseek.com@evil.test", "https://api.deepseek.com/?key=secret", "https://api.deepseek.com:443"])
def test_credentials_reject_other_destinations(tmp_path, url):
    path = tmp_path / ".env"
    path.write_text(f"RAG_LLM_API_KEY=synthetic-test-key\nRAG_LLM_BASE_URL={url}\n")
    with pytest.raises(ValueError, match="credential_configuration_invalid"):
        module.load_credentials(path)


def test_payload_explicit_thinking_and_same_output_budget():
    messages = [{"role": "user", "content": "合成测试"}]
    on, off = (module.payload(messages, mode) for mode in ("enabled", "disabled"))
    assert on["model"] == off["model"] == "deepseek-flash"
    assert on["max_tokens"] == off["max_tokens"] == 4096
    assert on["thinking"] == {"type": "enabled"}
    assert on["reasoning_effort"] == "high"
    assert off["thinking"] == {"type": "disabled"}
    assert "temperature" not in on and "temperature" not in off


def test_expanded_budget_is_explicit_and_bounded():
    assert module.payload([], "enabled", max_tokens=16384)["max_tokens"] == 16384
    for budget in (True, 0, 32769, "16384"):
        with pytest.raises(ValueError, match="invalid_token_budget"):
            module.payload([], "disabled", max_tokens=budget)
    for timeout in (True, 0, 181, float("nan")):
        with pytest.raises(ValueError, match="invalid_request_timeout"):
            asyncio.run(module.execute(Path("unused"), timeout=timeout))


def test_schedule_counterbalanced_and_bounded():
    cases = [{"id": "a"}, {"id": "b"}]
    schedule = module.schedule(cases)
    assert len(schedule) == 8
    assert [(s["repeat"], s["mode"]) for s in schedule[:4]] == [(1, "disabled"), (1, "enabled"), (2, "enabled"), (2, "disabled")]
    assert [s["mode"] for s in schedule[4:]] == ["enabled", "disabled", "disabled", "enabled"]


def test_raw_forbidden_errors_are_visible_even_if_parser_filters():
    sources = {role: {"content": text, "created_at": "2026-10-06T00:00:00+00:00"}
               for role, text in [("user", "姐姐的生日是六月九日。"), ("assistant", "我听到了。") ]}
    claim = {"quote": "姐姐的生日是六月九日", "kind": "profile", "subject": "other", "polarity": "affirm", "topic_key": "sister:birthday", "confidence": .99, "explicit_keep": False, "correction": False, "promise_state": None, "date_basis": None, "event_date": "六月九日"}
    case = {"category": "semantic", "sources": sources, "required": [], "forbidden": [{"role": "user", "quote_anchor": "姐姐的生日", "subject": "other"}]}
    grade = module.grade(case, json.dumps({"user": [claim], "assistant": []}, ensure_ascii=False), "stop")
    assert grade["format_valid"] is True
    assert grade["raw_pass"] is False
    assert grade["accepted_pass"] is True
    assert grade["filtered_claims"] == 1


def test_truncation_is_failure_and_secret_is_redacted():
    case = {"category": "reply", "required_checks": ["你好"], "forbidden_checks": []}
    assert module.grade(case, "你好", "length")["usable_pass"] is False
    assert module.redact({"content": "hello synthetic-test-key"}, "synthetic-test-key") == {"content": "hello [REDACTED]"}


def test_cost_uses_observed_tokens_and_reports_unknown():
    assert module.estimate_cost({"prompt_cache_hit_tokens": 100, "prompt_cache_miss_tokens": 200, "completion_tokens": 300}) == pytest.approx(.001402)
    assert module.estimate_cost({"prompt_tokens": 300}) is None


def test_duplicate_quote_requires_correct_original_position():
    text = "我答应陪你散步。\n仅为引用：我答应陪你散步。"
    case = {"category": "semantic", "sources": {r: {"content": t, "created_at": "2026-10-06T00:00:00+00:00"} for r, t in [("user", text), ("assistant", "收到")]}, "span_checks": [{"role": "user", "quote_anchor": "我答应陪你散步", "start": 0, "end_max": 8}]}
    start = text.rindex("我答应陪你散步。")
    claim = {"quote": "我答应陪你散步。", "kind": "promise", "subject": "user", "polarity": "affirm", "topic_key": "user:promise:散步", "confidence": .99, "explicit_keep": False, "correction": False, "promise_state": "pending", "date_basis": None, "event_date": None, "start": start, "end": start + 8}
    scoring = module.grade(case, json.dumps({"user": [claim], "assistant": []}, ensure_ascii=False), "stop")
    assert scoring["format_valid"] is True
    assert scoring["raw_pass"] is True
    assert scoring["accepted_pass"] is False


def test_http_errors_do_not_include_body_and_requests_are_official():
    def handler(request):
        assert str(request.url) == module.ENDPOINT
        assert request.headers["authorization"] == "Bearer synthetic-test-key"
        return httpx.Response(401, text="private synthetic-test-key")

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            return await module._request(client, "synthetic-test-key", module.payload([], "disabled"))
    assert asyncio.run(run()) == {"http_status": 401, "error": "upstream_http_error"}


def test_reasoning_is_not_saved_and_output_secret_is_redacted():
    def handler(_request):
        return httpx.Response(200, json={"model": "deepseek-flash", "choices": [{"finish_reason": "stop", "message": {"content": "你好 synthetic-test-key", "reasoning_content": "private reasoning"}}], "usage": {"prompt_cache_hit_tokens": 0, "prompt_cache_miss_tokens": 10, "completion_tokens": 20}})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            trial = {"case": {"id": "mock", "category": "reply", "messages": [], "required_checks": ["你好"], "forbidden_checks": []}, "mode": "enabled", "repeat": 1}
            return await module.run_trial(client, "synthetic-test-key", trial)
    result = asyncio.run(run())
    assert result["reasoning_chars"] == len("private reasoning")
    assert "private reasoning" not in json.dumps(result)
    assert "synthetic-test-key" not in json.dumps(result)
    assert result["grade"]["usable_pass"] is True


def test_fixed_cases_freeze_time_and_count_without_network():
    cases = module.freeze_cases()
    assert len(cases) == 16
    assert len(module.schedule(cases)) == 64
    assert all("2026-10-06T04:00:00+00:00" in c["messages"][0]["content"] for c in cases if c["category"] == "reply")
    assert any(c.get("span_checks") for c in cases)


def test_response_size_limit_and_nonstring_fields():
    async def run(transport):
        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
            return await module._request(client, "synthetic-test-key", {})
    large = asyncio.run(run(lambda _r: httpx.Response(200, content=b"a" * (module.MAX_RESPONSE_BYTES + 1))))
    assert large["error"] == "response_size_limit"
    with pytest.raises(TypeError, match="invalid_response_fields"):
        asyncio.run(run(lambda _r: httpx.Response(200, json={"choices": [{"message": {"content": ["invalid"]}}]})))


def test_overall_timeout_is_failure_not_retry(monkeypatch):
    async def blocked(*_args):
        await asyncio.sleep(10)
    monkeypatch.setattr(module, "_request", blocked)
    monkeypatch.setattr(module, "TIMEOUT", .001)
    trial = {"case": {"id": "mock", "category": "reply", "messages": [], "required_checks": [], "forbidden_checks": []}, "mode": "disabled", "repeat": 1}
    result = asyncio.run(module.run_trial(None, "synthetic-test-key", trial))
    assert result["error"] == "TimeoutError"
    assert result["grade"]["usable_pass"] is False


@pytest.mark.parametrize("status, expected_count", [(200, 64), (401, 1)])
def test_execute_manifest_progress_and_auth_abort(tmp_path, monkeypatch, status, expected_count):
    files = ["scripts/deepseek_flash_eval.py", "scripts/flash_eval_cases.py", "backend/app/memory_semantic.py", "backend/app/chat_routes.py", "backend/app/companion.py"]
    for name in files:
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("frozen synthetic source", encoding="utf-8")
    env_file = tmp_path / ".env"
    env_file.write_text("RAG_LLM_API_KEY=synthetic-test-key\nRAG_LLM_BASE_URL=https://api.deepseek.com\n")
    monkeypatch.setattr(module, "ROOT", tmp_path)
    monkeypatch.setattr(module, "__file__", str(tmp_path / files[0]))
    cases = [{"id": f"mock{i}", "category": "reply", "messages": []} for i in range(16)]
    monkeypatch.setattr(module, "freeze_cases", lambda: cases)

    async def fake_trial(_client, _key, trial, **_kwargs):
        return {"case_id": trial["case"]["id"], "http_status": status, "latency_seconds": .1, "grade": {"usable_pass": status == 200}}
    monkeypatch.setattr(module, "run_trial", fake_trial)
    code = asyncio.run(module.execute(env_file))
    result_file = next((tmp_path / "artifacts").glob("*/results.json"))
    result = json.loads(result_file.read_text(encoding="utf-8"))
    assert result["attempted_calls"] == expected_count
    assert result["credential_file_unchanged"] and result["source_hashes_unchanged"]
    assert result["status"] == ("complete" if status == 200 else "incomplete")
    assert code == (0 if status == 200 else 1)
    assert "synthetic-test-key" not in result_file.read_text(encoding="utf-8")
