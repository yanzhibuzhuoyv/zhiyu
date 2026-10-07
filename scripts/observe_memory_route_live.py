"""Authorized synthetic ASGI observation, full official answers and no live skip."""

import argparse
import asyncio
import hashlib
import json
import os
import re
import sys
import time
import uuid
from dataclasses import asdict
from datetime import UTC, datetime
from pathlib import Path

from scripts import eval_memory_route as evaluation
from scripts.eval_memory_route_flows import PROFILE_SHA, reference_evidence, seed_flow
from scripts.memory_route_linear import LinearPolicy, LinearRouter
from scripts.replay_memory_route_linear import load_profile

ROOT = evaluation.ROOT
MAX_OUTPUT_TOKENS = 393216
EXTENDED = "今晚有点累，想听你慢慢陪我说会儿话。请自然地写1500到2000个汉字，陪我从今晚的烦闷聊到明早的小期待；别讲大道理，也不要虚构我们一起线下做过的事。"


def live_inputs(flow):
    return [
        *[{"id": item["id"], "text": item["text"]} for item in flow["turns"]],
        {"id": flow["id"] + "-extended", "text": EXTENDED},
    ]


def write_report(output, report, secret):
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if secret in text:
        raise ValueError("报告包含密钥")
    (output / "results.json").write_text(text + "\n", encoding="utf-8")


def record_event(event, result):
    names = (
        "http_status",
        "latency_seconds",
        "first_content_seconds",
        "first_reasoning_seconds",
        "reasoning_chars",
        "usage",
        "finish_reason",
        "done_received",
        "model_returned",
        "error",
    )
    event.update({k: result.get(k) for k in names})
    event["status"] = "complete" if result["grade"]["usable_pass"] else "failed"


def make_provider(mode):
    sys.path.insert(0, str(ROOT / "scripts"))
    import httpx
    from deepseek_flash_stream import run_stream_trial

    from app.providers import ProviderClient, ProviderError

    class ObservedProvider(ProviderClient):
        def __init__(self):
            super().__init__(
                httpx.AsyncClient(
                    timeout=httpx.Timeout(None, connect=15, write=30, pool=30),
                    trust_env=False,
                    follow_redirects=False,
                )
            )
            self.events = []

        async def aclose(self):
            await self._client.aclose()

        async def _call(self, provider, key, model, messages, max_tokens):
            if provider.id != "deepseek" or model != "deepseek-flash":
                raise ValueError("仅允许官方DeepSeek Flash合成测试")
            phase = {8: "auth", 2048: "chat", 4096: "memory"}[max_tokens]
            case = {
                "id": str(len(self.events)),
                "category": "reply",
                "messages": messages,
                "required_checks": [],
                "forbidden_checks": [],
            }
            event = {
                "phase": phase,
                "thinking": mode if phase == "chat" else "disabled",
                "status": "interrupted",
                "max_output_tokens": MAX_OUTPUT_TOKENS,
            }
            self.events.append(event)
            result = await run_stream_trial(
                self._client,
                key,
                {"case": case, "mode": event["thinking"], "repeat": 1},
            )
            record_event(event, result)
            if event["status"] != "complete":
                raise ProviderError(502, "测试未收到完整最终答案")
            return {"content": result["content"]}

    return ObservedProvider()


async def wait_job(app, identifier):
    async with asyncio.timeout(35):
        while True:
            with app.state.chat.connection() as db:
                row = db.execute(
                    "SELECT status,attempts FROM memory_jobs WHERE conversation_id=? "
                    "ORDER BY user_sequence DESC LIMIT 1",
                    (identifier,),
                ).fetchone()
            if row and row["status"] not in {"pending", "running", "waiting_auth"}:
                return dict(row)
            await asyncio.sleep(0.02)


