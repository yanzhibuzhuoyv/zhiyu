"""Sequential real-HTTP demo endurance checks. No private keys or cloud calls.

Probe turns are included in --turns, so 10,000 means exactly 20,000 saved messages.
An interrupted process leaves its latest checkpoint evidence, not a resumable job.
"""

import argparse
import hashlib
import json
import random
import time
from contextlib import nullcontext
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

import httpx

PROFILE = {"mode": "preset", "style": "gentle", "role": "partner",
           "name": "压力测试恋人", "user_name": "测试旅人"}
ANCHORS = {
    1: ("anniversary", "我们的纪念日是七月九日。这是相识那天，请记住这个纪念日。", "七月九日"),
    2: ("gift", "我的秘密礼物盒里有青瓷风铃，礼物盒是我为你留的。", "青瓷风铃"),
    3: ("nickname", "我小时候的特别昵称是小萤火，这个昵称只有亲近的人知道。", "小萤火"),
    4: ("first_date", "第一次约会地点是槐影书店二楼，第一次约会地点写在手写票根上。", "槐影书店二楼"),
    5: ("habit_original", "我以前的星河花笺订花习惯是每周三送白玫瑰。", "每周三送白玫瑰"),
    10: ("related_birthday", "前天是我生日，家里给我办了小聚会。", "前天是我生日"),
    11: ("related_chicken", "我前天晚上吃了鸡翅，是烤的。", "我前天晚上吃了鸡翅"),
    12: ("unrelated_birthday", "明天是隔壁朋友的生日，我打算去买礼物。", "隔壁朋友的生日"),
}
WEATHER = ("微雨刚停", "太阳穿过薄云", "晚风吹起落叶", "天空变成浅紫色", "晨雾散了")
PLACES = ("旧码头", "植物园小路", "河边唱片摊", "山脚茶亭", "桥下书摊", "石阶花铺")
OBJECTS = ("一只停在窗沿的小鸟", "挂着雨珠的树叶", "慢慢转动的纸风车", "新开的金色小花",
           "映着远山的水洼", "摊主正在整理的旧地图", "两个追着影子的小朋友")
FEELINGS = ("轻松而安静的心情", "想把风景分享给你的冲动", "忙碌之后的踏实", "一些无须解释的小开心")
DETAILS = (
    "我把走过的路写在手账里，顺便画了一个歪歪扭扭的小图案，画得不好却很开心。",
    "路边有人慢慢练习一首曲子，停顿很多次，我发现耐心听完整首也很舒服。",
    "买饮料的时候我选择了少一点糖，又多加了一杯温水，这些小事也想讲给你听。",
    "午后的安排没有赶得很紧，留出一点空白坐在椅子上发呆，看看人们来来往往。",
    "我尝试把手机收起来一会儿，认真看周围的颜色，感觉这一天突然有了很多细节。",
    "回去时拐进另一条街，那里有一家亮着暖色灯的小店，门口摆着修好的木椅。",
    "我留意到花盆里有新长出的嫩芽，它看起来很小，却让我愿意多停留几分钟。",
    "给自己准备了一顿简单的晚餐，厨房有热气，窗外的灯一盏一盏地亮了起来。",
    "整理桌面时翻到上个月的随手笔记，一些当时以为很重要的烦恼现在已经淡了。",
    "今天也想到将来约会时可以沿着这条路散步，但这里讲的是普通日常，不是初次见面的记录。",
    "如果你在身边，我想让你看看天空边缘那道很淡的光，也愿意认真听你讲今天。",
    "我给窗边的小植物换了方向，阳光照到叶背，纹理比平时看起来更清楚一些。",
)


class ValidationFailure(AssertionError):
    """Only deliberately sanitized diagnostics may be placed in this exception."""


def require(condition: bool, message: str):
    if not condition:
        raise ValidationFailure(message)


def validated_url(value: str) -> str:
    parsed = urlsplit(value)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise ValueError("URL must be HTTP(S), without credentials, query or fragment")
    return value.rstrip("/")


def stage_turns(total: int) -> list[int]:
    return sorted({stage for stage in (1000, total) if stage <= total})


