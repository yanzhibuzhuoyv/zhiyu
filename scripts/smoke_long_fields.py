"""Real loopback HTTP long-field smoke; synthetic provider, isolated temporary SQLite.

This boundary check is not a cloud-model or 10,000 long-turn benchmark. Run the
unchanged runtime app with an injected provider; no shared server or volume used.
"""

import argparse
import asyncio
import hashlib
import importlib
import json
import os
import random
import re
import socket
import sqlite3
import tempfile
import threading
import time
from contextlib import closing
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import httpx

HAN = "山河星海林间青翠暮云晨光舟桥花叶风雨湖岸书笺窗灯影歌声沙石白紫金月日光暖清凉远近红蓝绿玉竹露泉水雾谷庭鸟木禾露棠兰梅苇荷桃"
LONG_TURNS = 36
FILLER_TURNS = 25


class CheckFailure(AssertionError):
    """Diagnostics deliberately contain no raw HTTP bodies, keys or chat text."""


def require(condition, message):
    if not condition:
        raise CheckFailure(message)


def anchor_marker(role, location, turn):
    return f"Long{role.title()}{location.title()}{turn:04d}"


def long_payload(chars, turn, role):
    rng = random.Random(f"zhiyu-long:{role}:{turn}")
    parts = [f"LONGFIELD turn={turn:04d} role={role} 合成字段边界测试。\n"]
    length = len(parts[0])
    while length < chars:
        line = ("".join(rng.choices(HAN, k=48)) + " "
                + "".join(rng.choices("abcdefghijklmnopqrstuvwxyz0123456789", k=16))
                + rng.choice((" 🌙\n", " 🌿\n", " 🪷\n")))
        parts.append(line)
        length += len(line)
    text = "".join(parts)[:chars]
    marker = anchor_marker(role, "middle", turn) + "：中部花纹原记录。"
    middle = chars // 2
    text = text[:middle] + marker + text[middle + len(marker):]
    tail = anchor_marker(role, "tail", turn) + "：末尾暗号原记录。"
    return text[:-len(tail)] + tail


