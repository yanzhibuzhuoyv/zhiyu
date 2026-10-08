"""Durable virtual daily schedules; Beijing's date boundary is evaluated per request."""

import hashlib
import json
from datetime import UTC, datetime
from uuid import uuid4
from zoneinfo import ZoneInfo

from .media_storage import connection

BEIJING = ZoneInfo("Asia/Shanghai")
PLANS = (
    (("00:00", "休息", ["night"]), ("07:30", "早餐", ["food", "morning"]),
     ("12:00", "午间散步", ["landscape", "cat", "afternoon"]),
     ("18:30", "晚餐", ["food", "evening"]), ("21:00", "安静阅读", ["object", "night"])),
    (("00:00", "休息", ["night"]), ("08:00", "慢慢吃早餐", ["food", "morning"]),
     ("12:30", "看看路边的小猫", ["cat", "landscape", "afternoon"]),
     ("18:00", "晚霞散步", ["landscape", "evening"]), ("21:30", "整理一天", ["object", "night"])),
)


class DailyScenes:
    def __init__(self, root):
        root.mkdir(parents=True, exist_ok=True)
        self.path = root / "daily.sqlite3"
        with connection(self.path) as db:
            db.execute("""CREATE TABLE IF NOT EXISTS days (owner TEXT NOT NULL,
                conversation_id TEXT NOT NULL,day_id TEXT NOT NULL,id TEXT NOT NULL,
                plan TEXT NOT NULL,created_at TEXT NOT NULL,
                PRIMARY KEY(owner,conversation_id,day_id))""")

    def get(self, owner, conversation_id, visual_version=0, now=None):
        now = now or datetime.now(UTC)
        if now.tzinfo is None:
            raise ValueError("日轨迹时间必须带时区")
        local = now.astimezone(BEIJING)
        day_id = local.date().isoformat()
        variation = int(hashlib.sha256(f"{owner}:{conversation_id}:{day_id}".encode()).hexdigest(), 16) % len(PLANS)
        slots = [{"time": time, "activity": activity, "tags": tags} for time, activity, tags in PLANS[variation]]
        with connection(self.path, write=True) as db:
            db.execute("INSERT OR IGNORE INTO days VALUES(?,?,?,?,?,?)", (
                owner, conversation_id, day_id, uuid4().hex,
                json.dumps(slots, ensure_ascii=False), now.isoformat(),
            ))
            row = db.execute("SELECT * FROM days WHERE owner=? AND conversation_id=? AND day_id=?",
                             (owner, conversation_id, day_id)).fetchone()
        slots = json.loads(row["plan"])
        current = slots[0]
        for slot in slots:
            if slot["time"] <= local.strftime("%H:%M"):
                current = slot
        return {"day_id": day_id, "timezone": "Asia/Shanghai", "fictional": True,
                "scene_id": row["id"], "visual_version": visual_version, "slots": slots,
                "current_slot": current, "refreshed_at": row["created_at"]}
