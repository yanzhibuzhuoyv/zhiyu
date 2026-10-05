"""Small OpenAI-compatible adapter with fixed routes and safe, user-facing errors."""

import json
import re
from dataclasses import asdict
from typing import Any

import httpx

from app.providers_catalog import PROVIDERS, Provider

__all__ = ["PROVIDERS", "Provider", "ProviderClient", "ProviderError", "list_providers"]

_TIMEOUT = httpx.Timeout(90.0, connect=10.0, pool=10.0)
_ALLOWED_URLS = frozenset(
    f"{provider.base_url}/chat/completions"
    for provider in PROVIDERS.values()
    if provider.id != "demo"
)


def list_providers(include_demo: bool = False) -> list[dict]:
    """Return a detached catalog; the backend configuration controls demo visibility."""
    return [
        asdict(provider) for provider in PROVIDERS.values() if include_demo or provider.id != "demo"
    ]


class ProviderError(Exception):
    def __init__(self, status_code: int, message: str):
        self.status_code = status_code
        self.message = message
        super().__init__(message)


def _provider(provider_id: str, allow_demo: bool) -> Provider:
    if not isinstance(provider_id, str) or provider_id not in PROVIDERS:
        raise ProviderError(400, "请选择支持的 AI 服务商。")
    provider = PROVIDERS[provider_id]
    if provider.id == "demo" and not allow_demo:
        raise ProviderError(403, "后端未开启演示模式。")
    return provider


def _validate_inputs(key: str, model: str, messages: list[dict], max_tokens: int) -> list[dict]:
    if not isinstance(key, str) or not key or len(key) > 4096:
        raise ProviderError(400, "请输入 API Key。")
    if any(ord(char) < 33 or ord(char) > 126 for char in key):
        raise ProviderError(400, "API Key 不能包含空格或控制字符，请完整复制密钥。")
    if not isinstance(model, str) or not model.strip() or len(model) > 256:
        raise ProviderError(400, "请填写已开通的模型 ID；豆包也可使用 ep- 接入点 ID。")
    if any(ord(char) < 32 for char in model):
        raise ProviderError(400, "模型 ID 包含无效字符。")
    if type(max_tokens) is not int or not 1 <= max_tokens <= 65536:
        raise ProviderError(400, "输出长度必须在 1 至 65536 token 之间。")
    if not isinstance(messages, list) or not 1 <= len(messages) <= 100:
        raise ProviderError(400, "聊天上下文格式或长度不正确。")
    copied = []
    for message in messages:
        if not isinstance(message, dict) or not isinstance(message.get("role"), str):
            raise ProviderError(400, "聊天消息角色不受支持。")
        if message["role"] not in {"system", "user", "assistant"}:
            raise ProviderError(400, "聊天消息角色不受支持。")
        content = message.get("content")
        if not isinstance(content, str) or not content.strip() or len(content) > 128000:
            raise ProviderError(400, "聊天消息必须包含非空文本，且单条不超过 128000 字符。")
        copied.append({"role": message["role"], "content": content})
    if sum(len(message["content"]) for message in copied) > 256000:
        raise ProviderError(400, "聊天上下文过长，请新建对话或缩短知识库内容。")
    return copied


def _model_options(provider_id: str, model: str) -> dict[str, Any]:
    """Only send a thinking flag for the provider/model combinations verified in docs."""
    disabled = {"thinking": {"type": "disabled"}}
    if provider_id == "kimi" and (model == "kimi-k3" or model.startswith("kimi-k2.7-code")):
        raise ProviderError(
            400, "该 Kimi 模型需要保留完整思考消息，本版暂未适配；请使用 kimi-k2.6。"
        )
    if provider_id == "deepseek" and model in {"deepseek-flash", "deepseek-v4-pro"}:
        return disabled
    if provider_id == "qwen" and model in {"qwen-plus", "qwen-flash"}:
        return {"enable_thinking": False}
    if provider_id == "kimi" and model == "kimi-k2.6":
        return disabled
    if provider_id == "zhipu" and model in {"glm-4.5-flash", "glm-5.2"}:
        return disabled
    if provider_id == "doubao" and (model.startswith("doubao-seed-") or model.startswith("ep-")):
        return disabled
    if provider_id == "tokenhub" and model == "hy3":
        return disabled
    if provider_id == "siliconflow" and model == "deepseek-ai/DeepSeek-V4-Flash":
        return {"enable_thinking": False}
    return {}


def _upstream_error(status: int) -> ProviderError:
    if status == 401:
        return ProviderError(401, "API Key 无效或已失效，请检查服务商与密钥是否对应。")
    if status == 403:
        return ProviderError(403, "该 Key 没有调用权限，请检查地域、模型开通状态和密钥类型。")
    if status == 429:
        return ProviderError(429, "服务商限流或额度不足，请稍后重试并检查配额。")
    if status == 402:
        return ProviderError(403, "服务商余额不足，请在对应平台充值或更换可用 Key。")
    if status in {408, 504}:
        return ProviderError(504, "服务商响应超时，请稍后重试。")
    if status in {400, 404, 422}:
        return ProviderError(400, "服务商拒绝请求，请检查模型 ID、接入点、上下文长度或模型兼容性。")
    return ProviderError(502, "AI 服务暂时不可用，请稍后重试。")


