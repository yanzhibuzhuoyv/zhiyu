import json
import sqlite3
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4


def timestamp() -> str:
    return datetime.now(UTC).isoformat()


class ChatDatabase:
    def __init__(self, data_dir: Path):
        data_dir.mkdir(parents=True, exist_ok=True)
        self.path = data_dir / "chat.sqlite3"
        with self.connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL, title TEXT NOT NULL,
                    model TEXT NOT NULL, use_rag INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
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
        return {k: (bool(row[k]) if k == "use_rag" else row[k]) for k in row.keys() if k != "owner"}

    def list_conversations(self, owner: str):
        with self.connection() as db:
            rows = db.execute("SELECT * FROM conversations WHERE owner=? ORDER BY updated_at DESC", (owner,)).fetchall()
        return [self._conversation(row) for row in rows]

    def get_conversation(self, owner: str, identifier: str):
        with self.connection() as db:
            row = db.execute("SELECT * FROM conversations WHERE id=? AND owner=?", (identifier, owner)).fetchone()
        return self._conversation(row)

    def create_conversation(self, owner: str, title: str, model: str, use_rag: bool):
        identifier, now = uuid4().hex, timestamp()
        with self.connection() as db:
            db.execute("INSERT INTO conversations VALUES (?,?,?,?,?,?,?)",
                       (identifier, owner, title, model, int(use_rag), now, now))
        return self.get_conversation(owner, identifier)

    def update_conversation(self, owner: str, identifier: str, values: dict):
        fields = {k: v for k, v in values.items() if k in {"title", "use_rag"}}
        if fields:
            fields["updated_at"] = timestamp()
            assignments = ",".join(f"{key}=?" for key in fields)
            with self.connection() as db:
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
        return [{k: (json.loads(row[k]) if k == "sources" else row[k])
                 for k in row.keys() if k not in {"sequence", "conversation_id"}} for row in rows]

    def save_turn(self, owner: str, identifier: str, content: str, answer: str, sources: list):
        user = {"id": uuid4().hex, "role": "user", "content": content, "sources": [], "created_at": timestamp()}
        assistant = {"id": uuid4().hex, "role": "assistant", "content": answer, "sources": sources, "created_at": timestamp()}
        with self.connection() as db:
            row = db.execute("SELECT title FROM conversations WHERE id=? AND owner=?", (identifier, owner)).fetchone()
            if row is None:
                raise ValueError("会话不存在")
            for message in (user, assistant):
                db.execute("INSERT INTO messages (id,conversation_id,role,content,sources,created_at) VALUES (?,?,?,?,?,?)",
                           (message["id"], identifier, message["role"], message["content"], json.dumps(message["sources"], ensure_ascii=False), message["created_at"]))
            title = content[:30] if row["title"] == "新对话" else row["title"]
            db.execute("UPDATE conversations SET title=?,updated_at=? WHERE id=? AND owner=?",
                       (title, assistant["created_at"], identifier, owner))
        return {"user_message": user, "assistant_message": assistant}