async def send_turn(app, client, headers, cid, owner, item, router, saved):
    from app.conversation_memory import RECENT_CHARS, RECENT_TURNS, recent_history

    history, _ = recent_history(
        app.state.chat.recent_turns(owner, cid, RECENT_TURNS, RECENT_CHARS)
    )
    start = time.perf_counter()
    decision = await asyncio.to_thread(router.decide, item["text"], history[-4:])
    classifier_ms = (time.perf_counter() - start) * 1000
    before_events = len(app.state.provider.events)
    start = time.perf_counter()
    response = await client.post(
        f"/api/conversations/{cid}/messages",
        headers=headers,
        json={"content": item["text"]},
    )
    returned = time.perf_counter() - start
    if response.status_code != 200:
        raise RuntimeError("实际聊天请求失败")
    payload = response.json()
    if payload["memory_recognition"]["status"] != "pending":
        raise RuntimeError("聊天返回前不应等待记忆识别")
    refs = payload["memory_refs"]
    reference_evidence(refs, {m["id"]: m for m in saved})
    saved.extend([payload["user_message"], payload["assistant_message"]])
    job = await wait_job(app, cid)
    if app.state.chat.list_messages(owner, cid) != saved:
        raise RuntimeError("实际返回与完整原文保存不一致")
    events = app.state.provider.events[before_events:]
    chat = next(e for e in events if e["phase"] == "chat")
    answer = payload["assistant_message"]["content"]
    return {
        "id": item["id"],
        "input": item["text"],
        "answer": answer,
        "answer_chars": len(answer),
        "answer_han": len(re.findall(r"[\u4e00-\u9fff]", answer)),
        "route_observation": asdict(decision),
        "classifier_ms": classifier_ms,
        "http_return_seconds": returned,
        "first_content_seconds": chat["first_content_seconds"],
        "memory_finished_after_http_seconds": time.perf_counter() - start - returned,
        "job": job,
        "references": refs,
        "originals_identical": True,
    }


async def login_and_seed(app, client, flow, credentials):
    response = await client.post(
        "/api/auth/login",
        json={
            "provider_id": "deepseek",
            "api_key": credentials.key,
            "model": "deepseek-flash",
        },
    )
    if response.status_code != 200:
        raise RuntimeError("合成模型测试鉴权失败")
    token = response.json()["token"]
    headers = {"Authorization": "Bearer " + token}
    owner = app.state.sessions.get(token).owner
    response = await client.post(
        "/api/conversations",
        headers=headers,
        json={
            "use_rag": False,
            "companion_profile": {
                "mode": "preset",
                "style": "gentle",
                "role": "partner",
                "name": "小语",
            },
        },
    )
    if response.status_code != 201:
        raise RuntimeError("合成会话创建失败")
    cid = response.json()["id"]
    saved, _ = seed_flow(app.state.chat, owner, cid, flow)
    return headers, owner, cid, saved


async def one_mode(output, flow, router, credentials, mode, report):
    import httpx

    from app.config import Settings
    from app.main import create_app

    provider = make_provider(mode)
    app = create_app(
        Settings(data_dir=output / mode, auto_memory_annotations=True), provider
    )
    record = {"mode": mode, "events": provider.events, "turns": []}
    report["modes"].append(record)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://synthetic.invalid"
        ) as client:
            headers, owner, cid, saved = await login_and_seed(
                app, client, flow, credentials
            )
            for item in live_inputs(flow):
                turn = await send_turn(
                    app, client, headers, cid, owner, item, router, saved
                )
                record["turns"].append(turn)
                write_report(output, report, credentials.key)
                print(
                    json.dumps(
                        {
                            "mode": mode,
                            "id": item["id"],
                            "http_s": round(turn["http_return_seconds"], 3),
                            "chars": turn["answer_chars"],
                            "job": turn["job"]["status"],
                        }
                    ),
                    flush=True,
                )
            record["message_count"] = len(saved)
            record["history_sha256"] = hashlib.sha256(
                json.dumps(saved, ensure_ascii=False, sort_keys=True).encode()
            ).hexdigest()
            if (
                await client.delete("/api/auth/session", headers=headers)
            ).status_code != 204:
                raise RuntimeError("合成会话注销失败")