def _message(response: httpx.Response) -> dict:
    try:
        payload = response.json()
    except (ValueError, UnicodeDecodeError):
        raise ProviderError(502, "服务商返回了无法解析的响应。") from None
    if not isinstance(payload, dict):
        raise ProviderError(502, "服务商返回的消息格式不正确。")
    if payload.get("error") or payload.get("error_code"):
        raise ProviderError(502, "服务商未能完成请求，请检查配额和模型状态。")
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        raise ProviderError(502, "服务商没有返回可用的聊天消息。")
    message = choices[0].get("message")
    if not isinstance(message, dict):
        raise ProviderError(502, "服务商没有返回可用的聊天消息。")
    return message


def _content(message: dict) -> str:
    content = message.get("content")
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list) and all(
        isinstance(part, dict) and part.get("type") == "text" and isinstance(part.get("text"), str)
        for part in content
    ):
        return "".join(part["text"] for part in content).strip()
    return ""


def _demo_knowledge(context: str) -> str:
    try:
        references = json.loads(context)
    except ValueError:
        return context  # Preserve the earlier text format used by existing callers.
    if not isinstance(references, list) or not all(
        isinstance(source, dict) and type(source.get("index")) is int
        and isinstance(source.get("name"), str) and isinstance(source.get("text"), str)
        for source in references
    ):
        return context
    return "\n\n".join(f"[{source['index']}] 文件：{source['name']}\n{source['text']}" for source in references)


def _demo_answer(messages: list[dict]) -> str:
    user_text = next(
        (message["content"] for message in reversed(messages) if message["role"] == "user"), "你好"
    )
    system = "\n".join(message["content"] for message in messages if message["role"] == "system")
    match = re.search(r"<knowledge>\n(.*?)\n</knowledge>", system, flags=re.DOTALL)
    context = _demo_knowledge(match.group(1)) if match else ""
    if context:
        return (
            f"【本地演示】你问的是：{user_text[:200]}\n\n"
            f"以下是本次传入的知识库上下文节选，仅展示检索结果：\n{context[:1800]}\n\n"
            "这是演示内容，未调用 AI，也未对资料作模型推理。"
        )
    return (
        f"【本地演示】已收到：{user_text[:200]}\n\n"
        "可以上传文档体验知识库检索，或选择 AI 服务商并输入 API Key 开始真实对话。"
    )


def _without_inline_thinking(content: str) -> str:
    """MiniMax's compatible API may put private reasoning at the start of content."""
    if not content.startswith("<think>"):
        return content
    matched = re.match(r"^<think>.*?</think>\s*", content, flags=re.DOTALL)
    return content[matched.end() :].strip() if matched else ""


class ProviderClient:
    def __init__(self, client: httpx.AsyncClient | None = None, *, allow_demo: bool = False):
        self._owns_client = client is None
        self._client = (
            client
            if client is not None
            else httpx.AsyncClient(timeout=_TIMEOUT, follow_redirects=False, trust_env=False)
        )
        self._allow_demo = allow_demo

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def _call(
        self, provider: Provider, key: str, model: str, messages: list[dict], max_tokens: int
    ) -> dict:
        url = f"{provider.base_url}/chat/completions"
        if url not in _ALLOWED_URLS:
            raise ProviderError(400, "服务商地址不在后端允许的列表中。")
        body = {
            "model": model,
            "messages": messages,
            "max_tokens": max_tokens,
            "stream": False,
            **_model_options(provider.id, model),
        }
        try:
            response = await self._client.post(
                url,
                headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
                json=body,
                timeout=_TIMEOUT,
                follow_redirects=False,
            )
        except httpx.TimeoutException:
            raise ProviderError(504, "AI 服务连接或响应超时，请稍后重试。") from None
        except httpx.RequestError:
            raise ProviderError(502, "无法连接 AI 服务，请检查网络后重试。") from None
        if response.status_code != 200:
            raise _upstream_error(response.status_code)
        return _message(response)

    async def validate(self, provider_id: str, key: str, model: str) -> None:
        """Perform a real, billable 8-token chat call, not a key-format authentication check."""
        provider = _provider(provider_id, self._allow_demo)
        if provider.id == "demo":
            return
        messages = _validate_inputs(key, model, [{"role": "user", "content": "只回复 OK"}], 8)
        message = await self._call(provider, key, model, messages, 8)
        reasoning = message.get("reasoning_content")
        if not _content(message) and not (isinstance(reasoning, str) and reasoning.strip()):
            raise ProviderError(502, "服务商返回了空消息，无法验证模型是否可用。")

    async def complete(
        self, provider_id: str, key: str, model: str, messages: list[dict], max_tokens: int = 2048
    ) -> str:
        provider = _provider(provider_id, self._allow_demo)
        copied = _validate_inputs(
            "demo" if provider.id == "demo" else key, model, messages, max_tokens
        )
        if provider.id == "demo":
            return _demo_answer(copied)
        message = await self._call(provider, key, model, copied, max_tokens)
        content = _content(message)
        if provider.id == "minimax":
            content = _without_inline_thinking(content)
        if content:
            return content
        if (
            isinstance(message.get("reasoning_content"), str)
            and message["reasoning_content"].strip()
        ):
            raise ProviderError(
                502, "模型只返回了思考过程，尚无最终回答；请增加输出预算或改用非思考模型。"
            )
        raise ProviderError(502, "模型没有返回文本回答，请重试或改用纯文本聊天模型。")
