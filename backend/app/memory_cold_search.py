"""Bounded cold lexical recovery in the caller's owner-scoped read snapshot."""

import hashlib
import hmac
import re
from collections.abc import Sequence
from datetime import datetime, timedelta

from .body_codec import MAGIC as BODY_MAGIC
from .body_codec import MAX_BODY_BYTES, decode_body
from .memory_bloom import MAX_BLOOM_BYTES, decode_bloom
from .source_codec import ChatStorageError

MAX_SCANNED = 20000
MAX_DECODED = 64
MAX_CHARS = 256 * 1024
MAX_SOLO_CHARS = 1024 * 1024
MAX_DIRECTORY_BYTES = 32 * 1024 * 1024
_BODY_HEADER = len(BODY_MAGIC) + 4 + 32

_ROWS_SQL = """SELECT m.sequence,u.sequence turn_sequence,length(d.bloom) bloom_size
    FROM memory_cold_directory d JOIN memory_index_state s ON s.sequence=d.sequence AND s.state='cold'
    JOIN messages m ON m.sequence=d.sequence
    JOIN messages u ON u.sequence=m.sequence-(m.role='assistant') AND u.role='user'
        AND u.conversation_id=m.conversation_id
    JOIN messages a ON a.sequence=u.sequence+1 AND a.role='assistant' AND a.conversation_id=u.conversation_id
    JOIN conversations c ON c.id=u.conversation_id
    WHERE c.owner=? AND c.id=? AND a.sequence<? AND m.sequence<? {window_filter}
    ORDER BY m.sequence DESC LIMIT ?"""

_META_SQL = """SELECT m.sequence,m.id,m.role,m.created_at,typeof(m.content) body_type,
    length(CAST(m.content AS BLOB)) body_bytes,substr(CAST(m.content AS BLOB),1,42) body_header,
    s.state,s.index_version,d.char_length,d.source_sha,length(d.bloom) bloom_size
    FROM messages m LEFT JOIN memory_index_state s ON s.sequence=m.sequence
    LEFT JOIN memory_cold_directory d ON d.sequence=m.sequence
    WHERE m.conversation_id=? AND m.sequence IN (?,?) ORDER BY m.sequence"""


def _parameters(owner, identifier, before, terms, limit, excerpt, cursor):
    numbers = ((before, 1, 2 ** 63 - 1), (limit, 1, 256), (excerpt, 1, 4096))
    if any(type(value) is not int or not low <= value <= high for value, low, high in numbers):
        raise ValueError("冷记忆检索参数无效")
    if cursor is not None and (type(cursor) is not int or not 1 <= cursor <= before):
        raise ValueError("冷记忆检索游标无效")
    if any(not isinstance(value, str) or not value or len(value) > 256 for value in (owner, identifier)):
        raise ValueError("冷记忆检索身份无效")
    if not isinstance(terms, Sequence) or isinstance(terms, (str, bytes)) or len(terms) > 32:
        raise ValueError("冷记忆检索词项无效")
    try:
        if any(not isinstance(term, str) or not 1 <= len(term) <= 16000 for term in terms):
            raise ValueError("冷记忆检索词项无效")
        return tuple(sorted({"t" + term.encode("utf-8").hex() for term in terms if len(term) > 1}))
    except UnicodeError as exc:
        raise ValueError("冷记忆检索词项编码无效") from exc


def _window_filter(windows):
    if windows is None:
        return "", ()
    if not isinstance(windows, Sequence) or not 1 <= len(windows) <= 2:
        raise ValueError("冷记忆时间区间无效")
    bounds = []
    for window in windows:
        start, end = getattr(window, "start", None), getattr(window, "end", None)
        if any(not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() != timedelta(0)
               for value in (start, end)) or start > end:
            raise ValueError("冷记忆时间区间必须为 UTC")
        bounds.extend((start.isoformat(), end.isoformat()))
    clauses = " OR ".join("julianday(u.created_at) BETWEEN julianday(?) AND julianday(?)" for _ in windows)
    return "AND m.role='user' AND (" + clauses + ")", tuple(bounds)


