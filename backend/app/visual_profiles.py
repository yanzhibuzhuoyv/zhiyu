"""OC appearance and owner-scoped two-generation quota, outside personality/memory."""

import hashlib
import json
from datetime import UTC, datetime
from uuid import uuid4

from .media_models import VisualUpdate
from .media_storage import connection


class VisualConflict(ValueError):
    def __init__(self, message, *, code="visual_conflict"):
        super().__init__(message)
        self.code = code


class VisualProfiles:
    def __init__(self, root):
        root.mkdir(parents=True, exist_ok=True)
        self.path = root / "visual.sqlite3"
        with connection(self.path) as db:
            db.executescript("""CREATE TABLE IF NOT EXISTS profiles (
                owner TEXT NOT NULL, conversation_id TEXT NOT NULL, id TEXT NOT NULL,
                mode TEXT NOT NULL, description TEXT NOT NULL, gender TEXT NOT NULL,
                hair TEXT NOT NULL, version INTEGER NOT NULL, reference_id TEXT,
                reference_sha256 TEXT, updated_at TEXT NOT NULL,
                PRIMARY KEY(owner,conversation_id));
                CREATE TABLE IF NOT EXISTS generations (
                id TEXT PRIMARY KEY,owner TEXT NOT NULL,conversation_id TEXT NOT NULL,
                request_id TEXT NOT NULL,payload_hash TEXT NOT NULL,description TEXT NOT NULL,
                visual_id TEXT NOT NULL,expected_version INTEGER NOT NULL,status TEXT NOT NULL,
                result TEXT,created_at TEXT NOT NULL,UNIQUE(owner,request_id));
                CREATE INDEX IF NOT EXISTS idx_generation_quota ON generations(owner,status);""")

    @staticmethod
    def _profile(row, conversation_id):
        if row is None:
            return {"visual_id": None, "conversation_id": conversation_id, "mode": "none",
                    "description": "", "gender": "unspecified", "hair": "unspecified",
                    "visual_version": 0, "reference_id": None, "reference_sha256": None,
                    "reference_url": None, "updated_at": None}
        return {"visual_id": row["id"], "conversation_id": conversation_id, "mode": row["mode"],
                "description": row["description"], "gender": row["gender"], "hair": row["hair"],
                "visual_version": row["version"], "reference_id": row["reference_id"],
                "reference_sha256": row["reference_sha256"], "updated_at": row["updated_at"],
                "reference_url": f"/api/media/assets/{row['reference_id']}" if row["reference_id"] else None}

    @staticmethod
    def _row(db, owner, conversation_id):
        return db.execute("SELECT * FROM profiles WHERE owner=? AND conversation_id=?",
                          (owner, conversation_id)).fetchone()

    @staticmethod
    def _quota(db, owner, configured):
        counts = {row["status"]: row["total"] for row in db.execute(
            "SELECT status,count(*) total FROM generations WHERE owner=? GROUP BY status", (owner,))}
        used, pending = counts.get("succeeded", 0), counts.get("pending", 0)
        return {"configured": configured, "limit": 2, "used": used, "pending": pending,
                "remaining": max(0, 2 - used - pending)}

    def get(self, owner, conversation_id, *, configured=False):
        with connection(self.path) as db:
            return {**self._profile(self._row(db, owner, conversation_id), conversation_id),
                    "generation": self._quota(db, owner, configured)}

    @staticmethod
    def _no_pending(db, owner, conversation_id):
        if db.execute("SELECT 1 FROM generations WHERE owner=? AND conversation_id=? AND status='pending'",
                      (owner, conversation_id)).fetchone():
            raise VisualConflict("外观生成仍在进行，完成前不能修改 OC 档案")

    @staticmethod
    def _write(db, owner, conversation_id, profile):
        db.execute("""INSERT INTO profiles VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(owner,conversation_id) DO UPDATE SET id=excluded.id,mode=excluded.mode,
            description=excluded.description,gender=excluded.gender,hair=excluded.hair,
            version=excluded.version,reference_id=excluded.reference_id,
            reference_sha256=excluded.reference_sha256,updated_at=excluded.updated_at""", (
                owner, conversation_id, profile["visual_id"], profile["mode"], profile["description"],
                profile["gender"], profile["hair"], profile["visual_version"], profile["reference_id"],
                profile["reference_sha256"], profile["updated_at"],
            ))

    def update(self, owner, conversation_id, values: VisualUpdate, *, configured=False):
        with connection(self.path, write=True) as db:
            self._no_pending(db, owner, conversation_id)
            prior = self._profile(self._row(db, owner, conversation_id), conversation_id)
            fields = values.model_dump()
            if any(prior[key] != value for key, value in fields.items()):
                identity_changed = not prior["visual_id"] or any(
                    prior[key] != fields[key] for key in ("mode", "gender", "hair")
                )
                keep = not identity_changed and values.mode != "none"
                changed = {**prior, **fields, "visual_id": prior["visual_id"] or uuid4().hex,
                           "visual_version": prior["visual_version"] + int(identity_changed),
                           "reference_id": prior["reference_id"] if keep else None,
                           "reference_sha256": prior["reference_sha256"] if keep else None,
                           "updated_at": datetime.now(UTC).isoformat()}
                self._write(db, owner, conversation_id, changed)
        return self.get(owner, conversation_id, configured=configured)

    def adopt_template(self, owner, conversation_id, reference, *, expected_version, description):
        with connection(self.path, write=True) as db:
            self._no_pending(db, owner, conversation_id)
            prior = self._profile(self._row(db, owner, conversation_id), conversation_id)
            if prior["visual_version"] != expected_version:
                raise VisualConflict("OC 档案已更新，请重新选择样例")
            self._write(db, owner, conversation_id, {
                **prior, "visual_id": reference["visual_id"], "mode": "import",
                "description": description, "gender": reference["gender"], "hair": reference["hair"],
                "visual_version": expected_version + 1, "reference_id": reference["id"],
                "reference_sha256": reference["sha256"], "updated_at": datetime.now(UTC).isoformat(),
            })
        return self.get(owner, conversation_id)

    def attach(self, owner, conversation_id, asset, *, expected_version, mode="import", description=None):
        with connection(self.path, write=True) as db:
            self._no_pending(db, owner, conversation_id)
            prior = self._profile(self._row(db, owner, conversation_id), conversation_id)
            if prior["visual_version"] != expected_version:
                raise VisualConflict("OC 档案已更新，请重新导入参考图片")
            self._write(db, owner, conversation_id, {
                **prior, "visual_id": asset["visual_id"], "mode": mode,
                "description": prior["description"] if description is None else description,
                "visual_version": expected_version + 1, "reference_id": asset["id"],
                "reference_sha256": asset["sha256"], "updated_at": datetime.now(UTC).isoformat(),
            })
        return self.get(owner, conversation_id)

    def reserve(self, owner, conversation_id, request_id, description):
        digest = hashlib.sha256(json.dumps([conversation_id, description], ensure_ascii=False).encode()).hexdigest()
        with connection(self.path, write=True) as db:
            existing = db.execute("SELECT * FROM generations WHERE owner=? AND request_id=?", (owner, request_id)).fetchone()
            if existing:
                if existing["payload_hash"] != digest:
                    raise VisualConflict("生成请求编号已用于另一项描述")
                if existing["status"] == "failed":
                    raise VisualConflict("该生成请求已失败，请使用新的请求编号重试", code="generation_failed")
                return {**dict(existing), "replayed": True}
            self._no_pending(db, owner, conversation_id)
            if self._quota(db, owner, True)["remaining"] == 0:
                raise VisualConflict("两次外观生成机会已用完或正在生成")
            profile = self._profile(self._row(db, owner, conversation_id), conversation_id)
            operation = {"id": uuid4().hex, "owner": owner, "conversation_id": conversation_id,
                         "request_id": request_id, "payload_hash": digest, "description": description,
                         "visual_id": profile["visual_id"] or uuid4().hex,
                         "expected_version": profile["visual_version"], "status": "pending",
                         "result": None, "created_at": datetime.now(UTC).isoformat()}
            db.execute("INSERT INTO generations VALUES(?,?,?,?,?,?,?,?,?,?,?)", tuple(operation.values()))
            return {**operation, "replayed": False}

    def fail(self, operation_id):
        with connection(self.path, write=True) as db:
            db.execute("UPDATE generations SET status='failed' WHERE id=? AND status='pending'", (operation_id,))

    def finish(self, operation_id, asset):
        with connection(self.path, write=True) as db:
            row = db.execute("SELECT * FROM generations WHERE id=? AND status='pending'", (operation_id,)).fetchone()
            if row is None:
                raise VisualConflict("外观生成状态已发生变化")
            prior = self._profile(self._row(db, row["owner"], row["conversation_id"]), row["conversation_id"])
            if prior["visual_version"] != row["expected_version"]:
                raise VisualConflict("生成期间 OC 档案已更新")
            result = {**prior, "visual_id": row["visual_id"], "mode": "generate",
                      "description": row["description"], "visual_version": row["expected_version"] + 1,
                      "reference_id": asset["id"], "reference_sha256": asset["sha256"],
                      "reference_url": f"/api/media/assets/{asset['id']}", "updated_at": datetime.now(UTC).isoformat()}
            self._write(db, row["owner"], row["conversation_id"], result)
            db.execute("UPDATE generations SET status='succeeded',result=? WHERE id=?",
                       (json.dumps(result, ensure_ascii=False), operation_id))
            return {**result, "generation": self._quota(db, row["owner"], True)}

    def existing(self, owner, conversation_id, request_id, description):
        digest = hashlib.sha256(json.dumps([conversation_id, description], ensure_ascii=False).encode()).hexdigest()
        with connection(self.path) as db:
            row = db.execute("SELECT * FROM generations WHERE owner=? AND request_id=?", (owner, request_id)).fetchone()
        if row is None:
            return None
        if row["payload_hash"] != digest:
            raise VisualConflict("生成请求编号已用于另一项描述")
        if row["status"] == "failed":
            raise VisualConflict("该生成请求已失败，请使用新的请求编号重试", code="generation_failed")
        return {**dict(row), "replayed": True}

    def replay(self, operation, *, configured=True):
        if operation["status"] == "pending":
            raise VisualConflict("相同的外观请求正在生成，请稍后查看", code="generation_pending")
        with connection(self.path) as db:
            return {**json.loads(operation["result"]), "generation": self._quota(db, operation["owner"], configured)}