def probe_schedule(total: int) -> dict[int, dict]:
    schedule = {}
    for stage in stage_turns(total):
        correction_turn = max(6, stage - 100)
        probes = [
            ("anniversary", 1, "七月九日", "我们的纪念日具体是哪天？"),
            ("gift", 2, "青瓷风铃", "我的秘密礼物盒里放的是什么？"),
            ("nickname", 3, "小萤火", "我小时候的特别昵称是什么？"),
            ("latest_correction", correction_turn, f"每周六送紫鸢尾，修订编号{stage}",
             "我最新更正的星河花笺订花习惯是什么？"),
            ("candidate_overflow_first_date", 4, "槐影书店二楼",
             "第一次约会地点，手写票根上写的具体地点在哪里？"),
        ]
        if stage >= 1000:
            probes.extend([
                ("long_message_middle", 250, "琉璃沙漏-250", "第250轮旅行长手账的中部校验锚点是什么？"),
                ("long_message_tail", 250, "青桥月色-250", "第250轮旅行长手账的末尾校验锚点是什么？"),
            ])
        records = [{"name": name, "anchor_turn": anchor, "fact": fact, "content": content,
                    "stage": stage} for name, anchor, fact, content in probes]
        if stage >= 1000:
            # Synthetic related raw sources remain two independent records.
            records.append({
                "name": "related_raw_birthday_and_chicken", "content": "我想吃鸡翅", "stage": stage,
                "required_sources": [{"anchor_turn": turn, "fact": ANCHORS[turn][1]} for turn in (10, 11)],
                "forbidden_anchor_turns": [12],
            })
        for turn, record in zip(range(stage - len(records) + 1, stage + 1), records, strict=True):
            schedule[turn] = record
    return schedule


def narrative(turn: int, seed: int) -> str:
    rng = random.Random(f"zhiyu:{seed}:{turn}")
    story = (f"今天想告诉你的是：{rng.choice(WEATHER)}，我经过{rng.choice(PLACES)}，"
             f"看到{rng.choice(OBJECTS)}，忽然想起{rng.choice(FEELINGS)}。")
    story += "".join(rng.sample(DETAILS, 7))
    story += f"我也想听听你今天的心情，日常约会想象只是随口分享。手账记录第{turn}页。🌿"
    while len(story) < 256:
        story += "这些简单的小事让我慢慢放松下来，也愿意把今天的心情说给你听。"
    return story


def long_narrative(turn: int, seed: int) -> str:
    parts = [narrative(turn * 100 + offset, seed) for offset in range(60)]
    story = "旅行长手账，这是一次接近消息上限的完整原文校验。" + "".join(parts)
    middle = f"长手账中部校验锚点：琉璃沙漏-{turn}，请保留这行的原文。🪷"
    story = story[:7900] + middle + story[7900:]
    return story[:15850] + f"长手账末尾校验锚点：青桥月色-{turn}，手账结束。🌙"


def turn_content(turn: int, seed: int, schedule: dict[int, dict], total: int) -> str:
    if turn in schedule:
        return schedule[turn]["content"]
    if turn in ANCHORS:
        return ANCHORS[turn][1]
    for stage in stage_turns(total):
        if turn == max(6, stage - 100):
            return f"更正我以前的星河花笺订花习惯，现在是每周六送紫鸢尾，修订编号{stage}。以这次更正为准。"
    return long_narrative(turn, seed) if turn % 250 == 0 else narrative(turn, seed)


def digest(value) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def message_signature(message: dict) -> dict:
    require(isinstance(message, dict), "Message must be an object")
    require(message.get("role") in {"user", "assistant"}, "Invalid message role")
    require(isinstance(message.get("content"), str), "Invalid message content")
    require(isinstance(message.get("sources"), list), "Invalid message sources")
    require(isinstance(message.get("id"), str) and bool(message["id"]), "Invalid message id")
    require(isinstance(message.get("created_at"), str), "Invalid message timestamp")
    return {"id": message["id"], "role": message["role"], "created_at": message["created_at"],
            "content_sha256": digest(message["content"]), "sources_sha256": digest(message["sources"])}


def verify_history_page(messages: list, expected: list, position: int, seen: set) -> int:
    require(isinstance(messages, list) and 0 < len(messages) <= 100, "Invalid history page length")
    start = position - len(messages)
    require(start >= 0, "History contains extra messages")
    for index, message in enumerate(messages, start):
        signature = message_signature(message)
        require(signature["id"] not in seen, "Duplicate message across history pages")
        require(signature == expected[index], "History message order/content/metadata mismatch")
        require(signature["role"] == ("user" if index % 2 == 0 else "assistant"), "Unpaired history roles")
        seen.add(signature["id"])
    return start


def percentile(values: list[float], percent: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * percent
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return round(ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower), 3)


