"""Provider protocol tests use MockTransport; no API key or paid request is used."""

import json
from dataclasses import FrozenInstanceError

import httpx
import pytest

from app.providers import PROVIDERS, ProviderClient, ProviderError, list_providers

MESSAGES = [{"role": "user", "content": "你好"}]
ANSWER = {"choices": [{"message": {"role": "assistant", "content": "你好，我是助手。"}}]}


def client_for(handler, **kwargs):
    transport = httpx.MockTransport(handler)
    return httpx.AsyncClient(transport=transport, **kwargs)


def test_catalog_is_explicit_and_does_not_share_mutable_model_lists():
    catalog = list_providers()
    assert len(catalog) >= 10
    assert "demo" not in {provider["id"] for provider in catalog}
    assert "demo" in {provider["id"] for provider in list_providers(include_demo=True)}
    assert all(provider["doc_url"].startswith("https://") for provider in catalog)
    catalog[0]["models"].append("mutated")
    assert "mutated" not in PROVIDERS[catalog[0]["id"]].models
    with pytest.raises(FrozenInstanceError):
        PROVIDERS["deepseek"].base_url = "http://localhost"
    assert PROVIDERS["doubao"].default_model == ""
    assert "kimi-k3" not in PROVIDERS["kimi"].models


@pytest.mark.parametrize(
    ("provider_id", "endpoint"),
    [
        ("deepseek", "https://api.deepseek.com/chat/completions"),
        ("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
        ("kimi", "https://api.moonshot.cn/v1/chat/completions"),
        ("zhipu", "https://open.bigmodel.cn/api/paas/v4/chat/completions"),
        ("doubao", "https://ark.cn-beijing.volces.com/api/v3/chat/completions"),
        ("minimax", "https://api.minimax.cn/v1/chat/completions"),
        ("qianfan", "https://qianfan.baidubce.com/v2/chat/completions"),
        ("hunyuan", "https://api.hunyuan.cloud.tencent.com/v1/chat/completions"),
        ("tokenhub", "https://tokenhub.tencentmaas.com/v1/chat/completions"),
        ("siliconflow", "https://api.siliconflow.cn/v1/chat/completions"),
        ("spark", "https://spark-api-open.xf-yun.com/v1/chat/completions"),
    ],
)
async def test_each_provider_uses_official_endpoint_headers_and_body(provider_id, endpoint):
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200, json=ANSWER)

    async with client_for(handler) as upstream:
        result = await ProviderClient(upstream).complete(
            provider_id, "key-arbitrary-valid-format", "custom-authorized-model", MESSAGES, 128
        )
    assert result == "你好，我是助手。"
    assert len(seen) == 1
    request = seen[0]
    assert str(request.url) == endpoint
    assert request.method == "POST"
    assert request.headers["Authorization"] == "Bearer key-arbitrary-valid-format"
    assert request.headers["Content-Type"] == "application/json"
    body = json.loads(request.content)
    assert body["model"] == "custom-authorized-model"
    assert body["messages"] == MESSAGES
    assert body["max_tokens"] == 128
    assert body["stream"] is False
    assert "api_key" not in body
    assert request.extensions["timeout"]["read"] <= 120


@pytest.mark.parametrize(
    ("provider_id", "model", "extra"),
    [
        ("deepseek", "deepseek-flash", {"thinking": {"type": "disabled"}}),
        ("qwen", "qwen-plus", {"enable_thinking": False}),
        ("kimi", "kimi-k2.6", {"thinking": {"type": "disabled"}}),
        ("zhipu", "glm-4.5-flash", {"thinking": {"type": "disabled"}}),
        ("doubao", "doubao-seed-2-1-pro-260628", {"thinking": {"type": "disabled"}}),
        ("tokenhub", "hy3", {"thinking": {"type": "disabled"}}),
        ("siliconflow", "deepseek-ai/DeepSeek-V4-Flash", {"enable_thinking": False}),
    ],
)
async def test_known_models_receive_their_own_non_thinking_parameter(provider_id, model, extra):
    def handler(request):
        body = json.loads(request.content)
        for key, value in extra.items():
            assert body[key] == value
        return httpx.Response(200, json=ANSWER)

    async with client_for(handler) as upstream:
        await ProviderClient(upstream).complete(provider_id, "key", model, MESSAGES)


