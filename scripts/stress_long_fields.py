"""Deterministic real loopback HTTP long-field endurance; no cloud calls.

The unchanged app and harness share a process: reported RSS includes both.
Payloads contain synthetic randomized word postings, not natural conversation.
"""

import argparse
import asyncio
import importlib
import importlib.util
import json
import os
import re
import tempfile
import time
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

HELPER_SPEC = importlib.util.spec_from_file_location("long_fields_helpers", Path(__file__).with_name("smoke_long_fields.py"))
helpers = importlib.util.module_from_spec(HELPER_SPEC)
HELPER_SPEC.loader.exec_module(helpers)


def checkpoint_turns(total):
    return sorted({number for number in (1000, total) if number <= total})


def probe_schedule(total):
    probes = {}
    for stage in checkpoint_turns(total):
        cases = [(role, anchor, location) for role, anchor in (("user", 1), ("assistant", 1), ("assistant", 250))
                 for location in ("middle", "tail")]
        for turn, (role, anchor, location) in zip(range(stage - 5, stage + 1), cases, strict=True):
            probes[turn] = {"stage": stage, "role": role, "anchor_turn": anchor, "location": location}
    return probes


def field_lengths(turn):
    if turn == 1:
        return 16000, 16000
    if turn % 250 == 0:
        return 16000, 64000
    return 2000 + (turn * 977 % 2001), 4000 + (turn * 1879 % 4001)


def distribution(values, unit):
    if not values:
        return {"count": 0}
    ordered = sorted(values)

    def percentile(fraction):
        position = (len(ordered) - 1) * fraction
        index = int(position)
        return round(ordered[index] + (ordered[min(index + 1, len(ordered) - 1)] - ordered[index]) * (position - index), 3)

    return {"count": len(values), f"min_{unit}": min(values), f"p50_{unit}": percentile(0.5),
            f"p95_{unit}": percentile(0.95), f"p99_{unit}": percentile(0.99),
            f"max_{unit}": max(values), f"mean_{unit}": round(sum(values) / len(values), 3)}


def process_memory():
    result = {"scope": "Combined process: Uvicorn app, synthetic provider, HTTP client and verification harness"}
    process = Path("/proc/self/status")
    if process.exists():
        result["process"] = {key: value.strip() for key, value in
                             (line.split(":", 1) for line in process.read_text().splitlines())
                             if key in ("VmRSS", "VmHWM", "VmSize", "Threads")}
    result["cgroup"] = {}
    for name in ("memory.current", "memory.peak", "memory.max"):
        path = Path("/sys/fs/cgroup") / name
        if path.exists():
            value = path.read_text().strip()
            result["cgroup"][name] = int(value) if value.isdigit() else value
    return result


def initial_report(total):
    return {"format_version": 1, "started_at": datetime.now(UTC).isoformat(), "status": "running",
            "requested_turns": total, "completed_turns": 0, "cloud_calls": 0, "concurrency": 1,
            "scope": "Real loopback HTTP, unchanged app, synthetic randomized Chinese/ASCII/emoji/newline fields; no cloud semantics",
            "planned_normal_lengths_chars": {"user": [2000, 4000], "assistant": [4000, 8000]},
            "planned_boundary_lengths_chars": {"first_turn": [16000, 16000], "every_250_turns": [16000, 64000],
                                               "except_checkpoint_probe_turns": True},
            "http_requests": 0, "unexpected_http_failures": 0, "response_payload_bytes": 0,
            "saved_original_utf8_bytes": {"user": 0, "assistant": 0},
            "checkpoints": [], "probes": [], "storage": {}}