def numeric_summary(values: list[float], suffix: str) -> dict:
    if not values:
        return {"count": 0}
    return {"count": len(values), f"p50_{suffix}": percentile(values, 0.5),
            f"p95_{suffix}": percentile(values, 0.95), f"p99_{suffix}": percentile(values, 0.99),
            f"max_{suffix}": round(max(values), 3), f"mean_{suffix}": round(sum(values) / len(values), 3)}


def latency_summary(values: list[float]) -> dict:
    return numeric_summary(values, "ms")


def source_present(source: dict, references: list, anchors: dict) -> dict:
    anchor = anchors.get(source["anchor_turn"], {})
    matches = [ref for ref in references if ref.get("message_id") == anchor.get("id")
               and ref.get("turn_id") == anchor.get("id") and ref.get("role") == "user"
               and ref.get("created_at") == anchor.get("created_at")
               and isinstance(ref.get("text"), str) and ref["text"] in anchor.get("content", "")
               and source["fact"] in ref.get("text", "")]
    return {"anchor_turn": source["anchor_turn"], "expected_message_id": anchor.get("id"),
            "passed": bool(matches)}


def check_probe(probe: dict, references: list, anchors: dict) -> dict:
    result = {"name": probe["name"],
            "reference_count": len(references),
            "observed_user_message_ids": [ref.get("message_id") for ref in references if ref.get("role") == "user"]}
    if "required_sources" not in probe:
        return {**result, **source_present(probe, references, anchors)}
    checks = [source_present(source, references, anchors) for source in probe["required_sources"]]
    forbidden = []
    for turn in probe.get("forbidden_anchor_turns", []):
        identifier = anchors.get(turn, {}).get("id")
        absent = bool(identifier) and not any(
            ref.get("message_id") == identifier or ref.get("turn_id") == identifier for ref in references
        )
        forbidden.append({"anchor_turn": turn, "expected_message_id": identifier, "absent": absent})
    return {**result, "required_source_checks": checks, "forbidden_source_checks": forbidden,
            "passed": bool(checks) and all(check["passed"] for check in checks)
            and all(check["absent"] for check in forbidden)}