def prepare(args):
    from scripts.memory_route_flows import flows

    output, model = Path(args.output), Path(args.model).resolve(strict=True)
    output.mkdir(parents=True, exist_ok=False)
    os.environ["ZHIYU_DATA_DIR"] = str(output / "unused-global")
    sys.path[:0] = [str(ROOT / "backend"), str(ROOT / "scripts")]
    from deepseek_flash_eval import load_credentials

    profile = load_profile(args.profile, PROFILE_SHA, model)
    credentials = load_credentials(args.credentials)
    flow = flows()[0]  # Preselected first flow; never chosen from scores/results.
    hashes = {
        str(p.relative_to(ROOT)): evaluation.file_hash(p)
        for p in (
            *sorted((ROOT / "backend/app").rglob("*.py")),
            Path(__file__),
            ROOT / "scripts/eval_memory_route_flows.py",
            ROOT / "scripts/memory_route_flows.py",
        )
    }
    evaluation.write(
        output / "frozen-inputs.json", {"flow": flow, "live_inputs": live_inputs(flow)}
    )
    evaluation.write(
        output / "manifest.json",
        {
            "source_sha256": hashes,
            "profile_sha256": PROFILE_SHA,
            "model_sha256": evaluation.model_hash(model),
            "started_utc": datetime.now(UTC).isoformat(),
            "max_output_tokens": MAX_OUTPUT_TOKENS,
            "memory_worker_timeout_s": 15,
            "maximum_calls": 46,
            "modes": ["disabled", "enabled"],
            "memory_thinking": "disabled",
            "production_skip_enabled": False,
            "cloud_frontend_classifier_calls": 0,
            "meaning": "Synthetic ASGI only; replies change later context, no independent live intent gold.",
        },
    )
    encoder = evaluation.BgeEncoder(model)
    router = LinearRouter(
        encoder,
        profile["prototypes"],
        profile["head"]["coefficients"],
        profile["head"]["intercept"],
        LinearPolicy(**profile["policy"]),
    )
    return output, model, credentials, flow, hashes, router


async def run(args):
    output, model, credentials, flow, hashes, router = prepare(args)
    report = {"status": "running", "modes": [], "production_skip_enabled": False}
    write_report(output, report, credentials.key)
    try:
        for mode in ("disabled", "enabled"):
            await one_mode(output, flow, router, credentials, mode, report)
        if evaluation.file_hash(args.credentials) != credentials.file_sha256:
            raise RuntimeError("原凭据文件发生变化")
        if any(evaluation.file_hash(ROOT / p) != h for p, h in hashes.items()):
            raise RuntimeError("测试期间源码发生变化")
        load_profile(args.profile, PROFILE_SHA, model)
        for path in output.rglob("*"):
            if path.is_file() and credentials.key.encode() in path.read_bytes():
                raise ValueError("测试数据含密钥")
        calls = sum(len(m["events"]) for m in report["modes"])
        if calls != 46:
            raise RuntimeError("实际调用数与固定日程不一致")
        report.update(
            status="completed",
            cloud_calls=calls,
            originals_identical=True,
            credential_file_unchanged=True,
            source_files_unchanged=True,
            finished_utc=datetime.now(UTC).isoformat(),
        )
    except BaseException as error:
        report.update(status="failed", error_type=type(error).__name__)
        write_report(output, report, credentials.key)
        raise
    write_report(output, report, credentials.key)
    print(
        json.dumps({"status": report["status"], "output": str(output), "calls": calls}),
        flush=True,
    )
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--credentials", required=True)
    parser.add_argument(
        "--output",
        default=str(
            ROOT
            / "artifacts"
            / (
                "memory-route-live-"
                + datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
                + "-"
                + uuid.uuid4().hex[:6]
            )
        ),
    )
    args = parser.parse_args()
    try:
        asyncio.run(run(args))
    except BaseException as error:
        print(
            json.dumps({"status": "failed", "error_type": type(error).__name__}),
            flush=True,
        )
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
