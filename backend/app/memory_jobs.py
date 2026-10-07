"""Durable source pointers for bounded, authenticated semantic work."""

import hashlib
from datetime import UTC, datetime
from uuid import uuid4

from .body_codec import decode_body
from .memory_restore import restore_protected
from .memory_semantic import SEMANTIC_VERSION
from .memory_transaction import transaction

MAX_ATTEMPTS = 2  # One interrupted attempt can resume after fresh authentication.
_FINAL = {"ok", "partial", "failed", "unavailable", "skipped_large"}
_ROLES = ("user", "assistant")


class MemoryJobs:
    def __init__(self, chat):
        self.chat = chat

    @staticmethod
    def initialize(db):
        db.execute("""CREATE TABLE IF NOT EXISTS memory_jobs (
            user_sequence INTEGER PRIMARY KEY REFERENCES messages(sequence) ON DELETE CASCADE,
            assistant_sequence INTEGER UNIQUE NOT NULL REFERENCES messages(sequence) ON DELETE CASCADE,
            conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            owner TEXT NOT NULL, provider_id TEXT NOT NULL, model TEXT NOT NULL,
            user_id TEXT NOT NULL, assistant_id TEXT NOT NULL,
            user_created_at TEXT NOT NULL, assistant_created_at TEXT NOT NULL,
            user_sha256 TEXT NOT NULL, assistant_sha256 TEXT NOT NULL,
            version TEXT NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
            lease TEXT, updated_at TEXT NOT NULL,
            CHECK(assistant_sequence=user_sequence+1), CHECK(attempts BETWEEN 0 AND 2))""")
        db.execute("CREATE INDEX IF NOT EXISTS idx_memory_jobs_owner ON memory_jobs(owner,user_sequence,status)")

    @staticmethod
    def enqueue(db, owner, conversation, messages, sequences, identity):
        if (not isinstance(identity, dict) or set(identity) != {"provider_id", "model"}
                or any(not isinstance(identity[key], str) or not 1 <= len(identity[key]) <= 200
                       for key in identity)):
            raise ValueError("后台记忆模型配置无效")
        user, assistant = messages
        values = (sequences[0], sequences[1], conversation, owner,
                  identity["provider_id"], identity["model"], user["id"], assistant["id"],
                  user["created_at"], assistant["created_at"],
                  hashlib.sha256(user["content"].encode()).hexdigest(),
                  hashlib.sha256(assistant["content"].encode()).hexdigest(),
                  SEMANTIC_VERSION, "pending", datetime.now(UTC).isoformat())
        db.execute("""INSERT INTO memory_jobs(user_sequence,assistant_sequence,conversation_id,
            owner,provider_id,model,user_id,assistant_id,user_created_at,assistant_created_at,
            user_sha256,assistant_sha256,version,status,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", values)

    @staticmethod
    def _recognition(db, job, status):
        db.execute("UPDATE memory_recognition SET status=? WHERE sequence IN (?,?)",
                   (status, job["user_sequence"], job["assistant_sequence"]))

    def recover(self, owner):
        """Only a valid owner authentication may recover interrupted jobs; no startup scan."""
        with transaction(self.chat, write=True) as db:
            # A bounded batch is enough for <=2 formerly running workers.
            rows = db.execute("""SELECT * FROM memory_jobs WHERE owner=? AND status='running'
                ORDER BY user_sequence LIMIT 2""", (owner,)).fetchall()
            for row in rows:
                status = "waiting_auth" if row["attempts"] < MAX_ATTEMPTS else "failed"
                db.execute("UPDATE memory_jobs SET status=?,lease=NULL,updated_at=? WHERE user_sequence=?",
                           (status, datetime.now(UTC).isoformat(), row["user_sequence"]))
                self._recognition(db, row, status)

    def claim(self, owner, provider_id):
        with transaction(self.chat, write=True) as db:
            self._retire_exhausted(db, owner)
            row = db.execute("""SELECT j.* FROM memory_jobs j JOIN conversations c
                ON c.id=j.conversation_id AND c.owner=j.owner
                WHERE j.owner=? AND j.status IN ('pending','waiting_auth','running')
                ORDER BY j.user_sequence LIMIT 1""", (owner,)).fetchone()
            if row is None or row["status"] == "running" or row["provider_id"] != provider_id:
                return None
            job = dict(row)
            lease = uuid4().hex
            db.execute("""UPDATE memory_jobs SET status='running',attempts=attempts+1,
                lease=?,updated_at=? WHERE user_sequence=?""",
                       (lease, datetime.now(UTC).isoformat(), job["user_sequence"]))
            self._recognition(db, job, "running")
            return {**job, "status": "running", "lease": lease, "attempts": job["attempts"] + 1}

    def _retire_exhausted(self, db, owner):
        # At most one owner lease runs at a time; bound malformed/legacy backlog too.
        rows = db.execute("""SELECT * FROM memory_jobs WHERE owner=?
            AND status IN ('pending','waiting_auth') AND attempts>=?
            ORDER BY user_sequence LIMIT 128""", (owner, MAX_ATTEMPTS)).fetchall()
        for row in rows:
            self._end(db, row, "failed")

    @staticmethod
    def _current(db, owner, job):
        return db.execute("""SELECT j.* FROM memory_jobs j JOIN conversations c
            ON c.id=j.conversation_id AND c.owner=j.owner
            WHERE j.owner=? AND j.user_sequence=? AND j.status='running' AND j.lease=?""",
                          (owner, job["user_sequence"], job["lease"])).fetchone()

    @staticmethod
    def _sources(db, job):
        sources = {}
        for role in _ROLES:
            row = db.execute("""SELECT id,role,created_at,content FROM messages
                WHERE sequence=? AND conversation_id=?""",
                             (job[f"{role}_sequence"], job["conversation_id"])).fetchone()
            if row is None or any(row[key] != job[f"{role}_{key}"] for key in ("id", "created_at")) or row["role"] != role:
                return None
            original = decode_body(row["content"])
            if hashlib.sha256(original.encode()).hexdigest() != job[f"{role}_sha256"]:
                return None
            sources[role] = {"content": original, "created_at": row["created_at"]}
        return sources if job["version"] == SEMANTIC_VERSION else None

    def sources(self, owner, job):
        with transaction(self.chat) as db:
            current = self._current(db, owner, job)
            return self._sources(db, current) if current else None

    def finish(self, owner, job, recognition):
        if recognition["status"] not in _FINAL or recognition["version"] != SEMANTIC_VERSION:
            raise ValueError("后台识别结果版本无效")
        with transaction(self.chat, write=True) as db:
            current = self._current(db, owner, job)
            if current is None:
                return False
            if self._sources(db, current) is None:
                self._end(db, current, "failed")
                return False
            for role in _ROLES:
                sequence = current[f"{role}_sequence"]
                if recognition["roles"][role] == "ok":
                    # Successful empty arrays are recorded too; reruns cannot relabel them.
                    self.chat.annotations.record_semantic(db, sequence, recognition["claims"][role])
                db.execute("UPDATE memory_recognition SET status=?,version=? WHERE sequence=?",
                           (recognition["roles"][role], recognition["version"], sequence))
            restore_protected(self.chat, db, current["conversation_id"])
            db.execute("UPDATE memory_jobs SET status=?,lease=NULL,updated_at=? WHERE user_sequence=?",
                       (recognition["status"], datetime.now(UTC).isoformat(), current["user_sequence"]))
            return True

    def release(self, owner, job, status="waiting_auth"):
        if status not in {"waiting_auth", "failed"}:
            raise ValueError("后台识别状态无效")
        with transaction(self.chat, write=True) as db:
            current = self._current(db, owner, job)
            if current is None:
                return False
            self._end(db, current, status)
            return True

    def _end(self, db, current, status):
        db.execute("UPDATE memory_jobs SET status=?,lease=NULL,updated_at=? WHERE user_sequence=?",
                   (status, datetime.now(UTC).isoformat(), current["user_sequence"]))
        self._recognition(db, current, status)
