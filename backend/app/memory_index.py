"""Persistent lexical chat RAG over both roles, without a second original text.

FTS stores derived word postings only; fragments point to canonical messages.
This candidate contract can later host an actual embedding implementation.
It currently provides lexical retrieval, not vector semantics or Engram.
"""

import sqlite3

from .body_codec import decode_body
from .body_reads import decoded_turn
from .memory_ranking import EVENT_TERMS
from .source_codec import ChatStorageError
from .vendor_rag.bm25 import _tokenize

CHUNK_CHARS = 768
CHUNK_OVERLAP = 128
BACKFILL_BATCH = 128
INDEX_VERSION = 1


class MemoryIndexError(ChatStorageError):
    """A chat RAG index read/write failed; never disclose original chat text."""


def scope_token(identifier: str) -> str:
    return "c" + identifier.encode("utf-8").hex()


def encoded_terms(text: str) -> list[str]:
    return sorted({"t" + term.encode("utf-8").hex() for term in _tokenize(text) if len(term) > 1})


def chunk_offsets(content: str):
    start = 0
    while True:
        end = min(start + CHUNK_CHARS, len(content))
        yield start, end
        if end == len(content):
            break
        start = end - CHUNK_OVERLAP


class MemoryIndex:
    def initialize(self, db):
        if sqlite3.sqlite_version_info < (3, 43, 0):
            raise RuntimeError("聊天 RAG 需要 SQLite >= 3.43.0 和 FTS5，请升级 Python/SQLite")
        statements = (
            """CREATE TABLE IF NOT EXISTS memory_fragments (
                id INTEGER PRIMARY KEY,
                message_sequence INTEGER NOT NULL REFERENCES messages(sequence) ON DELETE CASCADE,
                char_start INTEGER NOT NULL CHECK(char_start>=0),
                char_end INTEGER NOT NULL CHECK(char_end>=char_start),
                UNIQUE(message_sequence,char_start)
            )""",
            """CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
                tokens, content='', contentless_delete=1, tokenize='ascii'
            )""",
            """CREATE TRIGGER IF NOT EXISTS memory_fragment_delete AFTER DELETE ON memory_fragments
                BEGIN DELETE FROM memory_fts WHERE rowid=old.id; END""",
            """CREATE TABLE IF NOT EXISTS memory_index_metadata (
                id INTEGER PRIMARY KEY CHECK(id=1), version INTEGER NOT NULL
            )""",
            "INSERT OR IGNORE INTO memory_index_metadata(id,version) VALUES(1,1)",
        )
        try:
            for statement in statements:
                db.execute(statement)
            version = db.execute("SELECT version FROM memory_index_metadata WHERE id=1").fetchone()[0]
        except sqlite3.Error as error:
            raise RuntimeError("聊天 RAG 需要支持 contentless_delete 的 SQLite FTS5") from error
        if version != INDEX_VERSION:
            raise RuntimeError("聊天 RAG 索引版本不兼容，请先迁移索引")

    def index_message(self, db, sequence: int, identifier: str, content: str):
        try:
            if db.execute("SELECT 1 FROM memory_fragments WHERE message_sequence=? LIMIT 1", (sequence,)).fetchone():
                return
            scope = scope_token(identifier)
            for start, end in chunk_offsets(content):
                cursor = db.execute(
                    "INSERT INTO memory_fragments(message_sequence,char_start,char_end) VALUES(?,?,?)",
                    (sequence, start, end),
                )
                tokens = " ".join([scope, *encoded_terms(content[start:end])])
                db.execute("INSERT INTO memory_fts(rowid,tokens) VALUES(?,?)", (cursor.lastrowid, tokens))
        except sqlite3.Error as error:
            raise MemoryIndexError("聊天 RAG 索引暂时无法写入") from error

    def backfill_batch(self, db, after: int) -> int | None:
        rows = db.execute("""
            SELECT m.sequence,m.conversation_id FROM messages m
            WHERE m.sequence>? AND NOT EXISTS (
                SELECT 1 FROM memory_fragments f WHERE f.message_sequence=m.sequence
            ) ORDER BY m.sequence LIMIT ?
            """, (after, BACKFILL_BATCH))
        last = None
        for row in rows:
            content = decode_body(db.execute("SELECT content FROM messages WHERE sequence=?", (row["sequence"],)).fetchone()[0])
            self.index_message(db, row["sequence"], row["conversation_id"], content)
            del content
            last = row["sequence"]
        return last

    def candidates(self, db, owner: str, identifier: str, before: int,
                   terms: list[str], limit: int, excerpt_chars: int) -> list[dict]:
        if not terms or limit < 1:
            return []
        # Only hex-derived barewords reach MATCH; arbitrary query syntax stays data.
        tokens = ["t" + term.encode("utf-8").hex() for term in terms]
        query = scope_token(identifier) + " AND (" + " OR ".join(tokens) + ")"
        latest_limit = min(64, max(1, limit // 4))
        parameters = (query, owner, identifier, before, limit - latest_limit, latest_limit)
        try:
            return [{**decoded_turn(db, row, excerpt_chars), "owner": owner, "conversation_id": identifier}
                    for row in db.execute(CANDIDATES_SQL, parameters)]
        except sqlite3.Error as error:
            raise MemoryIndexError("聊天 RAG 索引暂时无法检索") from error

    def event_candidates(self, db, owner, identifier, before, windows, limit=32, excerpt_chars=768):
        if not windows or limit < 1:
            return []
        # Reuse indexed pair/fragment selection. Scope, role, boundary and time filters
        # apply in MATCH before deduplication and LIMIT; no global neighbor scan.
        windows = windows[:2]
        time_filter = " OR ".join("julianday(u.created_at) BETWEEN julianday(?) AND julianday(?)" for _ in windows)
        sql = CANDIDATES_SQL.replace("AND a.sequence<?", "AND a.sequence<? AND m.role='user' AND (" + time_filter + ")")
        query = scope_token(identifier) + " AND (" + " OR ".join(
            "t" + term.encode("utf-8").hex() for term in EVENT_TERMS) + ")"
        bounds = tuple(time.isoformat() for window in windows for time in (window.start, window.end))
        latest_limit = min(8, max(1, limit // 4))
        try:
            parameters = (query, owner, identifier, before, *bounds, limit - latest_limit, latest_limit)
            return [{**decoded_turn(db, row, excerpt_chars), "owner": owner, "conversation_id": identifier}
                    for row in db.execute(sql, parameters)]
        except sqlite3.Error as error:
            raise MemoryIndexError("聊天 RAG 事件索引暂时无法检索") from error


CANDIDATES_SQL = """
    WITH matched AS MATERIALIZED (
        SELECT u.sequence turn_sequence,m.role,f.char_start,f.char_end,memory_fts.rank retrieval_rank
        FROM memory_fts
        JOIN memory_fragments f ON f.id=memory_fts.rowid
        JOIN messages m ON m.sequence=f.message_sequence
        JOIN messages u ON u.sequence=m.sequence-(m.role='assistant') AND u.role='user'
            AND u.conversation_id=m.conversation_id
        JOIN messages a ON a.sequence=u.sequence+1 AND a.role='assistant'
            AND a.conversation_id=u.conversation_id
        JOIN conversations c ON c.id=u.conversation_id
        WHERE memory_fts MATCH ? AND c.owner=? AND c.id=? AND a.sequence<?
    ), pairs AS MATERIALIZED (
        SELECT turn_sequence,MIN(retrieval_rank) relevance FROM matched GROUP BY turn_sequence
    ), relevant AS MATERIALIZED (
        SELECT turn_sequence FROM pairs ORDER BY relevance,turn_sequence DESC LIMIT ?
    ), latest AS (
        SELECT turn_sequence FROM pairs WHERE turn_sequence NOT IN (SELECT turn_sequence FROM relevant)
        ORDER BY turn_sequence DESC LIMIT ?
    ), selected AS (
        SELECT turn_sequence FROM relevant UNION ALL SELECT turn_sequence FROM latest
    ), best AS (
        SELECT turn_sequence,role,char_start,
            ROW_NUMBER() OVER (PARTITION BY turn_sequence,role ORDER BY retrieval_rank,char_start) choice
        FROM matched
        WHERE turn_sequence IN (SELECT turn_sequence FROM selected)
    )
    SELECT u.sequence,u.id user_id,a.id assistant_id,
        COALESCE(bu.char_start,0) user_start,COALESCE(ba.char_start,0) assistant_start,
        u.created_at user_created_at,a.created_at assistant_created_at
    FROM selected s JOIN messages u ON u.sequence=s.turn_sequence
    JOIN messages a ON a.sequence=u.sequence+1
    LEFT JOIN best bu ON bu.turn_sequence=u.sequence AND bu.role='user' AND bu.choice=1
    LEFT JOIN best ba ON ba.turn_sequence=u.sequence AND ba.role='assistant' AND ba.choice=1
    ORDER BY u.sequence
"""
