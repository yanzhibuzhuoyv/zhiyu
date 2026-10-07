"""Transactional active/cold state over unchanged canonical original messages.

Callers authorize the owner and protect a full write transaction before cool/warm.
No implicit commit or automatic policy-driven cooling occurs in this module.
"""

import hashlib
import hmac
import sqlite3
from functools import wraps

from .body_codec import MAX_BODY_BYTES, decode_body
from .memory_bloom import decode_bloom, encode_bloom
from .source_codec import ChatStorageError

INDEX_VERSION = 1
BACKFILL_ACTIVE_SQL = """NOT EXISTS (
    SELECT 1 FROM memory_index_state cold_state
    WHERE cold_state.sequence=m.sequence AND cold_state.state='cold'
)"""


def _storage(function):
    @wraps(function)
    def wrapped(*args, **kwargs):
        try:
            return function(*args, **kwargs)
        except sqlite3.Error as exc:
            raise ChatStorageError("冷记忆存储操作暂时不可用") from exc
    return wrapped


def _transaction(db):
    if not db.in_transaction:
        raise ChatStorageError("冷记忆操作需要完整事务")


def _canonical(db, sequence, identifier, original):
    if not isinstance(original, str):
        raise ChatStorageError("冷记忆原文类型无效")
    decode_body(original)
    row = db.execute("SELECT content FROM messages WHERE sequence=? AND conversation_id=?",
                     (sequence, identifier)).fetchone()
    if row is None:
        raise ChatStorageError("冷记忆原文不属于当前会话")
    source = decode_body(row[0])
    digest = hashlib.sha256(source.encode("utf-8")).hexdigest()
    supplied = hashlib.sha256(original.encode("utf-8")).hexdigest()
    if len(source) != len(original) or not hmac.compare_digest(digest, supplied):
        raise ChatStorageError("冷记忆原文完整性校验失败")
    return digest


def _verify_directory(db, sequence, original, digest):
    row = db.execute("SELECT bloom,source_sha,char_length FROM memory_cold_directory WHERE sequence=?",
                     (sequence,)).fetchone()
    if row is None:
        raise ChatStorageError("冷记忆目录缺失")
    decode_bloom(row[0])
    if row[2] != len(original) or not isinstance(row[1], str) or not hmac.compare_digest(row[1], digest):
        raise ChatStorageError("冷记忆目录与原文不一致")


def _original_terms(original):
    # Imported at runtime so MemoryIndex may itself own a ColdIndex.
    from .memory_index import chunk_offsets, encoded_terms

    return sorted({term for start, end in chunk_offsets(original)
                   for term in encoded_terms(original[start:end])})


class ColdIndex:
    @staticmethod
    def pending_turn(db, sequence, identifier):
        source = db.execute("SELECT role FROM messages WHERE sequence=? AND conversation_id=?",
                            (sequence, identifier)).fetchone()
        if source is None:
            return False
        first = sequence - int(source["role"] == "assistant")
        return db.execute("""SELECT 1 FROM memory_recognition r JOIN messages m ON m.sequence=r.sequence
            WHERE m.conversation_id=? AND m.sequence IN (?,?)
              AND r.status IN ('pending','running','waiting_auth') LIMIT 1""",
                          (identifier, first, first + 1)).fetchone() is not None

    @_storage
    def initialize(self, db):
        statements = (
            """CREATE TABLE IF NOT EXISTS memory_index_state (
                sequence INTEGER PRIMARY KEY REFERENCES messages(sequence) ON DELETE CASCADE,
                state TEXT NOT NULL CHECK(state IN ('active','cold')),
                index_version INTEGER NOT NULL CHECK(index_version>0),
                revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0)
            )""",
            f"""CREATE TABLE IF NOT EXISTS memory_cold_directory (
                sequence INTEGER PRIMARY KEY REFERENCES messages(sequence) ON DELETE CASCADE,
                bloom BLOB NOT NULL CHECK(typeof(bloom)='blob'),
                source_sha TEXT NOT NULL CHECK(length(source_sha)=64),
                char_length INTEGER NOT NULL CHECK(typeof(char_length)='integer'
                    AND char_length>=0 AND char_length<={MAX_BODY_BYTES})
            )""",
        )
        for statement in statements:
            db.execute(statement)

    @_storage
    def get_state(self, db, sequence) -> dict:
        row = db.execute("SELECT state,index_version,revision FROM memory_index_state WHERE sequence=?",
                         (sequence,)).fetchone()
        if row is None:
            return {"index_state": "active", "revision": 0}
        state, version, revision = row
        if state not in {"active", "cold"} or version != INDEX_VERSION or type(revision) is not int or revision < 1:
            raise ChatStorageError("冷记忆状态版本无效")
        return {"index_state": state, "revision": revision}

    def is_cold(self, db, sequence) -> bool:
        return self.get_state(db, sequence)["index_state"] == "cold"

    @_storage
    def bump_revision(self, db, sequence) -> dict:
        _transaction(db)
        self.get_state(db, sequence)
        db.execute("""INSERT INTO memory_index_state(sequence,state,index_version,revision)
            VALUES(?,'active',?,1) ON CONFLICT(sequence)
            DO UPDATE SET revision=memory_index_state.revision+1""", (sequence, INDEX_VERSION))
        return self.get_state(db, sequence)

    @_storage
    def cool(self, db, sequence, identifier, original, indexer) -> dict:
        _transaction(db)
        digest = _canonical(db, sequence, identifier, original)
        if self.pending_turn(db, sequence, identifier):
            raise ChatStorageError("原文仍在等待识别，需要保留活跃检索入口")
        if self.is_cold(db, sequence):
            _verify_directory(db, sequence, original, digest)
            return self.get_state(db, sequence)
        bloom = encode_bloom(_original_terms(original))
        db.execute("""INSERT INTO memory_cold_directory(sequence,bloom,source_sha,char_length)
            VALUES(?,?,?,?) ON CONFLICT(sequence) DO UPDATE SET
            bloom=excluded.bloom,source_sha=excluded.source_sha,char_length=excluded.char_length""",
                   (sequence, bloom, digest, len(original)))
        db.execute("DELETE FROM memory_fragments WHERE message_sequence=?", (sequence,))
        db.execute("""INSERT INTO memory_index_state(sequence,state,index_version,revision)
            VALUES(?,'cold',?,1) ON CONFLICT(sequence) DO UPDATE SET
            state='cold',index_version=excluded.index_version,revision=memory_index_state.revision+1""",
                   (sequence, INDEX_VERSION))
        return self.get_state(db, sequence)

    @_storage
    def warm(self, db, sequence, identifier, original, indexer) -> dict:
        _transaction(db)
        digest = _canonical(db, sequence, identifier, original)
        if not self.is_cold(db, sequence):
            return self.get_state(db, sequence)
        _verify_directory(db, sequence, original, digest)
        db.execute("UPDATE memory_index_state SET state='active',revision=revision+1 WHERE sequence=?",
                   (sequence,))
        db.execute("DELETE FROM memory_cold_directory WHERE sequence=?", (sequence,))
        indexer.index_message(db, sequence, identifier, original)
        return self.get_state(db, sequence)

    @_storage
    def search(self, db, owner, identifier, before, terms, limit=256, excerpt_chars=768,
               cursor=None, windows=None):
        from .memory_cold_search import search_cold

        _transaction(db)
        return search_cold(db, owner, identifier, before, terms, limit, excerpt_chars, cursor, windows)