async def test_validate_makes_a_small_paid_chat_call_and_accepts_reasoning_only():
    calls = []

    def handler(request):
        calls.append(json.loads(request.content))
        return httpx.Response(
            200, json={"choices": [{"message": {"content": None, "reasoning_content": "thinking"}}]}
        )

    async with client_for(handler) as upstream:
        assert await ProviderClient(upstream).validate("minimax", "key", "MiniMax-M2.7") is None
    assert len(calls) == 1
    assert calls[0]["max_tokens"] == 8
    assert calls[0]["messages"] == [{"role": "user", "content": "只回复 OK"}]


@pytest.mark.parametrize("status", [400, 401, 403, 404, 408, 422, 429, 500, 503])
async def test_upstream_errors_are_mapped_without_leaking_secrets(status):
    secret = "key-super-secret-test"
    sensitive = "sensitive-request-body"

    def handler(request):
        return httpx.Response(status, json={"error": {"message": secret + sensitive}})

    async with client_for(handler) as upstream:
        with pytest.raises(ProviderError) as raised:
            await ProviderClient(upstream).complete(
                "deepseek", secret, "deepseek-flash", [{"role": "user", "content": sensitive}]
            )
    assert secret not in str(raised.value)
    assert sensitive not in str(raised.value)
    assert raised.value.status_code in {400, 401, 403, 429, 502, 504}
    assert raised.value.message


@pytest.mark.parametrize(
    "exc_type, expected", [(httpx.ReadTimeout, 504), (httpx.ConnectError, 502)]
)
async def test_transport_failure_is_safe(exc_type, expected):
    def handler(request):
        raise exc_type("error mentions secret-key-and-prompt", request=request)

    async with client_for(handler) as upstream:
        with pytest.raises(ProviderError) as raised:
            await ProviderClient(upstream).validate("deepseek", "key", "deepseek-flash")
    assert raised.value.status_code == expected
    assert "secret-key-and-prompt" not in str(raised.value)
    assert raised.value.__cause__ is None


async def test_redirect_cannot_forward_key_even_with_redirect_enabled_injected_client():
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(307, headers={"Location": "http://127.0.0.1/internal"})

    async with client_for(handler, follow_redirects=True) as upstream:
        with pytest.raises(ProviderError) as raised:
            await ProviderClient(upstream).validate("deepseek", "key", "deepseek-flash")
    assert raised.value.status_code == 502
    assert len(calls) == 1


@pytest.mark.parametrize(
    "payload",
    [
        None,
        [],
        {},
        {"choices": []},
        {"choices": [None]},
        {"choices": [{"message": {}}]},
        {"choices": [{"message": {"content": 42}}]},
        {"choices": [{"message": {"content": "  "}}]},
        {"choices": [{"message": {"content": None, "reasoning_content": "thinking"}}]},
    ],
)
async def test_malformed_or_empty_final_answer_is_an_error(payload):
    async with client_for(lambda request: httpx.Response(200, json=payload)) as upstream:
        with pytest.raises(ProviderError) as raised:
            await ProviderClient(upstream).complete("deepseek", "key", "model", MESSAGES)
    assert raised.value.status_code == 502


async def test_invalid_json_is_an_error():
    async with client_for(lambda request: httpx.Response(200, text="<!doctype html>")) as upstream:
        with pytest.raises(ProviderError) as raised:
            await ProviderClient(upstream).complete("deepseek", "key", "model", MESSAGES)
    assert raised.value.status_code == 502


async def test_text_content_parts_are_supported():
    payload = {
        "choices": [
            {
                "message": {
                    "content": [{"type": "text", "text": "你"}, {"type": "text", "text": "好"}]
                }
            }
        ]
    }
    async with client_for(lambda request: httpx.Response(200, json=payload)) as upstream:
        assert (
            await ProviderClient(upstream).complete("deepseek", "key", "model", MESSAGES) == "你好"
        )