def write_report(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def verify_complete(report):
    helpers.require(report["completed_turns"] == report["requested_turns"], "Requested endurance turns not completed")
    required = checkpoint_turns(report["requested_turns"])
    checkpoints = report["checkpoints"]
    helpers.require([item["completed_turns"] for item in checkpoints] == required, "Required checkpoints missing")
    helpers.require(all(item["status"] == "passed" and item.get("probe_count") == 6 for item in checkpoints),
                    "Required checkpoint probes or checks did not pass")


class EnduranceProvider:
    async def validate(self, *args):
        return None

    async def complete(self, provider_id, api_key, model, messages):
        match = re.match(r"LONGFIELD turn=(\d+) role=user", messages[-1]["content"])
        if match:
            turn = int(match[1])
            return helpers.long_payload(field_lengths(turn)[1], turn, "assistant")
        return "合成短回复用于探针HTTP验证，不回显检索事实。"


class EnduranceRun(helpers.LongFieldRun):
    def __init__(self, data_dir, factory, report, report_path, progress_every, pause, emit):
        super().__init__(data_dir, factory, report)
        self.report_path, self.progress_every, self.pause, self.emit = report_path, progress_every, pause, emit
        self.schedule = probe_schedule(report["requested_turns"])
        self.started, self.paused = time.perf_counter(), 0.0
        self.latencies, self.user_lengths, self.assistant_lengths = [], [], []
        self.foreign_headers = {}

    def call(self, method, path, expected=200, **kwargs):
        start = time.perf_counter()
        result = super().call(method, path, expected, **kwargs)
        if method == "POST" and path.endswith("/messages") and expected == 200:
            self.latencies.append((time.perf_counter() - start) * 1000)
        return result

    def record_turn(self, turn):
        probe = self.schedule.get(turn)
        if probe:
            content = helpers.anchor_marker(probe["role"], probe["location"], probe["anchor_turn"])
        else:
            content = helpers.long_payload(field_lengths(turn)[0], turn, "user")
        result = super().send(content)
        user, assistant = result["user_message"], result["assistant_message"]
        if not probe:
            correct = helpers.long_payload(field_lengths(turn)[1], turn, "assistant")
            helpers.require(assistant["content"] == correct, "Synthetic assistant long reply mismatch")
        else:
            anchor_user, anchor_assistant = self.saved[probe["anchor_turn"]]
            original = anchor_user if probe["role"] == "user" else anchor_assistant
            checked = helpers.verify_reference(result["memory_refs"], original, anchor_user["id"],
                                               probe["location"], probe["anchor_turn"])
            self.report["probes"].append({**checked, "stage": probe["stage"], "request_turn": turn})
        if turn in (1, 250):
            self.saved[turn] = (user, assistant)
        self.user_lengths.append(len(user["content"]))
        self.assistant_lengths.append(len(assistant["content"]))

    def refresh(self):
        self.report["elapsed_seconds_excluding_checkpoint_pauses"] = round(time.perf_counter() - self.started - self.paused, 3)
        self.report["checkpoint_pause_seconds"] = round(self.paused, 3)
        self.report["message_request_latency"] = distribution(self.latencies, "ms")
        self.report["role_lengths_chars"] = {"user": distribution(self.user_lengths, "chars"),
                                            "assistant": distribution(self.assistant_lengths, "chars")}
        self.report["actual_boundary_counts"] = {"user_16000": sum(value == 16000 for value in self.user_lengths),
                                                  "assistant_16000": sum(value == 16000 for value in self.assistant_lengths),
                                                  "assistant_64000": sum(value == 64000 for value in self.assistant_lengths)}
        self.report["resource_sample"] = process_memory()
        write_report(self.report_path, self.report)

    def verify_rejection(self):
        before = helpers.snapshot(self.path)
        self.call("POST", f"/api/conversations/{self.identifier}/messages", expected=422, headers=self.headers,
                  json={"content": helpers.long_payload(16001, 99999, "user")})
        helpers.verify_rejected_unchanged(before, helpers.snapshot(self.path))
        self.report["user_16001_rejected_without_message_or_index_write"] = True

    def verify_isolation(self):
        session = self.call("POST", "/api/auth/login", json={"provider_id": "demo", "api_key": "long-isolation-" + uuid4().hex})
        self.foreign_headers = {"Authorization": "Bearer " + session["token"]}
        self.call("GET", f"/api/conversations/{self.identifier}/history", expected=404, headers=self.foreign_headers)
        self.call("POST", f"/api/conversations/{self.identifier}/messages", expected=404,
                  headers=self.foreign_headers, json={"content": "独立身份隔离检查"})
        self.call("DELETE", "/api/auth/session", expected=204, headers=self.foreign_headers)
        self.foreign_headers = {}
        return {"foreign_identity_read_write_rejected": True}

    def verify_restart(self):
        old_headers = self.headers
        self.client.close()
        self.server.stop()
        self.start()
        self.call("GET", "/api/auth/me", expected=401, headers=old_headers)
        self.login()
        return {"old_session_rejected": True, "same_key_reauthenticated": True,
                "history": self.history()}

    def checkpoint(self, turn):
        probes = [item for item in self.report["probes"] if item["stage"] == turn]
        helpers.require(len(probes) == 6 and all(item["passed"] for item in probes), "Checkpoint probes incomplete")
        checkpoint = {"completed_turns": turn, "status": "checking", "probe_count": len(probes)}
        self.report["checkpoints"].append(checkpoint)
        self.refresh()
        self.emit({"event": "checkpoint_checking", "completed_turns": turn})
        checkpoint["history_before_restart"] = self.history()
        checkpoint["isolation"] = self.verify_isolation()
        checkpoint["restart"] = self.verify_restart()
        checkpoint["storage"] = helpers.snapshot(self.path)
        helpers.require(checkpoint["storage"]["messages"] == 2 * turn, "SQLite message count does not match exact turns")
        helpers.require(checkpoint["storage"]["content_utf8_bytes"] == sum(self.bytes.values()), "SQLite saved original byte count mismatch")
        checkpoint["fragment_coverage"] = helpers.verify_all_fragments(self.path)
        helpers.require(checkpoint["fragment_coverage"]["messages_verified"] == 2 * turn, "Not every message indexed")
        checkpoint["resource_sample"] = process_memory()
        checkpoint["status"] = "passed"
        self.refresh()
        self.emit({"event": "checkpoint", "completed_turns": turn, "messages": 2 * turn, "status": "passed",
                   "pause_seconds": self.pause, "report": str(self.report_path)})
        if self.pause:
            start = time.perf_counter()
            time.sleep(self.pause)
            self.paused += time.perf_counter() - start

    def execute(self):
        self.start()
        self.report["storage"]["baseline"] = helpers.snapshot(self.path)
        helpers.require(self.report["storage"]["baseline"]["messages"] == 0
                        and self.report["storage"]["baseline"]["conversations"] == 0, "Use a new isolated endurance data directory")
        self.login()
        conversation = self.call("POST", "/api/conversations", expected=201, headers=self.headers,
                                 json={"title": "双方长字段耐久测试", "use_rag": False})
        self.identifier = conversation["id"]
        self.report["conversation_id"] = self.identifier
        self.emit({"event": "started", "requested_turns": self.report["requested_turns"], "conversation_id": self.identifier})
        self.verify_rejection()
        for turn in range(1, self.report["requested_turns"] + 1):
            self.record_turn(turn)
            if turn % self.progress_every == 0:
                self.refresh()
                self.emit({"event": "progress", "completed_turns": turn, "requested_turns": self.report["requested_turns"],
                           "last_request_ms": round(self.latencies[-1], 3),
                           "elapsed_seconds": self.report["elapsed_seconds_excluding_checkpoint_pauses"]})
            if turn in checkpoint_turns(self.report["requested_turns"]):
                self.checkpoint(turn)
        verify_complete(self.report)
        self.report["status"] = "passed"

    def cleanup(self):
        foreign_error = False
        if self.client and self.foreign_headers:
            try:
                self.call("DELETE", "/api/auth/session", expected=204, headers=self.foreign_headers)
            except Exception:  # noqa: BLE001 -- preserve original error while continuing own resource cleanup.
                foreign_error = True
        super().cleanup()
        if foreign_error:
            self.report["cleanup"]["errors"].append("Could not revoke own disposable isolation session")
            self.report["cleanup"]["completed"] = False
            self.report["status"] = "failed"
        self.report["cleanup"]["data_directory_deleted"] = False
        self.report["cleanup"]["database_file_may_retain_reusable_free_pages"] = True


def emit(item):
    print(json.dumps(item, ensure_ascii=False, separators=(",", ":")), flush=True)


def run(report_path, data_dir, turns=10000, progress_every=100, checkpoint_pause_seconds=5, output=emit):
    if turns not in (1000, 10000) or progress_every < 1 or not 0 <= checkpoint_pause_seconds <= 60:
        raise ValueError("Require turns in (1000,10000), progress-every >=1, checkpoint-pause-seconds in [0,60]")
    report, experiment = initial_report(turns), None
    with tempfile.TemporaryDirectory(prefix="zhiyu-long-bootstrap-") as bootstrap:
        previous = os.environ.get("ZHIYU_DATA_DIR")
        os.environ["ZHIYU_DATA_DIR"] = bootstrap
        try:
            main = importlib.import_module("app.main")
            from app.config import Settings

            def factory(path):
                return main.create_app(Settings(data_dir=path, allow_demo=True), provider_client=EnduranceProvider())

            experiment = EnduranceRun(data_dir, factory, report, report_path, progress_every, checkpoint_pause_seconds, output)
            experiment.execute()
        except KeyboardInterrupt:
            report["status"] = "aborted"
            report["failure"] = {"type": "KeyboardInterrupt", "message": "Test interrupted; only verified completed turns are reported"}
        except Exception as error:  # noqa: BLE001 -- sanitize arbitrary exception bodies before persisting evidence.
            report["status"] = "failed"
            report["failure"] = {"type": type(error).__name__,
                                 "message": str(error) if isinstance(error, helpers.CheckFailure) else "Unexpected harness error; sensitive body withheld"}
        finally:
            if experiment:
                try:
                    experiment.cleanup()
                except Exception:  # noqa: BLE001 -- retain primary failure and still restore bootstrap/environment.
                    report["status"] = "failed"
                    report["cleanup_internal_error"] = True
                try:
                    experiment.refresh()
                except Exception:  # noqa: BLE001 -- evidence publication failure cannot mask the original failure.
                    report["status"] = "failed"
                    report["checkpoint_publication_failed"] = True
            if "main" in locals():
                try:
                    asyncio.run(main.app.state.provider.aclose())
                except Exception:  # noqa: BLE001 -- bootstrap close failure prevents a green report.
                    report["status"] = "failed"
                    report["bootstrap_close_failed"] = True
            if previous is None:
                os.environ.pop("ZHIYU_DATA_DIR", None)
            else:
                os.environ["ZHIYU_DATA_DIR"] = previous
    report["finished_at"] = datetime.now(UTC).isoformat()
    write_report(report_path, report)
    output({"event": "finished", "status": report["status"], "completed_turns": report["completed_turns"], "report": str(report_path)})
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--turns", type=int, choices=(1000, 10000), default=10000)
    parser.add_argument("--report", type=Path, default=Path("artifacts/long-endurance/report.json"))
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--progress-every", type=int, default=100)
    parser.add_argument("--checkpoint-pause-seconds", type=float, default=5)
    options = parser.parse_args()
    result = run(options.report, options.data_dir, options.turns, options.progress_every, options.checkpoint_pause_seconds)
    raise SystemExit(0 if result["status"] == "passed" else 1)
