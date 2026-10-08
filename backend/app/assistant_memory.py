"""Recall standalone proactive originals, preserving their assistant authorship."""

import hashlib
import hmac
from datetime import UTC, datetime

from .body_codec import decode_body
from .memory_bloom import decode_bloom
from .memory_cold_search import _best, _body_bound
from .memory_index import scope_token
from .memory_transaction import transaction
from .source_codec import ChatStorageError
from .vendor_rag.bm25 import _tokenize

MAX_COLD_ROWS = 20000
MAX_DIRECTORY_BYTES = 8 * 1024 * 1024
MAX_DECODED_CHARS = 256 * 1024
MAX_DECODED = 32

_META = """SELECT m.sequence,m.id,m.created_at,typeof(m.content) body_type,
    length(CAST(m.content AS BLOB)) body_bytes,substr(CAST(m.content AS BLOB),1,42) body_header,
    s.state,s.index_version,d.char_length,d.source_sha,length(d.bloom) bloom_size
    FROM messages m LEFT JOIN memory_index_state s ON s.sequence=m.sequence
    LEFT JOIN memory_cold_directory d ON d.sequence=m.sequence WHERE m.sequence=?"""


def _original(db, sequence):
    row = db.execute(_META, (sequence,)).fetchone()
    if row is None or _body_bound(row) > 64000:
        raise ChatStorageError("主动消息原文长度或索引状态无效")
    original = decode_body(db.execute("SELECT content FROM messages WHERE sequence=?", (sequence,)).fetchone()[0])
    if len(original) > 16000:
        raise ChatStorageError("主动消息原文超过上限")
    if row["state"] == "cold":
        digest = hashlib.sha256(original.encode("utf-8")).hexdigest()
        if len(original) != row["char_length"] or not hmac.compare_digest(digest, row["source_sha"]):
            raise ChatStorageError("主动消息冷原文完整性校验失败")
    return row, original


def _reference(chat, db, row, original, start, terms, max_chars):
    if not 0 <= start <= len(original):
        raise ChatStorageError("主动消息原文位置无效")
    excerpt = original[start:start + min(768, max_chars)]
    score = len(set(terms).intersection(_tokenize(excerpt)))
    if not score:
        return None
    memory = chat.annotations.metadata(db, [row["sequence"]], datetime.now(UTC))[row["sequence"]]
    return {"message_id": row["id"], "turn_id": row["id"], "role": "assistant", "origin": "proactive",
            "text": excerpt, "char_start": start, "char_end": start + len(excerpt),
            "created_at": row["created_at"], "truncated": start > 0 or len(excerpt) < len(original),
            "memory_tier": memory.get("tier", "mid"), "source_current": memory.get("source_current", True),
            "score": float(score), "reason": "proactive_original", "anchor_ids": [],
            "relation_confirmed": False}


def _hot_references(chat, db, owner, identifier, before, query, terms, max_chars):
    hot = db.execute("""WITH matched AS MATERIALIZED (
            SELECT m.sequence,f.char_start,memory_fts.rank retrieval_rank FROM memory_fts
            JOIN memory_fragments f ON f.id=memory_fts.rowid JOIN messages m ON m.sequence=f.message_sequence
            JOIN conversations c ON c.id=m.conversation_id
            WHERE memory_fts MATCH ? AND c.owner=? AND c.id=? AND m.sequence<?
                AND m.role='assistant' AND m.origin='proactive'),
            best AS (SELECT *,row_number() OVER(PARTITION BY sequence ORDER BY retrieval_rank,char_start) choice FROM matched)
            SELECT sequence,char_start FROM best WHERE choice=1 ORDER BY retrieval_rank,sequence DESC LIMIT 64""",
                     (query, owner, identifier, before)).fetchall()
    refs, seen = [], set()
    for match in hot:
        row, original = _original(db, match["sequence"])
        ref = _reference(chat, db, row, original, match["char_start"], terms, max_chars)
        if ref:
            refs.append(ref)
            seen.add(row["id"])
    return refs, seen


def _cold_references(chat, db, owner, identifier, before, tokens, terms, max_chars, hot_ids):
    cold = db.execute("""SELECT m.sequence,length(d.bloom) bloom_size FROM memory_cold_directory d
            JOIN messages m ON m.sequence=d.sequence JOIN conversations c ON c.id=m.conversation_id
            JOIN memory_index_state s ON s.sequence=m.sequence AND s.state='cold'
            WHERE c.owner=? AND c.id=? AND m.sequence<? AND m.role='assistant' AND m.origin='proactive'
            ORDER BY m.sequence DESC LIMIT ?""", (owner, identifier, before, MAX_COLD_ROWS + 1)).fetchall()
    complete = len(cold) <= MAX_COLD_ROWS
    refs, seen = [], set(hot_ids)
    directory_bytes, decoded, chars = 0, 0, 0
    for match in cold[:MAX_COLD_ROWS]:
        size = match["bloom_size"]
        if type(size) is not int or not 0 < size <= 1024 * 1024:
            raise ChatStorageError("主动消息冷目录长度无效")
        if directory_bytes + size > MAX_DIRECTORY_BYTES or decoded >= MAX_DECODED or chars >= MAX_DECODED_CHARS:
            complete = False
            break
        frame = db.execute("SELECT bloom FROM memory_cold_directory WHERE sequence=?", (match["sequence"],)).fetchone()[0]
        directory_bytes += size
        if not decode_bloom(frame).may_contain_any(tokens):
            continue
        row, original = _original(db, match["sequence"])
        chars += len(original)
        decoded += 1
        start = _best(original, frozenset(tokens))
        ref = _reference(chat, db, row, original, start, terms, max_chars) if start is not None else None
        if ref and row["id"] not in seen:
            refs.append(ref)
            seen.add(row["id"])
    return refs, complete


def standalone_references(chat, owner, identifier, before, terms, *, max_chars=3072):
    if not terms or max_chars <= 0:
        return {"references": [], "complete": True}
    if type(before) is not int or before < 1 or len(terms) > 32:
        raise ValueError("主动原文检索参数无效")
    tokens = tuple("t" + term.encode("utf-8").hex() for term in terms)
    query = scope_token(identifier) + " AND (" + " OR ".join(tokens) + ")"
    with transaction(chat) as db:
        hot, seen = _hot_references(chat, db, owner, identifier, before, query, terms, max_chars)
        cold, complete = _cold_references(chat, db, owner, identifier, before, tokens, terms, max_chars, seen)
    refs = hot + cold
    selected, remaining = [], max_chars
    for ref in sorted(refs, key=lambda item: (item["score"], item["created_at"]), reverse=True):
        if len(selected) >= 4 or len(ref["text"]) > remaining:
            continue
        selected.append(ref)
        remaining -= len(ref["text"])
    return {"references": selected, "complete": complete}