@pytest.mark.parametrize(
    ("provider_id", "key", "model", "messages", "max_tokens"),
    [
        ("http://localhost", "key", "model", MESSAGES, 10),
        ("deepseek", "", "model", MESSAGES, 10),
        ("deepseek", "key\r\ninjected:header", "model", MESSAGES, 10),
        ("deepseek", "key", "", MESSAGES, 10),
        ("doubao", "key", "", MESSAGES, 10),
        ("deepseek", "key", "model", [], 10),
        ("deepseek", "key", "model", [{"role": "malicious", "content": "text"}], 10),
        ("deepseek", "key", "model", [{"role": [], "content": "text"}], 10),
        ("deepseek", "key", "model", [{"role": "user", "content": {}}], 10),
        ("deepseek", "key", "model", MESSAGES, 0),
        ("deepseek", "key", "model", MESSAGES, True),
    ],
)
async def test_bad_external_input_is_rejected_before_network(
    provider_id, key, model, messages, max_tokens
):
    def handler(request):
        pytest.fail("Invalid input must not cause an upstream request")

    async with client_for(handler) as upstream:
        with pytest.raises(ProviderError) as raised:
            await ProviderClient(upstream).complete(provider_id, key, model, messages, max_tokens)
    assert raised.value.status_code == 400


async def test_unauthorized_demo_and_invalid_key_never_fall_back_to_demo():
    async with client_for(
        lambda request: httpx.Response(401, json={"error": "bad key"})
    ) as upstream:
        with pytest.raises(ProviderError) as raised:
            await ProviderClient(upstream, allow_demo=True).validate("deepseek", "bad-key", "model")
        assert raised.value.status_code == 401
        with pytest.raises(ProviderError) as raised:
            await ProviderClient(upstream).complete("demo", "", "demo-local", MESSAGES)
        assert raised.value.status_code == 403


async def test_explicit_demo_mentions_its_mode_and_context_without_network():
    def handler(request):
        pytest.fail("Demo must be local")

    async with client_for(handler) as upstream:
        client = ProviderClient(upstream, allow_demo=True)
        await client.validate("demo", "", "demo-local")
        messages = [
            {"role": "system", "content": "<knowledge>\n[1] 知识库：示例项目包含聊天和文档检索。\n</knowledge>"},
            *MESSAGES,
        ]
        answer = await client.complete("demo", "", "demo-local", messages)
    assert "演示" in answer
    assert "[1]" in answer
    assert "文档检索" in answer


async def test_closing_wrapper_leaves_injected_client_open():
    async with client_for(lambda request: httpx.Response(200, json=ANSWER)) as upstream:
        await ProviderClient(upstream).aclose()
        assert not upstream.is_closed


async def test_closing_wrapper_closes_owned_client():
    wrapper = ProviderClient()
    owned = wrapper._client
    await wrapper.aclose()
    assert owned.is_closed


@pytest.mark.parametrize("model", ["kimi-k3", "kimi-k2.7-code", "kimi-k2.7-code-highspeed"])
async def test_unimplemented_kimi_preserved_thinking_models_are_rejected(model):
    def handler(request):
        pytest.fail("Unimplemented model must not cause a paid request")

    async with client_for(handler) as upstream:
        with pytest.raises(ProviderError) as raised:
            await ProviderClient(upstream).complete("kimi", "key", model, MESSAGES)
    assert raised.value.status_code == 400


@pytest.mark.parametrize(
    "inline", ["<think>private reasoning</think>", "<think>unfinished private reasoning"]
)
async def test_minimax_inline_reasoning_is_not_a_final_answer(inline):
    response = {"choices": [{"message": {"content": inline}}]}
    async with client_for(lambda request: httpx.Response(200, json=response)) as upstream:
        with pytest.raises(ProviderError) as raised:
            await ProviderClient(upstream).complete("minimax", "key", "MiniMax-M2.7", MESSAGES)
    assert raised.value.status_code == 502
    assert "private reasoning" not in str(raised.value)


async def test_minimax_inline_reasoning_is_removed_from_final_answer():
    response = {"choices": [{"message": {"content": "<think>private reasoning</think>\n最终答案"}}]}
    async with client_for(lambda request: httpx.Response(200, json=response)) as upstream:
        answer = await ProviderClient(upstream).complete("minimax", "key", "MiniMax-M2.7", MESSAGES)
    assert answer == "最终答案"


async def test_complete_does_not_mutate_input_messages():
    messages = [{"role": "user", "content": "你好", "extra": "not sent"}]
    async with client_for(lambda request: httpx.Response(200, json=ANSWER)) as upstream:
        await ProviderClient(upstream).complete("deepseek", "key", "model", messages)
    assert messages == [{"role": "user", "content": "你好", "extra": "not sent"}]