def signature(message):
    fields = {key: message[key] for key in ("id", "role", "content", "sources", "created_at")}
    return hashlib.sha256(json.dumps(fields, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def verify_page(messages, expected, position, seen):
    require(0 < len(messages) <= 100, "Invalid history page size")
    start = position - len(messages)
    require(start >= 0, "Extra history messages")
    for index, message in enumerate(messages, start):
        require(message["id"] not in seen, "Duplicate history message")
        require(signature(message) == expected[index], "History content/metadata/order hash mismatch")
        require(message["role"] == ("user" if index % 2 == 0 else "assistant"), "History turn pairing mismatch")
        seen.add(message["id"])
    return start


def verify_reference(references, message, turn_id, location, turn):
    marker = anchor_marker(message["role"], location, turn)
    matches = [ref for ref in references
               if ref.get("message_id") == message["id"] and ref.get("turn_id") == turn_id
               and ref.get("role") == message["role"] and ref.get("created_at") == message["created_at"]
               and isinstance(ref.get("text"), str) and marker in ref["text"]
               and ref["text"] in message["content"] and 0 < len(ref["text"]) <= 768
               and ref.get("truncated") is True]
    require(bool(matches), "Long original reference missing or provenance/excerpt mismatch")
    return {"role": message["role"], "location": location, "anchor_turn": turn,
            "message_id": message["id"], "turn_id": turn_id, "passed": True,
            "raw_excerpt_verified": True}


def verify_fragment_rows(chars, rows):
    require(bool(rows), "Missing fragments for message")
    covered = 0
    for start, end, fts_present in rows:
        require(fts_present, "Fragment missing FTS posting row")
        require(0 <= start < end <= chars and end - start <= 768, "Invalid fragment offsets")
        require(start <= covered and end > covered, "Fragment coverage gap or invalid ordering")
        covered = end
    require(rows[0][0] == 0 and covered == chars, "Fragment coverage misses message head or tail")
    return {"fragments": len(rows), "covered_chars": covered}


def verify_rejected_unchanged(before, after):
    for key in ("messages", "fragments", "fts_rows", "conversations", "content_utf8_bytes"):
        require(before[key] == after[key], "Rejected message changed saved messages/index")


def verify_snapshot_integrity(result):
    require(result["quick_check"] == "ok", "SQLite quick_check failed")
    require(result["fragments"] == result["fts_rows"], "FTS/fragment counts differ")


def snapshot(path):
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        counts = db.execute("SELECT role,count(*),coalesce(sum(length(CAST(content AS BLOB))),0) FROM messages GROUP BY role").fetchall()
        result = {"messages": sum(row[1] for row in counts), "content_utf8_bytes": sum(row[2] for row in counts),
                  "roles": {role: {"count": count, "utf8_bytes": size} for role, count, size in counts},
                  "conversations": db.execute("SELECT count(*) FROM conversations").fetchone()[0],
                  "fragments": db.execute("SELECT count(*) FROM memory_fragments").fetchone()[0],
                  "fts_rows": db.execute("SELECT count(*) FROM memory_fts").fetchone()[0],
                  "sqlite": {name: db.execute("PRAGMA " + name).fetchone()[0]
                             for name in ("page_size", "page_count", "freelist_count")},
                  "quick_check": db.execute("PRAGMA quick_check").fetchone()[0]}
        verify_snapshot_integrity(result)
        require(db.execute("SELECT count(*) FROM memory_fts WHERE tokens IS NOT NULL").fetchone()[0] == 0,
                "Contentless index contains an original text copy")
        try:
            tables = dict(db.execute("SELECT name,sum(pgsize) FROM dbstat GROUP BY name"))
            result["allocation"] = {"available": True, "tables_bytes": tables,
                                    "index_bytes": sum(size for name, size in tables.items()
                                                       if name.startswith(("memory_", "sqlite_autoindex_memory_")))}
        except sqlite3.OperationalError:
            result["allocation"] = {"available": False}
    result["files_bytes"] = {suffix or "main": Path(str(path) + suffix).stat().st_size
                             if Path(str(path) + suffix).exists() else 0 for suffix in ("", "-wal", "-shm")}
    result["measurement_mutates_database"] = False
    return result


def verify_all_fragments(path):
    totals = {"messages_verified": 0, "fragments_verified": 0, "long_messages_verified": 0}
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        for sequence, chars in db.execute("SELECT sequence,length(content) FROM messages ORDER BY sequence"):
            rows = db.execute("SELECT f.char_start,f.char_end,ft.rowid IS NOT NULL FROM memory_fragments f LEFT JOIN memory_fts ft ON ft.rowid=f.id WHERE f.message_sequence=? ORDER BY f.char_start", (sequence,)).fetchall()
            result = verify_fragment_rows(chars, rows)
            totals["messages_verified"] += 1
            totals["fragments_verified"] += result["fragments"]
            totals["long_messages_verified"] += int(chars >= 16000)
    return {**totals, "full_message_offsets_and_fts_rows_verified": True}


class SyntheticProvider:
    async def validate(self, *args):
        return None

    async def complete(self, provider_id, api_key, model, messages):
        text = messages[-1]["content"]
        match = re.match(r"LONGFIELD turn=(\d+) role=user", text)
        if match:
            turn = int(match[1])
            return long_payload(16000 if turn <= 32 else 64000, turn, "assistant")
        return "这是独立合成短回复，用于移出近期窗口与HTTP链路检查。"


class LoopbackServer:
    def __init__(self, app):
        self.app, self.failure, self.thread, self.server, self.listener = app, None, None, None, None
        self.stopping = False

    def check(self):
        require(self.failure is None, "Background server failed; exception body withheld")
        if self.thread and not self.stopping:
            require(self.thread.is_alive(), "Background server stopped unexpectedly")

    def start(self):
        import uvicorn

        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        port = self.listener.getsockname()[1]
        self.server = uvicorn.Server(uvicorn.Config(self.app, host="127.0.0.1", port=port,
                                                   log_level="critical", access_log=False, ws="none", loop="asyncio"))

        def worker():
            try:
                self.server.run(sockets=[self.listener])
            except BaseException as error:  # noqa: BLE001 -- propagate through sanitized check(), including thread exit.
                self.failure = error

        self.thread = threading.Thread(target=worker, daemon=True)
        self.thread.start()
        deadline = time.monotonic() + 15
        while not self.server.started:
            self.check()
            require(time.monotonic() < deadline, "Loopback HTTP server startup timed out")
            time.sleep(0.01)
        self.check()
        return f"http://127.0.0.1:{port}"

    def stop(self):
        self.stopping = True
        try:
            if self.server:
                self.server.should_exit = True
            if self.thread:
                self.thread.join(timeout=15)
                if self.thread.is_alive():
                    self.server.force_exit = True
                    self.thread.join(timeout=3)
                require(not self.thread.is_alive(), "Loopback HTTP server failed to stop")
        finally:
            if self.listener:
                self.listener.close()
        self.check()


class LongFieldRun:
    def __init__(self, data_dir, factory, report):
        self.data_dir, self.factory, self.report = data_dir, factory, report
        self.server, self.client, self.headers, self.identifier = None, None, {}, None
        self.key = "synthetic-long-fields-" + uuid4().hex
        self.expected, self.saved, self.bytes = [], {}, {"user": 0, "assistant": 0}

    @property
    def path(self):
        return self.data_dir / "chat.sqlite3"

    def start(self):
        self.server = LoopbackServer(self.factory(self.data_dir))
        url = self.server.start()
        self.client = httpx.Client(base_url=url, timeout=120, trust_env=False, follow_redirects=False)

    def call(self, method, path, expected=200, **kwargs):
        self.server.check()
        self.report["http_requests"] += 1
        try:
            response = self.client.request(method, path, **kwargs)
        except httpx.HTTPError as error:
            self.report["unexpected_http_failures"] += 1
            raise CheckFailure(f"{type(error).__name__} during {method} {path}") from None
        self.server.check()
        if response.status_code != expected:
            self.report["unexpected_http_failures"] += 1
        require(response.status_code == expected, f"HTTP {response.status_code}, expected {expected}, {method} {path}")
        self.report["response_payload_bytes"] += len(response.content)
        return response.json() if response.content else None

    def login(self):
        session = self.call("POST", "/api/auth/login", json={"provider_id": "demo", "api_key": self.key})
        self.headers = {"Authorization": "Bearer " + session["token"]}

    def send(self, content, turn=None):
        result = self.call("POST", f"/api/conversations/{self.identifier}/messages", headers=self.headers,
                           json={"content": content})
        user, assistant = result["user_message"], result["assistant_message"]
        require(user["content"] == content, "User original content changed")
        require(user["role"] == "user" and assistant["role"] == "assistant", "Returned role pairing invalid")
        if turn:
            expected = long_payload(16000 if turn <= 32 else 64000, turn, "assistant")
            require(assistant["content"] == expected, "Synthetic long assistant reply changed")
            self.saved[turn] = (user, assistant)
        for item in (user, assistant):
            require(item["sources"] == [], "Document RAG must remain disabled")
            self.expected.append(signature(item))
            self.bytes[item["role"]] += len(item["content"].encode())
        if turn:
            counts = self.report["verified_long_payloads"]
            counts["user_16000_chars"] += 1
            counts["assistant_16000_chars" if turn <= 32 else "assistant_64000_chars"] += 1
        self.report["saved_original_utf8_bytes"] = dict(self.bytes)
        self.report["completed_turns"] = len(self.expected) // 2
        return result

    def history(self):
        position, seen, cursor, pages = len(self.expected), set(), None, 0
        while True:
            page = self.call("GET", f"/api/conversations/{self.identifier}/history", headers=self.headers,
                             params={"limit": 100, **({"before": cursor} if cursor else {})})
            position = verify_page(page["messages"], self.expected, position, seen)
            pages += 1
            require(page["has_more"] is (position > 0), "History boundary wrong")
            if not position:
                require(page["next_before"] is None, "Unexpected final history cursor")
                break
            cursor = page["next_before"]
            require(cursor == page["messages"][0]["id"], "History cursor wrong")
        return {"messages": len(seen), "pages": pages, "full_sha256_and_metadata_verified": True}

    def execute(self):
        self.start()
        self.report["storage"]["baseline"] = snapshot(self.path)
        self.login()
        conversation = self.call("POST", "/api/conversations", expected=201, headers=self.headers,
                                 json={"title": "合成长字段测试", "use_rag": False})
        self.identifier = conversation["id"]
        for turn in range(1, LONG_TURNS + 1):
            self.send(long_payload(16000, turn, "user"), turn)
        before = snapshot(self.path)
        self.call("POST", f"/api/conversations/{self.identifier}/messages", expected=422,
                  headers=self.headers, json={"content": long_payload(16001, 999, "user")})
        verify_rejected_unchanged(before, snapshot(self.path))
        self.report["user_16000_accepted_16001_rejected_without_write"] = True
        for index in range(FILLER_TURNS):
            self.send(f"移出近期上下文的独立短对话第{index}轮，今天在湖畔慢慢散步。")
        for role, turn in (("user", 1), ("assistant", 1), ("assistant", 36)):
            user, assistant = self.saved[turn]
            for location in ("middle", "tail"):
                marker = anchor_marker(role, location, turn)
                result = self.send(marker)
                item = user if role == "user" else assistant
                self.report["probes"].append(verify_reference(result["memory_refs"], item, user["id"], location, turn))
        self.report["history_before_restart"] = self.history()
        self.report["storage"]["saved"] = snapshot(self.path)
        require(self.report["storage"]["saved"]["content_utf8_bytes"] == sum(self.bytes.values()), "Saved UTF8 byte count mismatch")
        self.report["fragment_coverage"] = verify_all_fragments(self.path)
        old_headers = self.headers
        self.client.close()
        self.server.stop()
        self.start()
        self.call("GET", "/api/auth/me", expected=401, headers=old_headers)
        self.login()
        self.report["history_after_restart"] = self.history()
        self.report["restart_old_session_rejected_same_key_history_restored"] = True
        self.report["saved_original_utf8_bytes"] = self.bytes

    def cleanup(self):
        errors = []
        if self.identifier and self.client and self.headers:
            try:
                self.call("DELETE", f"/api/conversations/{self.identifier}", expected=204, headers=self.headers)
            except Exception:  # noqa: BLE001 -- retain primary failure and continue other cleanup.
                errors.append("Could not delete own temporary conversation")
        if self.client and self.headers:
            try:
                self.call("DELETE", "/api/auth/session", expected=204, headers=self.headers)
            except Exception:  # noqa: BLE001 -- cleanup must not mask a primary failure.
                errors.append("Could not revoke own synthetic session")
        if self.path.exists():
            try:
                self.report["storage"]["cleaned"] = snapshot(self.path)
                if "baseline" in self.report["storage"]:
                    verify_rejected_unchanged(self.report["storage"]["baseline"], self.report["storage"]["cleaned"])
            except Exception:  # noqa: BLE001 -- shutdown must continue even when storage measurement fails.
                errors.append("Temporary database cleanup measurement failed or counts did not return to baseline")
        if self.client:
            try:
                self.client.close()
            except Exception:  # noqa: BLE001 -- continue server stop even if the HTTP client close fails.
                errors.append("Loopback HTTP client close failed")
        if self.server:
            try:
                self.server.stop()
            except Exception:  # noqa: BLE001 -- stopping failure is recorded, never declared success.
                errors.append("Loopback server shutdown failed")
        self.report["cleanup"] = {"completed": not errors, "errors": errors,
                                  "shared_server_or_volume_touched": False, "vacuum_performed": False}
        if errors:
            self.report["status"] = "failed"


def run(report_path):
    report = {"started_at": datetime.now(UTC).isoformat(), "status": "running", "cloud_calls": 0,
              "scope": "Real loopback HTTP / temporary SQLite / synthetic provider; not a cloud or long-turn load benchmark",
              "planned_long_payloads": {"user_16000_chars": 36, "assistant_16000_chars": 32, "assistant_64000_chars": 4},
              "verified_long_payloads": {"user_16000_chars": 0, "assistant_16000_chars": 0, "assistant_64000_chars": 0},
              "http_requests": 0, "unexpected_http_failures": 0, "response_payload_bytes": 0,
              "completed_turns": 0, "probes": [], "storage": {}}
    with tempfile.TemporaryDirectory(prefix="zhiyu-long-fields-") as directory:
        root = Path(directory)
        previous = os.environ.get("ZHIYU_DATA_DIR")
        os.environ["ZHIYU_DATA_DIR"] = str(root / "bootstrap")
        experiment = None
        try:
            main = importlib.import_module("app.main")
            from app.config import Settings

            def factory(path):
                return main.create_app(Settings(data_dir=path, allow_demo=True), provider_client=SyntheticProvider())
            experiment = LongFieldRun(root / "isolated", factory, report)
            experiment.execute()
            report["status"] = "passed"
        except Exception as error:  # noqa: BLE001 -- sanitize exceptions before publishing report.
            report["status"] = "failed"
            report["failure"] = {"type": type(error).__name__, "message": str(error) if isinstance(error, CheckFailure) else "Unexpected harness error; sensitive body withheld"}
        finally:
            if experiment:
                experiment.cleanup()
            if "main" in locals():
                try:
                    asyncio.run(main.app.state.provider.aclose())
                except Exception:  # noqa: BLE001 -- shutdown failure must be safe and reportable.
                    report["status"] = "failed"
                    report["bootstrap_provider_close_failed"] = True
            if previous is None:
                os.environ.pop("ZHIYU_DATA_DIR", None)
            else:
                os.environ["ZHIYU_DATA_DIR"] = previous
    report["finished_at"] = datetime.now(UTC).isoformat()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=Path("artifacts/long-fields/report.json"))
    options = parser.parse_args()
    raise SystemExit(0 if run(options.report)["status"] == "passed" else 1)