def _body_bound(row):
    size = row["body_bytes"]
    if row["body_type"] == "text":
        expected = size
    elif row["body_type"] == "blob":
        header = row["body_header"]
        if not _BODY_HEADER < size <= MAX_BODY_BYTES + _BODY_HEADER or not header.startswith(BODY_MAGIC):
            raise ChatStorageError("冷记忆正文编码无效")
        expected = int.from_bytes(header[len(BODY_MAGIC):len(BODY_MAGIC) + 4], "big")
    else:
        raise ChatStorageError("冷记忆正文类型无效")
    if expected > MAX_BODY_BYTES:
        raise ChatStorageError("冷记忆正文长度无效")
    if row["state"] is not None and row["index_version"] != 1:
        raise ChatStorageError("冷记忆索引状态版本无效")
    if row["state"] is None or row["state"] == "active":
        return expected  # UTF-8 byte count is a conservative character bound.
    if row["state"] != "cold" or row["index_version"] != 1:
        raise ChatStorageError("冷记忆索引状态无效")
    length, digest = row["char_length"], row["source_sha"]
    if (type(length) is not int or not 0 <= length <= MAX_BODY_BYTES
            or not length <= expected <= length * 4
            or not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest)):
        raise ChatStorageError("冷记忆原文目录长度或校验无效")
    return length


def _best(original, query):
    from .memory_index import chunk_offsets, encoded_terms

    best, score = None, (0, 0, 0)
    for start, end in chunk_offsets(original):
        tokens = set(encoded_terms(original[start:end]))
        matches = len(tokens.intersection(query))
        candidate = (matches, -len(tokens), -start)
        if matches and (best is None or candidate > score):
            best, score = start, candidate
    return best


def _decode_pair(db, metadata, identifier, query, excerpt):
    originals, starts = [], []
    for row in metadata:
        value = db.execute("SELECT content FROM messages WHERE sequence=? AND conversation_id=?",
                           (row["sequence"], identifier)).fetchone()
        if value is None:
            raise ChatStorageError("冷记忆原文不存在")
        original = decode_body(value[0])
        if row["state"] == "cold":
            digest = hashlib.sha256(original.encode("utf-8")).hexdigest()
            if len(original) != row["char_length"] or not hmac.compare_digest(digest, row["source_sha"]):
                raise ChatStorageError("冷记忆原文完整性校验失败")
        originals.append(original)
        starts.append(_best(original, query))
    size = sum(len(original) for original in originals)
    if size > MAX_SOLO_CHARS:
        raise ChatStorageError("冷记忆原文目录与读取预算不一致")
    if all(start is None for start in starts):
        return None, size
    result = {"sequence": metadata[0]["sequence"]}
    for row, original, start in zip(metadata, originals, starts, strict=True):
        role, start = row["role"], start or 0
        end = min(start + excerpt, len(original))
        result.update({f"{role}_id": row["id"], f"{role}_created_at": row["created_at"],
                       f"{role}_start": start, f"{role}_char_end": end,
                       f"{role}_length": len(original), f"{role}_text": original[start:end]})
    return result, size


