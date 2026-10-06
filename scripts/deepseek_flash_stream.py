"""Full-answer SSE measurement at the verified official model output ceiling."""

import json
import time

import httpx
from deepseek_flash_eval import ENDPOINT, estimate_cost, grade, payload, redact

MAX_TOKENS = 393216  # Official /models metadata and Chat Completions docs, 2026-10-06.
MAX_CONTENT_BYTES = 64 * 1024 * 1024


def stream_payload(messages, mode):
    base = payload(messages, mode)
    return {**base, "max_tokens": MAX_TOKENS, "stream": True,
            "stream_options": {"include_usage": True}}


def _usage(value):
    names = ("prompt_tokens", "completion_tokens", "total_tokens",
             "prompt_cache_hit_tokens", "prompt_cache_miss_tokens")
    details = value.get("completion_tokens_details") or {}
    return {**{name: value.get(name) for name in names},
            "reasoning_tokens": details.get("reasoning_tokens")}


def _consume_frame(state, frame, elapsed):
    if frame.get("error"):
        raise ValueError("upstream_stream_error")
    if frame.get("model"):
        state["model_returned"] = frame["model"]
    if frame.get("usage"):
        state["usage"] = _usage(frame["usage"])
    choices = frame.get("choices", [])
    if not choices:
        return
    choice = choices[0]
    delta = choice.get("delta") or {}
    content, reasoning = delta.get("content") or "", delta.get("reasoning_content") or ""
    if not isinstance(content, str) or not isinstance(reasoning, str):
        raise TypeError("invalid_stream_fields")
    if reasoning:
        state["reasoning_chars"] += len(reasoning)
        if state["first_reasoning_seconds"] is None:
            state["first_reasoning_seconds"] = elapsed
    if content:
        state["content_bytes"] += len(content.encode("utf-8"))
        if state["content_bytes"] > MAX_CONTENT_BYTES:
            raise OverflowError("content_size_limit")
        state["parts"].append(content)
        if state["first_content_seconds"] is None:
            state["first_content_seconds"] = elapsed
    if choice.get("finish_reason") is not None:
        state["finish_reason"] = choice["finish_reason"]


def _state():
    return {"parts": [], "content_bytes": 0, "reasoning_chars": 0, "usage": {},
            "model_returned": None, "finish_reason": None, "done_received": False,
            "first_reasoning_seconds": None, "first_content_seconds": None}


async def _request_stream(client, key, body):
    state, start = _state(), time.perf_counter()
    async with client.stream("POST", ENDPOINT, headers={"Authorization": f"Bearer {key}"}, json=body) as response:
        state["http_status"] = response.status_code
        if response.status_code != 200:
            return {**state, "error": "upstream_http_error"}
        async for line in response.aiter_lines():
            if not line.startswith("data:"):
                continue
            value = line[5:].strip()
            if value == "[DONE]":
                state["done_received"] = True
                break
            if value:
                _consume_frame(state, json.loads(value), time.perf_counter() - start)
    if not state["done_received"]:
        state["error"] = "stream_incomplete"
    return state


async def run_stream_trial(client, key, trial):
    """No whole-request deadline; callers configure connect/write/pool, read=None."""
    start = time.perf_counter()
    case = trial["case"]
    try:
        result = await _request_stream(client, key, stream_payload(case["messages"], trial["mode"]))
    except OverflowError:
        result = {**_state(), "http_status": None, "error": "content_size_limit"}
    except (httpx.HTTPError, ValueError, TypeError, KeyError, IndexError, AttributeError) as exc:
        result = {**_state(), "http_status": None, "error": type(exc).__name__}
    elapsed = round(time.perf_counter() - start, 4)
    content = "".join(result.pop("parts"))
    result.pop("content_bytes")
    result = redact({**result, "content": content}, key)
    finished = result["done_received"] and not result.get("error")
    scoring = grade(case, result["content"], result["finish_reason"] if finished else None)
    return {"case_id": case["id"], "category": case["category"], "repeat": trial["repeat"],
            "mode": trial["mode"], "latency_seconds": elapsed, **result,
            "estimated_cny_offpeak": estimate_cost(result["usage"]), "grade": scoring,
            "production_usable_pass": scoring["usable_pass"] and (case["category"] != "semantic" or elapsed <= 15)}