def atomic_report(path: Path, report: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def emit_progress(item: dict):
    print(json.dumps(item, ensure_ascii=False, separators=(",", ":")), flush=True)


class Requests:
    def __init__(self, client: httpx.Client):
        self.client = client
        self.count = 0
        self.failed = 0
        self.response_bytes = 0
        self.history_bytes = 0
        self.send_latencies = []

    def call(self, method: str, path: str, expected=200, **kwargs):
        self.count += 1
        start = time.perf_counter()
        try:
            response = self.client.request(method, path, **kwargs)
        except httpx.HTTPError as error:
            self.failed += 1
            raise ValidationFailure(f"{type(error).__name__} during {method} {path}") from None
        elapsed = (time.perf_counter() - start) * 1000
        self.response_bytes += len(response.content)
        if path.endswith("/history"):
            self.history_bytes += len(response.content)
        if method == "POST" and path.endswith("/messages") and expected == 200:
            self.send_latencies.append(elapsed)
        if response.status_code != expected:
            self.failed += 1
            raise ValidationFailure(f"HTTP {response.status_code}, expected {expected}, {method} {path}")
        if not response.content:
            return None
        try:
            return response.json()
        except ValueError:
            self.failed += 1
            raise ValidationFailure(f"Invalid JSON during {method} {path}") from None

    def summary(self) -> dict:
        return {"requests": self.count, "failed_requests": self.failed,
                "response_payload_bytes": self.response_bytes,
                "history_payload_bytes": self.history_bytes,
                "message_request_latency": latency_summary(self.send_latencies)}


class StressRun:
    def __init__(self, requests, report_path, turns, seed, progress_every, pause, emit, identity):
        self.http, self.report_path, self.turns = requests, report_path, turns
        self.seed, self.progress_every, self.pause, self.emit = seed, progress_every, pause, emit
        self.identity = identity or "stress-companion-" + uuid4().hex
        self.headers, self.foreign_headers = {}, {}
        self.conversations, self.expected, self.anchors, self.ids = [], [], {}, set()
        self.positions = {}
        self.schedule, self.probes = probe_schedule(turns), []
        self.started, self.paused_seconds = time.perf_counter(), 0.0
        self.user_lengths, self.assistant_lengths = [], []
        self.content_bytes = {"user": 0, "assistant": 0}
        self.report = {"format_version": 1, "started_at": datetime.now(UTC).isoformat(),
                       "requested_turns": turns, "seed": seed, "status": "running",
                       "completed_turns": 0, "checkpoints": [], "probes": [],
                       "cloud_calls": 0, "concurrency": 1,
                       "scope": "Sequential HTTP demo persistence/retrieval; no cloud model semantics or concurrent load",
                       "cleanup": {"conversations_deleted": 0, "session_revoked": False}}

    def login(self, key: str) -> dict:
        value = self.http.call("POST", "/api/auth/login", json={"provider_id": "demo", "api_key": key})
        require(isinstance(value, dict) and isinstance(value.get("token"), str), "Invalid login response")
        return {"Authorization": "Bearer " + value["token"]}

    def setup(self):
        providers = self.http.call("GET", "/api/providers")
        require(any(item.get("id") == "demo" for item in providers), "Enable demo in isolated test backend")
        self.headers = self.login(self.identity)
        conversation = self.http.call("POST", "/api/conversations", expected=201, headers=self.headers,
                                      json={"title": "长对话压力测试", "use_rag": False, "companion_profile": PROFILE})
        require(isinstance(conversation.get("id"), str), "Invalid conversation response")
        self.identifier = conversation["id"]
        self.conversations.append(self.identifier)
        require(conversation.get("profile_locked") is True, "Preset profile must be bound")
        self.report["conversation_id"] = self.identifier
        self.emit({"event": "started", "conversation_id": self.identifier, "requested_turns": self.turns})
        self.snapshot()

    def validate_references(self, references: list):
        require(isinstance(references, list) and len(references) <= 8 and len(references) % 2 == 0,
                "Memory reference count must contain at most four complete turns")
        require(sum(len(ref.get("text", "")) for ref in references) <= 6144, "Memory character budget exceeded")
        positions = self.positions
        previous = -1
        for index in range(0, len(references), 2):
            user, assistant = references[index:index + 2]
            uid, aid = user.get("message_id"), assistant.get("message_id")
            require(uid in positions and aid in positions, "Memory references an unknown message")
            position = positions[uid]
            require(position > previous and positions[aid] == position + 1, "Memory chronology/pairing mismatch")
            require(user.get("role") == "user" and assistant.get("role") == "assistant", "Memory role mismatch")
            require(user.get("turn_id") == uid == assistant.get("turn_id"), "Memory turn id mismatch")
            for ref, offset in ((user, 0), (assistant, 1)):
                require(ref.get("created_at") == self.expected[position + offset]["created_at"], "Memory provenance mismatch")
                require(isinstance(ref.get("text"), str) and len(ref["text"]) <= 768, "Memory excerpt limit exceeded")
            previous = position

    def send_turn(self, turn: int):
        content = turn_content(turn, self.seed, self.schedule, self.turns)
        response = self.http.call("POST", f"/api/conversations/{self.identifier}/messages",
                                  headers=self.headers, json={"content": content})
        references = response.get("memory_refs")
        self.validate_references(references)
        user, assistant = response.get("user_message"), response.get("assistant_message")
        require(user and assistant, "Missing complete returned turn")
        require(user.get("content") == content, "Server changed user original content")
        require(user.get("role") == "user" and assistant.get("role") == "assistant", "Unpaired returned roles")
        require(assistant.get("sources") == [], "Document RAG must remain disabled")
        for message in (user, assistant):
            signature = message_signature(message)
            require(signature["id"] not in self.ids, "Reused message id")
            self.ids.add(signature["id"])
            self.positions[signature["id"]] = len(self.expected)
            self.expected.append(signature)
            self.content_bytes[signature["role"]] += len(message["content"].encode())
        self.user_lengths.append(len(user["content"]))
        self.assistant_lengths.append(len(assistant["content"]))
        if turn in ANCHORS or turn == 250 or turn in {max(6, stage - 100) for stage in stage_turns(self.turns)}:
            self.anchors[turn] = user
        if turn in self.schedule:
            probe = check_probe(self.schedule[turn], references, self.anchors)
            self.probes.append({**probe, "request_turn": turn, "stage": self.schedule[turn]["stage"]})
        self.report["completed_turns"] = turn

    def verify_history(self) -> dict:
        position, seen, cursor, page_count = len(self.expected), set(), None, 0
        before_bytes = self.http.history_bytes
        started = time.perf_counter()
        while True:
            parameters = {"limit": 100, **({"before": cursor} if cursor else {})}
            page = self.http.call("GET", f"/api/conversations/{self.identifier}/history",
                                  headers=self.headers, params=parameters)
            position = verify_history_page(page.get("messages"), self.expected, position, seen)
            page_count += 1
            require(page.get("has_more") is (position > 0), "Incorrect history has_more")
            if position == 0:
                require(page.get("next_before") is None, "Unexpected final history cursor")
                break
            next_cursor = page.get("next_before")
            require(next_cursor == page["messages"][0]["id"] and next_cursor != cursor, "Incorrect history cursor")
            cursor = next_cursor
        require(len(seen) == len(self.expected), "Missing saved messages in history")
        return {"messages": len(seen), "complete_turns": len(seen) // 2, "pages": page_count,
                "all_ids_unique": True, "chronology_pairing_content_metadata_hashes_verified": True,
                "payload_bytes": self.http.history_bytes - before_bytes,
                "elapsed_seconds": round(time.perf_counter() - started, 3)}

    def relogin_and_isolation(self) -> dict:
        self.http.call("DELETE", "/api/auth/session", expected=204, headers=self.headers)
        self.headers = {}
        self.headers = self.login(self.identity)
        conversations = self.http.call("GET", "/api/conversations", headers=self.headers)
        restored = next((item for item in conversations if item.get("id") == self.identifier), None)
        require(restored and restored.get("companion_profile") == {**PROFILE, "instructions": ""},
                "Same-key relogin changed preset profile")
        self.foreign_headers = self.login("stress-isolation-" + uuid4().hex)
        path = f"/api/conversations/{self.identifier}/history"
        self.http.call("GET", path, expected=404, headers=self.foreign_headers)
        self.http.call("POST", f"/api/conversations/{self.identifier}/messages", expected=404,
                       headers=self.foreign_headers, json={"content": "身份隔离测试"})
        self.http.call("DELETE", "/api/auth/session", expected=204, headers=self.foreign_headers)
        self.foreign_headers = {}
        sibling = self.http.call("POST", "/api/conversations", expected=201,
                                 headers=self.headers, json={"title": "游标隔离测试", "use_rag": False})
        self.conversations.append(sibling["id"])
        self.http.call("GET", f"/api/conversations/{sibling['id']}/history", expected=404,
                       headers=self.headers, params={"before": self.expected[0]["id"]})
        self.http.call("GET", path, expected=422, headers=self.headers, params={"limit": 101})
        return {"same_key_preset_restored": True, "foreign_key_read_write_rejected": True,
                "same_owner_foreign_conversation_cursor_rejected": True, "page_limit_enforced": True}

    def checkpoint(self, turn: int):
        stage_probes = [probe for probe in self.probes if probe["stage"] == turn]
        expected_probes = 8 if turn >= 1000 else 5
        require(len(stage_probes) == expected_probes, "Checkpoint memory probe count mismatch")
        before = self.report["checkpoints"][-1]["completed_turns"] if self.report["checkpoints"] else 0
        latencies = self.http.send_latencies[before:turn]
        checkpoint = {"completed_turns": turn, "expected_messages": 2 * turn,
                      "probe_count": len(stage_probes), "all_probes_passed": all(p["passed"] for p in stage_probes),
                      "message_request_latency": latency_summary(latencies),
                      "send_seconds": round(sum(latencies) / 1000, 3),
                      "send_turns_per_second": round(len(latencies) * 1000 / sum(latencies), 3)}
        checkpoint["relogin_isolation"] = self.relogin_and_isolation()
        checkpoint["history"] = self.verify_history()
        checkpoint["elapsed_seconds_excluding_checkpoint_pauses"] = round(
            time.perf_counter() - self.started - self.paused_seconds, 3)
        checkpoint["status"] = "passed" if checkpoint["all_probes_passed"] else "failed"
        self.report["checkpoints"].append(checkpoint)
        self.snapshot()
        self.emit({"event": "checkpoint", "conversation_id": self.identifier,
                   "completed_turns": turn, "messages": 2 * turn, "status": checkpoint["status"],
                   "pause_seconds": self.pause, "report": str(self.report_path)})
        if self.pause:
            started = time.perf_counter()
            time.sleep(self.pause)
            self.paused_seconds += time.perf_counter() - started
        require(checkpoint["all_probes_passed"], f"Memory probe failed at {turn} turns; see probe evidence")

    def snapshot(self):
        self.report["http"] = self.http.summary()
        self.report["probes"] = list(self.probes)
        self.report["saved_content_utf8_bytes"] = dict(self.content_bytes)
        self.report["user_content_length_chars"] = numeric_summary(self.user_lengths, "chars")
        self.report["assistant_content_length_chars"] = numeric_summary(self.assistant_lengths, "chars")
        self.report["long_user_message_count"] = sum(length >= 15000 for length in self.user_lengths)
        self.report["elapsed_seconds_excluding_checkpoint_pauses"] = round(
            time.perf_counter() - self.started - self.paused_seconds, 3)
        self.report["checkpoint_pause_seconds"] = round(self.paused_seconds, 3)
        atomic_report(self.report_path, self.report)

    def execute(self):
        self.setup()
        for turn in range(1, self.turns + 1):
            self.send_turn(turn)
            if turn % self.progress_every == 0:
                self.snapshot()
                self.emit({"event": "progress", "conversation_id": self.identifier,
                           "completed_turns": turn, "requested_turns": self.turns,
                           "last_request_ms": round(self.http.send_latencies[-1], 3),
                           "elapsed_seconds": self.report["elapsed_seconds_excluding_checkpoint_pauses"]})
            if turn in stage_turns(self.turns):
                self.checkpoint(turn)
        self.report["status"] = "passed"

    def cleanup(self):
        errors = []
        if self.conversations and not self.headers:
            try:
                self.headers = self.login(self.identity)
            except Exception:  # noqa: BLE001 -- cleanup records failure and continues every remaining item.
                errors.append("Could not recover disposable identity for cleanup")
        for identifier in self.conversations:
            try:
                self.http.call("DELETE", f"/api/conversations/{identifier}", expected=204, headers=self.headers)
                self.report["cleanup"]["conversations_deleted"] += 1
            except Exception:  # noqa: BLE001 -- cleanup records failure and continues every remaining item.
                errors.append(f"Could not delete disposable conversation {identifier}")
        for name in ("headers", "foreign_headers"):
            headers = getattr(self, name)
            if not headers:
                continue
            try:
                self.http.call("DELETE", "/api/auth/session", expected=204, headers=headers)
                if name == "headers":
                    self.report["cleanup"]["session_revoked"] = True
            except Exception:  # noqa: BLE001 -- cleanup records failure and continues every remaining item.
                errors.append("Could not revoke disposable session")
        self.report["cleanup"]["completed"] = not errors
        self.report["cleanup"]["errors"] = errors
        if errors:
            self.report["status"] = "failed"


def run(base_url: str, report_path: Path, *, turns=10000, seed=4705, progress_every=100,
        checkpoint_pause_seconds=0, client=None, emit=emit_progress, identity=None) -> dict:
    base_url = validated_url(base_url)
    if turns < 40 or progress_every < 1 or not 0 <= checkpoint_pause_seconds <= 60:
        raise ValueError("Require turns >= 40, progress-every >= 1, checkpoint-pause-seconds in [0,60]")
    context = nullcontext(client) if client else httpx.Client(
        base_url=base_url, timeout=120, trust_env=False, follow_redirects=False)
    with context as connection:
        experiment = StressRun(Requests(connection), report_path, turns, seed, progress_every,
                               checkpoint_pause_seconds, emit, identity)
        experiment.report["url"] = base_url
        try:
            experiment.execute()
        except Exception as error:  # noqa: BLE001 -- final report sanitizes unexpected errors before cleanup.
            experiment.report["status"] = "failed"
            message = str(error) if isinstance(error, ValidationFailure) else "Unexpected harness error; inspect code without publishing HTTP bodies"
            experiment.report["failure"] = {"type": type(error).__name__, "message": message}
        finally:
            experiment.cleanup()
            experiment.report["finished_at"] = datetime.now(UTC).isoformat()
            experiment.snapshot()
        emit({"event": "finished", "status": experiment.report["status"],
              "completed_turns": experiment.report["completed_turns"], "report": str(report_path)})
        return experiment.report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8791")
    parser.add_argument("--turns", type=int, default=10000)
    parser.add_argument("--seed", type=int, default=4705)
    parser.add_argument("--report", type=Path, default=Path("artifacts/companion-stress-10000.json"))
    parser.add_argument("--progress-every", type=int, default=100)
    parser.add_argument("--checkpoint-pause-seconds", type=float, default=0)
    options = parser.parse_args()
    if options.turns < 1000:
        parser.error("--turns must be at least 1000 for this endurance test")
    result = run(options.url, options.report, turns=options.turns, seed=options.seed,
                 progress_every=options.progress_every,
                 checkpoint_pause_seconds=options.checkpoint_pause_seconds)
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
