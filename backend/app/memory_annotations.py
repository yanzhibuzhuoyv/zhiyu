"""Versioned original-source annotations. Callers own scope checks and transactions.

The store verifies canonical message author/time/span; public routes must first
authorize owner/conversation. No method commits, rewrites or removes messages.
"""

import hashlib
import json
import sqlite3
from datetime import UTC, datetime
from functools import wraps

from .body_codec import decode_body
from .memory_annotation_rules import classify_source
from .memory_corrections import details as correction_details
from .memory_lifecycle import POLICY_VERSION, Observation, Policy
from .memory_semantic import MAX_CLAIMS, SEMANTIC_VERSION, SemanticResponseError, reground_claim
from .source_codec import ChatStorageError

TOPIC_ANNOTATION_LIMIT = 1000
METADATA_LIMIT = 1024
_TIERS = {"mid", "long", "permanent", "auto"}


class MemoryAnnotationsError(ChatStorageError):
    """Source/annotation integrity or SQLite failure, without raw text disclosure."""


def _safe(method):
    @wraps(method)
    def wrapped(*args, **kwargs):
        try:
            return method(*args, **kwargs)
        except sqlite3.Error as error:
            raise MemoryAnnotationsError("聊天记忆标注暂时无法读写") from error
    return wrapped


def _instant(value):
    try:
        instant = datetime.fromisoformat(value)
        if instant.tzinfo is None or instant.utcoffset().total_seconds() != 0:
            raise ValueError
        return instant.astimezone(UTC)
    except (ValueError, TypeError, AttributeError) as error:
        raise MemoryAnnotationsError("聊天记忆原文时间无效") from error


def _sequence(value):
    if type(value) is not int or value < 1:
        raise ValueError("聊天记忆原文序号无效")


