import sqlite3
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from .companion import check_profile_change, decode_profile, encode_profile, normalize_profile
from .memory_index import MemoryIndex
from .source_codec import decode_sources, encode_sources


def timestamp() -> str:
    return datetime.now(UTC).isoformat()


class HistoryCursorError(ValueError):
    """Cursor does not belong to the requested owner's conversation."""


class ChatDatabase:
    def __init__(self, data_dir: Path):
        data_dir.mkdir(parents=True, exist_ok=True)
        self.path = data_dir / "chat.sqlite3"
        self.memory = MemoryIndex()
        with self.connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL, title TEXT NOT NULL,
                    model TEXT NOT NULL, use_rag INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL, companion_profile TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_conversation_owner
                    ON conversations(owner, updated_at);
                CREATE TABLE IF NOT EXISTS messages (
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL,
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    role TEXT NOT NULL CHECK(role IN ('user','assistant')),
                    content TEXT NOT NULL, sources TEXT NOT NULL DEFAULT '[]',
                    created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_messages_conversation
                    ON messages(conversation_id, sequence);
            """)
            db.execute("BEGIN IMMEDIATE")
            columns = {row["name"] for row in db.execute("PRAGMA table_info(conversations)")}
            if "companion_profile" not in columns:
                db.execute("ALTER TABLE conversations ADD COLUMN companion_profile TEXT")
            self.memory.initialize(db)
        after = 0
        while after is not None:
            with self.connection() as db:
                db.execute("BEGIN IMMEDIATE")
                after = self.memory.backfill_batch(db, after)

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA journal_mode=WAL")
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def _conversation(row):
        if row is None:
            return None
        result = {k: (bool(row[k]) if k == "use_rag" else row[k]) for k in row.keys() if k != "owner"}
        profile = decode_profile(result["companion_profile"])
        return {**result, "companion_profile": profile,
                "profile_locked": bool(profile and profile["mode"] == "preset")}

    def list_conversations(self, owner: str):
        with self.connection() as db:
            rows = db.execute("SELECT * FROM conversations WHERE owner=? ORDER BY updated_at DESC", (owner,)).fetchall()
        return [self._conversation(row) for row in rows]

    def get_conversation(self, owner: str, identifier: str):
        with self.connection() as db:
            row = db.execute("SELECT * FROM conversations WHERE id=? AND owner=?", (identifier, owner)).fetchone()
        return self._conversation(row)

    def create_conversation(self, owner: str, title: str, model: str, use_rag: bool,
                            companion_profile: dict | None = None):
        identifier, now = uuid4().hex, timestamp()
        with self.connection() as db:
            db.execute("""INSERT INTO conversations
                (id,owner,title,model,use_rag,created_at,updated_at,companion_profile) VALUES (?,?,?,?,?,?,?,?)""",
                       (identifier, owner, title, model, int(use_rag), now, now, encode_profile(companion_profile)))
        return self.get_conversation(owner, identifier)

    def update_conversation(self, owner: str, identifier: str, values: dict):
        fields = {k: v for k, v in values.items() if k in {"title", "use_rag", "companion_profile"}}
        if fields:
            fields["updated_at"] = timestamp()
            assignments = ",".join(f"{key}=?" for key in fields)
            with self.connection() as db:
                db.execute("BEGIN IMMEDIATE")
                row = db.execute("SELECT companion_profile FROM conversations WHERE id=? AND owner=?",
                                 (identifier, owner)).fetchone()
                if row is None:
                    return None
                if "companion_profile" in fields:
                    proposed = normalize_profile(fields["companion_profile"])
                    check_profile_change(decode_profile(row["companion_profile"]), proposed)
                    fields = {**fields, "companion_profile": encode_profile(proposed)}
                db.execute(f"UPDATE conversations SET {assignments} WHERE id=? AND owner=?",
                           (*fields.values(), identifier, owner))
        return self.get_conversation(owner, identifier)

    def delete_conversation(self, owner: str, identifier: str):
        with self.connection() as db:
            return db.execute("DELETE FROM conversations WHERE id=? AND owner=?", (identifier, owner)).rowcount > 0

    def list_messages(self, owner: str, identifier: str):
        with self.connection() as db:
            rows = db.execute("""SELECT m.* FROM messages m JOIN conversations c ON m.conversation_id=c.id
                WHERE c.owner=? AND c.id=? ORDER BY m.sequence""", (owner, identifier)).fetchall()
        return [self._message(row) for row in rows]

    @staticmethod
    def _message(row):
        return {k: (decode_sources(row[k]) if k == "sources" else row[k])
                for k in row.keys() if k not in {"sequence", "conversation_id"}}

    def history_page(self, owner: str, identifier: str, limit: int, before: str | None):
        with self.connection() as db:
            cursor_sequence = None
            if before is not None:
                cursor = db.execute("""
                    SELECT m.sequence FROM messages m JOIN conversations c ON c.id=m.conversation_id
                    WHERE c.owner=? AND c.id=? AND m.id=?
                    """, (owner, identifier, before)).fetchone()
                if cursor is None:
                    raise HistoryCursorError("会话历史游标不存在")
                cursor_sequence = cursor["sequence"]
            boundary = " AND m.sequence<?" if cursor_sequence is not None else ""
            parameters = (owner, identifier, cursor_sequence) if boundary else (owner, identifier)
            rows = db.execute(f"""
                SELECT m.* FROM messages m JOIN conversations c ON c.id=m.conversation_id
                WHERE c.owner=? AND c.id=?{boundary} ORDER BY m.sequence DESC LIMIT ?
                """, (*parameters, limit + 1)).fetchall()
        has_more = len(rows) > limit
        page = [self._message(row) for row in reversed(rows[:limit])]
        return {"messages": page, "has_more": has_more,
                "next_before": page[0]["id"] if has_more else None}

    def recent_turns(self, owner: str, identifier: str, limit: int, char_limit: int):
        with self.connection() as db:
            rows = db.execute("""
                SELECT u.sequence, u.id user_id, a.id assistant_id,
                    substr(u.content,1,?) user_text, substr(a.content,1,?) assistant_text,
                    length(u.content) user_length, length(a.content) assistant_length
                FROM messages u JOIN messages a ON a.conversation_id=u.conversation_id
                    AND a.sequence=u.sequence+1 AND a.role='assistant'
                JOIN conversations c ON c.id=u.conversation_id
                WHERE c.owner=? AND c.id=? AND u.role='user'
                ORDER BY u.sequence DESC LIMIT ?
                """, (char_limit, char_limit, owner, identifier, limit)).fetchall()
        return [dict(row) for row in rows]

    def memory_candidates(self, owner: str, identifier: str, before: int,
                          terms: list[str], limit: int, excerpt_chars: int):
        with self.connection() as db:
            return self.memory.candidates(db, owner, identifier, before, terms, limit, excerpt_chars)

    def memory_event_candidates(self, owner, identifier, before, windows, limit=32, excerpt_chars=768):
        with self.connection() as db:
            return self.memory.event_candidates(db, owner, identifier, before, windows, limit, excerpt_chars)

    def save_turn(self, owner: str, identifier: str, content: str, answer: str, sources: list):
        user = {"id": uuid4().hex, "role": "user", "content": content, "sources": [], "created_at": timestamp()}
        assistant = {"id": uuid4().hex, "role": "assistant", "content": answer, "sources": sources, "created_at": timestamp()}
        with self.connection() as db:
            row = db.execute("SELECT title FROM conversations WHERE id=? AND owner=?", (identifier, owner)).fetchone()
            if row is None:
                raise ValueError("会话不存在")
            for message in (user, assistant):
                cursor = db.execute("INSERT INTO messages (id,conversation_id,role,content,sources,created_at) VALUES (?,?,?,?,?,?)",
                                    (message["id"], identifier, message["role"], message["content"], encode_sources(message["sources"]), message["created_at"]))
                self.memory.index_message(db, cursor.lastrowid, identifier, message["content"])
            title = content[:30] if row["title"] == "新对话" else row["title"]
            db.execute("UPDATE conversations SET title=?,updated_at=? WHERE id=? AND owner=?",
                       (title, assistant["created_at"], identifier, owner))
        return {"user_message": user, "assistant_message": assistant}
