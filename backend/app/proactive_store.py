"""Contact preferences and idempotency references, without duplicate chat text."""

import sqlite3
from contextlib import contextmanager
from datetime import datetime

from .proactive_policy import decision
from .source_codec import ChatStorageError


class ProactiveStore:
    def __init__(self, directory):
        self.path = directory / "proactive.sqlite3"
        with self.connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS preferences (
                    owner TEXT, conversation TEXT, enabled INTEGER NOT NULL DEFAULT 0,
                    quiet_start INTEGER NOT NULL DEFAULT 22, quiet_end INTEGER NOT NULL DEFAULT 8,
                    PRIMARY KEY(owner,conversation));
                CREATE TABLE IF NOT EXISTS events (
                    owner TEXT, conversation TEXT, request_id TEXT, day_id TEXT NOT NULL,
                    created_at TEXT NOT NULL, status TEXT NOT NULL, message_id TEXT,
                    PRIMARY KEY(owner,conversation,request_id));
                CREATE INDEX IF NOT EXISTS events_day ON events(owner,conversation,day_id);
            """)

    @contextmanager
    def connection(self):
        try:
            db = sqlite3.connect(self.path, timeout=15)
            db.row_factory = sqlite3.Row
            try:
                with db:
                    yield db
            finally:
                db.close()
        except sqlite3.Error as exc:
            raise ChatStorageError("主动联系记录无法读写") from exc

    def preferences(self, owner, conversation):
        with self.connection() as db:
            row = db.execute("SELECT enabled,quiet_start,quiet_end FROM preferences WHERE owner=? AND conversation=?",
                             (owner, conversation)).fetchone()
        values = dict(row) if row else {"enabled": False, "quiet_start": 22, "quiet_end": 8}
        return {**values, "enabled": bool(values["enabled"]), "time_zone": "Asia/Shanghai"}

    def update(self, owner, conversation, values):
        decision(datetime.now().astimezone(), **values)
        with self.connection() as db:
            db.execute("""INSERT INTO preferences VALUES(?,?,?,?,?) ON CONFLICT(owner,conversation)
                DO UPDATE SET enabled=excluded.enabled,quiet_start=excluded.quiet_start,quiet_end=excluded.quiet_end""",
                       (owner, conversation, int(values["enabled"]), values["quiet_start"], values["quiet_end"]))
        return self.preferences(owner, conversation)

    def event(self, owner, conversation, request_id):
        with self.connection() as db:
            row = db.execute("SELECT * FROM events WHERE owner=? AND conversation=? AND request_id=?",
                             (owner, conversation, request_id)).fetchone()
        return dict(row) if row else None

    def stats(self, owner, conversation, day):
        with self.connection() as db:
            row = db.execute("""SELECT coalesce(sum(day_id=?),0) today_count,max(created_at) last_contact,
                coalesce(max(status='pending'),0) pending FROM events WHERE owner=? AND conversation=? AND status!='failed'""",
                             (day, owner, conversation)).fetchone()
        return {"today_count": row["today_count"],
                "last_contact": datetime.fromisoformat(row["last_contact"]) if row["last_contact"] else None,
                "pending": bool(row["pending"])}

    def reserve(self, owner, conversation, request_id, day, now):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            # An interrupted unknown attempt is not retried. A later day may resume contact.
            db.execute("UPDATE events SET status='unknown' WHERE owner=? AND conversation=? AND status='pending' AND day_id<?",
                       (owner, conversation, day))
            if db.execute("SELECT 1 FROM events WHERE owner=? AND conversation=? AND (request_id=? OR status='pending')",
                          (owner, conversation, request_id)).fetchone():
                return False
            db.execute("INSERT INTO events VALUES(?,?,?,?,?,'pending',NULL)",
                       (owner, conversation, request_id, day, now.isoformat()))
        return True

    def finish(self, owner, conversation, request_id, message_id):
        with self.connection() as db:
            db.execute("UPDATE events SET status='sent',message_id=? WHERE owner=? AND conversation=? AND request_id=?",
                       (message_id, owner, conversation, request_id))

    def failed(self, owner, conversation, request_id, *, unknown=False):
        with self.connection() as db:
            db.execute("UPDATE events SET status=? WHERE owner=? AND conversation=? AND request_id=?",
                       ("unknown" if unknown else "failed", owner, conversation, request_id))