class AnnotationsStore:
    @_safe
    def initialize(self, db):
        # Individual execute calls preserve an outer caller transaction.
        statements = (
            """CREATE TABLE IF NOT EXISTS memory_annotations (
                id TEXT PRIMARY KEY, message_sequence INTEGER NOT NULL
                    REFERENCES messages(sequence) ON DELETE CASCADE,
                conversation_id TEXT NOT NULL, char_start INTEGER NOT NULL,
                char_end INTEGER NOT NULL, span_sha256 TEXT NOT NULL,
                subject TEXT NOT NULL, kind TEXT NOT NULL, polarity TEXT NOT NULL,
                topic_key TEXT NOT NULL, validity TEXT NOT NULL DEFAULT 'current',
                explicit_keep INTEGER NOT NULL, promise_state TEXT, episode_id TEXT,
                classification_version TEXT NOT NULL, date_basis TEXT, event_date TEXT,
                tier TEXT NOT NULL DEFAULT 'mid', pinned INTEGER NOT NULL DEFAULT 0,
                reason TEXT NOT NULL DEFAULT 'unclassified', evidence_count INTEGER NOT NULL DEFAULT 0,
                evidence_ids TEXT NOT NULL DEFAULT '[]', policy_version TEXT NOT NULL,
                revision INTEGER NOT NULL DEFAULT 1, confidence REAL,
                UNIQUE(message_sequence,classification_version),
                CHECK(char_start>=0 AND char_end>char_start))""",
            """CREATE INDEX IF NOT EXISTS idx_memory_annotation_topic
                ON memory_annotations(conversation_id,kind,topic_key)""",
            """CREATE INDEX IF NOT EXISTS idx_memory_annotation_message
                ON memory_annotations(message_sequence)""",
            """CREATE TABLE IF NOT EXISTS memory_annotation_links (
                newer_id TEXT NOT NULL REFERENCES memory_annotations(id) ON DELETE CASCADE,
                older_id TEXT NOT NULL REFERENCES memory_annotations(id) ON DELETE CASCADE,
                relation TEXT NOT NULL CHECK(relation='supersedes'),
                PRIMARY KEY(newer_id,older_id), CHECK(newer_id!=older_id))""",
            """CREATE TABLE IF NOT EXISTS memory_manual_tiers (
                message_sequence INTEGER PRIMARY KEY REFERENCES messages(sequence) ON DELETE CASCADE,
                tier TEXT NOT NULL CHECK(tier IN ('mid','long','permanent')),
                source_sha256 TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 1)""",
            """CREATE TABLE IF NOT EXISTS memory_annotation_audit (
                id INTEGER PRIMARY KEY, message_sequence INTEGER NOT NULL
                    REFERENCES messages(sequence) ON DELETE CASCADE,
                annotation_id TEXT REFERENCES memory_annotations(id) ON DELETE CASCADE,
                action TEXT NOT NULL, metadata TEXT NOT NULL, created_at TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS memory_semantic_runs (
                message_sequence INTEGER PRIMARY KEY REFERENCES messages(sequence) ON DELETE CASCADE,
                version TEXT NOT NULL, source_sha256 TEXT NOT NULL, created_at TEXT NOT NULL)""",
        )
        for statement in statements:
            db.execute(statement)
        if "confidence" not in {row["name"] for row in db.execute("PRAGMA table_info(memory_annotations)")}:
            db.execute("ALTER TABLE memory_annotations ADD COLUMN confidence REAL")

    @staticmethod
    def _source(db, sequence):
        _sequence(sequence)
        row = db.execute("SELECT * FROM messages WHERE sequence=?", (sequence,)).fetchone()
        source = AnnotationsStore._checked_header(row)
        return source, decode_body(row["content"])

    @staticmethod
    def _header(db, sequence):
        _sequence(sequence)
        row = db.execute("SELECT sequence,id,conversation_id,role,created_at FROM messages WHERE sequence=?",
                         (sequence,)).fetchone()
        return AnnotationsStore._checked_header(row)

    @staticmethod
    def _checked_header(row):
        if row is None:
            raise ValueError("聊天记忆原文不存在")
        if row["role"] not in {"user", "assistant"}:
            raise MemoryAnnotationsError("聊天记忆原文作者无效")
        _instant(row["created_at"])
        return dict(row)

    @staticmethod
    def _verify(annotation, original):
        start, end = annotation["char_start"], annotation["char_end"]
        if (type(start) is not int or type(end) is not int or not 0 <= start < end <= len(original)
                or hashlib.sha256(original[start:end].encode()).hexdigest() != annotation["span_sha256"]):
            raise MemoryAnnotationsError("聊天记忆标注无法对应原文")

    @staticmethod
    def _audit(db, sequence, action, metadata, identifier=None):
        db.execute("INSERT INTO memory_annotation_audit(message_sequence,annotation_id,action,metadata,created_at) "
                   "VALUES(?,?,?,?,?)", (sequence, identifier, action, json.dumps(metadata), datetime.now(UTC).isoformat()))

    @staticmethod
    def _observations(rows):
        return [Observation(
            id=row["id"], message_id=row["message_id"], role=row["role"],
            created_at=_instant(row["created_at"]), subject=row["subject"], kind=row["kind"],
            polarity=row["polarity"], topic_key=row["topic_key"],
            source_span=(row["char_start"], row["char_end"]), text_sha=row["span_sha256"],
            validity=row["validity"], explicit_keep=bool(row["explicit_keep"]),
            promise_state=row["promise_state"], episode_id=row["episode_id"],
        ) for row in rows]

    def _replay(self, db, conversation, kind, topic):
        rows = db.execute("""SELECT a.*,m.id message_id,m.role,m.created_at
            FROM memory_annotations a JOIN messages m ON m.sequence=a.message_sequence
            WHERE a.conversation_id=? AND a.kind=? AND a.topic_key=? ORDER BY m.sequence LIMIT ?""",
                          (conversation, kind, topic, TOPIC_ANNOTATION_LIMIT + 1)).fetchall()
        if len(rows) > TOPIC_ANNOTATION_LIMIT:
            raise MemoryAnnotationsError("聊天记忆主题标注数量超出重放上限")
        decisions = Policy().evaluate(self._observations(rows), datetime.now(UTC))
        for decision in decisions.values():
            db.execute("""UPDATE memory_annotations SET tier=?,pinned=?,reason=?,evidence_count=?,
                evidence_ids=?,policy_version=? WHERE id=?""", (
                decision.tier, int(decision.pinned), decision.reason,
                decision.independent_evidence_count, json.dumps(decision.evidence_ids),
                POLICY_VERSION, decision.observation_id))

    def _supersede(self, db, source, rule, identifier):
        resolved = rule["kind"] == "promise" and rule["promise_state"] in {"fulfilled", "cancelled"}
        if rule["kind"] == "promise" and not resolved:
            return
        if not resolved and not rule["correction"] and rule["kind"] != "preference":
            return
        previous = db.execute("""SELECT a.id,a.message_sequence,a.polarity,a.promise_state,a.event_date
            FROM memory_annotations a JOIN messages m ON m.sequence=a.message_sequence
            WHERE a.conversation_id=? AND a.kind=? AND a.topic_key=? AND a.subject=?
              AND a.validity='current' AND a.message_sequence<? AND m.role=?
              AND m.conversation_id=a.conversation_id""", (
                  source["conversation_id"], rule["kind"], rule["topic_key"], rule["subject"],
                  source["sequence"], source["role"])).fetchall()
        if resolved:
            previous = [old for old in previous if old["promise_state"] in {"pending", "overdue"}
                        and (rule["event_date"] is None or old["event_date"] == rule["event_date"])]
            if len(previous) != 1:
                return  # No uniquely grounded former promise; never clear every same-topic debt.
        for old in previous:
            if not resolved and not rule["correction"] and old["polarity"] == rule["polarity"]:
                continue
            db.execute("UPDATE memory_annotations SET validity='superseded',revision=revision+1 WHERE id=?", (old["id"],))
            db.execute("INSERT INTO memory_annotation_links VALUES(?,?,'supersedes')", (identifier, old["id"]))
            self._audit(db, old["message_sequence"], "superseded", {"by": identifier}, old["id"])

    @staticmethod
    def _insert(db, source, rule):
        version = rule["classification_version"]
        identifier = hashlib.sha256((source["id"] + ":" + version).encode()).hexdigest()[:32]
        fields = {"id": identifier, "message_sequence": source["sequence"], "conversation_id": source["conversation_id"],
                  "char_start": rule["span_start"], "char_end": rule["span_end"], "span_sha256": rule["span_sha256"],
                  **{key: rule[key] for key in ("subject", "kind", "polarity", "topic_key", "promise_state", "date_basis", "event_date")},
                  "explicit_keep": int(rule["explicit_keep"]), "classification_version": version,
                  "policy_version": POLICY_VERSION, "confidence": rule.get("confidence")}
        db.execute("INSERT INTO memory_annotations(" + ",".join(fields) + ") VALUES(" + ",".join("?" for _ in fields) + ")",
                   tuple(fields.values()))
        return identifier

    @_safe
    def record_auto(self, db, message_dict, sequence, enabled):
        if type(enabled) is not bool:
            raise ValueError("自动记忆识别开关无效")
        if not enabled:
            return []
        source, original = self._source(db, sequence)
        rule = classify_source(original, source["role"])
        if rule is None:
            return []
        existing = db.execute("SELECT * FROM memory_annotations WHERE message_sequence=? AND classification_version=?",
                              (sequence, rule["classification_version"])).fetchone()
        if existing:
            self._verify(existing, original)
            return self.metadata(db, [sequence], datetime.now(UTC))[sequence]["annotations"]
        count = db.execute("SELECT count(*) FROM memory_annotations WHERE conversation_id=? AND kind=? AND topic_key=?",
                           (source["conversation_id"], rule["kind"], rule["topic_key"])).fetchone()[0]
        if count >= TOPIC_ANNOTATION_LIMIT:
            return [{"status": "skipped", "reason": "topic_annotation_limit"}]
        rule = {**rule, "span_sha256": hashlib.sha256(original.encode()).hexdigest()}
        identifier = self._insert(db, source, rule)
        self._supersede(db, source, rule, identifier)
        self._replay(db, source["conversation_id"], rule["kind"], rule["topic_key"])
        self._audit(db, sequence, "record_auto", {"version": rule["classification_version"]}, identifier)
        return self.metadata(db, [sequence], datetime.now(UTC))[sequence]["annotations"]

    @_safe
    def record_semantic(self, db, sequence, claims):
        if not isinstance(claims, list) or len(claims) > MAX_CLAIMS:
            raise ValueError("语义原文标注数量无效")
        source, original = self._source(db, sequence)
        checksum = hashlib.sha256(original.encode()).hexdigest()
        previous = db.execute("SELECT * FROM memory_semantic_runs WHERE message_sequence=?", (sequence,)).fetchone()
        if previous:
            if previous["source_sha256"] != checksum or previous["version"] != SEMANTIC_VERSION:
                raise MemoryAnnotationsError("语义识别记录无法对应原文")
            return self.metadata(db, [sequence], datetime.now(UTC))[sequence]["annotations"]
        try:
            grounded = [reground_claim(original, source["role"], claim) for claim in claims]
        except SemanticResponseError as error:
            raise MemoryAnnotationsError("语义标注无法对应已保存原文") from error
        topics = set()
        for claim in (item for item in grounded if item is not None):
            count = db.execute("SELECT count(*) FROM memory_annotations WHERE conversation_id=? AND kind=? AND topic_key=?",
                               (source["conversation_id"], claim["kind"], claim["topic_key"])).fetchone()[0]
            if count >= TOPIC_ANNOTATION_LIMIT:
                continue
            rule = {**claim, "classification_version": f"{SEMANTIC_VERSION}:{claim['span_start']}:{claim['span_end']}:{claim['kind']}",
                    "correction": claim["correction"] or claim["polarity"] == "deny"}
            identifier = self._insert(db, source, rule)
            self._supersede(db, source, rule, identifier)
            self._audit(db, sequence, "record_semantic", {"version": SEMANTIC_VERSION, "confidence": claim["confidence"]}, identifier)
            topics.add((claim["kind"], claim["topic_key"]))
        for kind, topic in topics:
            self._replay(db, source["conversation_id"], kind, topic)
        db.execute("INSERT INTO memory_semantic_runs VALUES(?,?,?,?)",
                   (sequence, SEMANTIC_VERSION, checksum, datetime.now(UTC).isoformat()))
        return self.metadata(db, [sequence], datetime.now(UTC))[sequence]["annotations"]

    @_safe
    def set_manual(self, db, sequence, tier):
        if not isinstance(tier, str) or tier not in _TIERS:
            raise ValueError("聊天记忆层级无效")
        _, original = self._source(db, sequence)
        if tier == "auto":
            db.execute("DELETE FROM memory_manual_tiers WHERE message_sequence=?", (sequence,))
        else:
            db.execute("""INSERT INTO memory_manual_tiers(message_sequence,tier,source_sha256) VALUES(?,?,?)
                ON CONFLICT(message_sequence) DO UPDATE SET tier=excluded.tier,
                    source_sha256=excluded.source_sha256,revision=revision+1""",
                       (sequence, tier, hashlib.sha256(original.encode()).hexdigest()))
        self._audit(db, sequence, "manual_tier", {"tier": tier})
        return self.metadata(db, [sequence], datetime.now(UTC))[sequence]

    @_safe
    def confirm_episode(self, db, sequence):
        source, original = self._source(db, sequence)
        rows = db.execute("""SELECT * FROM memory_annotations WHERE message_sequence=?
            AND validity='current' AND polarity='affirm' AND subject='user'
            AND kind IN ('wish','preference','habit')""", (sequence,)).fetchall()
        if source["role"] != "user" or not rows:
            raise ValueError("该原文没有可确认的本人独立表达")
        for row in rows:
            self._verify(row, original)
            db.execute("UPDATE memory_annotations SET episode_id=?,revision=revision+1 WHERE id=?",
                       ("confirmed:" + source["id"], row["id"]))
            self._audit(db, sequence, "confirm_episode", {"source": source["id"]}, row["id"])
            self._replay(db, source["conversation_id"], row["kind"], row["topic_key"])
        return self.metadata(db, [sequence], datetime.now(UTC))[sequence]

    @_safe
    def corrections(self, db, sequences):
        return sorted({row["target_sequence"] for row in self.correction_details(db, sequences)})

    @_safe
    def correction_details(self, db, sequences):
        """Keep exact original annotation and current span for every bounded link."""
        if not isinstance(sequences, (list, tuple)) or len(sequences) > METADATA_LIMIT:
            raise ValueError("聊天记忆修正读取数量无效")
        return correction_details(db, sequences, self._header)

    @_safe
    def metadata(self, db, sequences, now):
        if not isinstance(sequences, (list, tuple)) or len(sequences) > METADATA_LIMIT:
            raise ValueError("聊天记忆元数据读取数量无效")
        if not isinstance(now, datetime) or now.tzinfo is None or now.utcoffset().total_seconds() != 0:
            raise ValueError("聊天记忆读取时间必须为 UTC")
        result = {}
        for sequence in dict.fromkeys(sequences):
            source = self._header(db, sequence)
            rows = db.execute("SELECT * FROM memory_annotations WHERE message_sequence=? ORDER BY id", (sequence,)).fetchall()
            override = db.execute("SELECT * FROM memory_manual_tiers WHERE message_sequence=?", (sequence,)).fetchone()
            original = decode_body(db.execute("SELECT content FROM messages WHERE sequence=?", (sequence,)).fetchone()[0]) if rows or override else None
            annotations = []
            for row in rows:
                self._verify(row, original)
                annotation = {**dict(row), "role": source["role"], "created_at": source["created_at"],
                              "span_start": row["char_start"], "span_end": row["char_end"],
                              "pinned": bool(row["pinned"]), "explicit_keep": bool(row["explicit_keep"])}
                annotation["supersedes"] = [link[0] for link in db.execute(
                    "SELECT older_id FROM memory_annotation_links WHERE newer_id=? ORDER BY older_id", (row["id"],))]
                if _instant(source["created_at"]) > now or row["validity"] != "current":
                    annotation = {**annotation, "tier": "mid", "pinned": False}
                annotations.append(annotation)
            if override and hashlib.sha256(original.encode()).hexdigest() != override["source_sha256"]:
                raise MemoryAnnotationsError("聊天记忆手动层级无法对应原文")
            strongest = max(annotations, key=lambda item: {"mid": 0, "long": 1, "permanent": 2}[item["tier"]], default=None)
            tier = override["tier"] if override else strongest["tier"] if strongest else "mid"
            source_current = _instant(source["created_at"]) <= now and (
                not annotations or any(item["validity"] == "current" for item in annotations))
            can_confirm = source["role"] == "user" and source_current and any(
                item["validity"] == "current" and item["polarity"] == "affirm"
                and item["subject"] == "user" and item["kind"] in {"wish", "preference", "habit"}
                and item["episode_id"] is None for item in annotations)
            result[sequence] = {"tier": tier, "ranking_tier": tier if source_current else "mid",
                                "manual_tier": override["tier"] if override else None,
                                "source_current": source_current, "can_confirm_independent": can_confirm,
                                "pinned": any(item["pinned"] for item in annotations),
                                "reason": "manual_override" if override else strongest["reason"] if strongest else "unannotated",
                                "annotations": annotations, "override": dict(override) if override else None}
        return result