class _Scan:
    def __init__(self, db, owner, identifier, query, limit, excerpt):
        self.db, self.owner, self.identifier = db, owner, identifier
        self.query, self.limit, self.excerpt = query, limit, excerpt
        self.scanned = self.decoded = self.chars = self.directory_bytes = 0
        self.turns, self.seen = [], set()
        self.complete, self.solo = True, False
        self.next_before = self.blocked = None
        self.resume = None

    def _frame(self, sequence, size):
        if type(size) is not int or not 0 < size <= MAX_BLOOM_BYTES:
            raise ChatStorageError("冷记忆目录长度无效")
        if self.directory_bytes + size > MAX_DIRECTORY_BYTES:
            return None
        row = self.db.execute("SELECT bloom FROM memory_cold_directory WHERE sequence=?", (sequence,)).fetchone()
        if row is None:
            raise ChatStorageError("冷记忆目录不存在")
        frame = decode_bloom(row[0])
        self.directory_bytes += size
        return frame

    def _metadata(self, turn_sequence):
        rows = self.db.execute(_META_SQL, (self.identifier, turn_sequence, turn_sequence + 1)).fetchall()
        if len(rows) != 2 or [row["role"] for row in rows] != ["user", "assistant"]:
            raise ChatStorageError("冷记忆原文配对无效")
        return rows, sum(_body_bound(row) for row in rows)

    def _stop(self, sequence):
        self.complete, self.next_before = False, sequence + 1

    def _positive(self, row):
        metadata, estimate = self._metadata(row["turn_sequence"])
        if estimate > MAX_SOLO_CHARS:
            self.complete, self.blocked = False, row["sequence"]
            self.seen.add(row["turn_sequence"])
            self.resume = row["turn_sequence"]
            return True
        if self.decoded + 2 > MAX_DECODED or len(self.turns) >= self.limit:
            self._stop(row["sequence"])
            return False
        if self.chars + estimate > MAX_CHARS:
            if self.decoded:
                self._stop(row["sequence"])
                return False
            self.solo = True
        for source in metadata:
            if source["state"] == "cold" and source["sequence"] != row["sequence"]:
                if self._frame(source["sequence"], source["bloom_size"]) is None:
                    self._stop(row["sequence"])
                    return False
        turn, size = _decode_pair(self.db, metadata, self.identifier, self.query, self.excerpt)
        self.decoded += 2
        self.chars += size
        self.seen.add(row["turn_sequence"])
        self.resume = row["turn_sequence"]
        if turn:
            self.turns.append({**turn, "owner": self.owner, "conversation_id": self.identifier})
        return True

    def run(self, rows):
        for row in rows:
            if self.scanned >= MAX_SCANNED:
                sequence = row["turn_sequence"] - 1 if row["turn_sequence"] in self.seen else row["sequence"]
                self._stop(sequence)
                break
            self.scanned += 1
            if row["turn_sequence"] in self.seen:
                continue
            frame = self._frame(row["sequence"], row["bloom_size"])
            if frame is None:
                self._stop(row["sequence"])
                break
            if not frame.may_contain_any(self.query):
                self.resume = row["sequence"]
                continue
            if not self._positive(row):
                break
        if not self.complete and self.next_before is None:
            self.next_before = self.resume
        return {"turns": sorted(self.turns, key=lambda turn: turn["sequence"]),
                "complete": self.complete, "next_before": self.next_before,
                "scanned": self.scanned, "decoded": self.decoded,
                "decoded_chars": self.chars, "directory_bytes": self.directory_bytes,
                "solo_exception": self.solo, "blocked_sequence": self.blocked}


def search_cold(db, owner, identifier, before, terms, limit, excerpt, cursor, windows):
    query = _parameters(owner, identifier, before, terms, limit, excerpt, cursor)
    window_sql, window_bounds = _window_filter(windows)
    scan = _Scan(db, owner, identifier, query, limit, excerpt)
    if not query:
        return scan.run(())
    missing = db.execute("""SELECT m.sequence FROM memory_index_state s
        JOIN messages m ON m.sequence=s.sequence JOIN conversations c ON c.id=m.conversation_id
        WHERE s.state='cold' AND c.owner=? AND c.id=? AND m.sequence<?
        AND NOT EXISTS(SELECT 1 FROM memory_cold_directory d WHERE d.sequence=m.sequence) LIMIT 1""",
                         (owner, identifier, before)).fetchone()
    if missing is not None:
        raise ChatStorageError("冷记忆目录缺失")
    sql = _ROWS_SQL.format(window_filter=window_sql)
    rows = db.execute(sql, (owner, identifier, before, cursor or before, *window_bounds, MAX_SCANNED + 1))
    return scan.run(rows)
