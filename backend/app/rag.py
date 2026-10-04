"""Private, persistent retrieval using Chinese BM25 and optional local embeddings."""

import hashlib
import json
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

from .rag_parser import extract_text, safe_document_name
from .vendor_rag.bm25 import BM25Index
from .vendor_rag.splitter import TextSplitter


class RagStorageError(RuntimeError):
    """Storage failed; never expose SQL or document text to API clients."""


class RagService:
    def __init__(self, data_dir: Path, embedding_model: str | None = None):
        self._encoder = None
        if embedding_model:
            from .rag_semantic import LocalSemanticEncoder

            self._encoder = LocalSemanticEncoder(embedding_model)
        self.mode = self._encoder.mode if self._encoder else "bm25"
        self._splitter = TextSplitter(chunk_size=512, chunk_overlap=80)
        try:
            data_dir.mkdir(parents=True, exist_ok=True)
            self._database = data_dir / "rag.sqlite3"
            self._initialize_database()
        except OSError as exc:
            raise RagStorageError("知识库目录不可用") from exc

    @contextmanager
    def _connect(self):
        try:
            connection = sqlite3.connect(self._database, timeout=15)
            connection.row_factory = sqlite3.Row
            connection.execute("PRAGMA foreign_keys = ON")
            try:
                with connection:
                    yield connection
            finally:
                connection.close()
        except sqlite3.Error as exc:
            raise RagStorageError("知识库存储暂时不可用") from exc

    def _initialize_database(self) -> None:
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS documents (
                    id TEXT PRIMARY KEY,
                    owner TEXT NOT NULL,
                    name TEXT NOT NULL,
                    digest TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    chunks INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    UNIQUE(owner, name, digest)
                );
                CREATE INDEX IF NOT EXISTS idx_rag_documents_owner ON documents(owner);
                CREATE TABLE IF NOT EXISTS chunks (
                    id INTEGER PRIMARY KEY,
                    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                    position INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    UNIQUE(document_id, position)
                );
                CREATE TABLE IF NOT EXISTS embeddings (
                    chunk_id INTEGER NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
                    model TEXT NOT NULL,
                    vector TEXT NOT NULL,
                    PRIMARY KEY(chunk_id, model)
                );
                """
            )

    def ingest(self, owner: str, name: str, content: bytes) -> dict:
        self._validate_owner(owner)
        name = safe_document_name(name)
        text = extract_text(name, content)
        chunks = self._splitter.split(text)
        if not chunks or len(chunks) > 4096:
            raise ValueError("文档为空或分块数量过多，请拆分后上传")
        digest = hashlib.sha256(content).hexdigest()
        with self._connect() as connection:
            # Immediate transactions make identical concurrent uploads idempotent.
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                "SELECT * FROM documents WHERE owner = ? AND name = ? AND digest = ?",
                (owner, name, digest),
            ).fetchone()
            if existing:
                return self._document_dict(existing)
            current_chunks = connection.execute(
                "SELECT COALESCE(SUM(chunks), 0) FROM documents WHERE owner = ?", (owner,)
            ).fetchone()[0]
            if current_chunks + len(chunks) > 20000:
                raise ValueError("个人知识库最多保留 20000 个文字块，请先删除部分文档")
            document_id = uuid.uuid4().hex
            created_at = datetime.now(UTC).isoformat()
            connection.execute(
                "INSERT INTO documents VALUES (?, ?, ?, ?, ?, ?, ?)",
                (document_id, owner, name, digest, len(content), len(chunks), created_at),
            )
            connection.executemany(
                "INSERT INTO chunks(document_id, position, text) VALUES (?, ?, ?)",
                ((document_id, position, chunk) for position, chunk in enumerate(chunks)),
            )
            return {
                "id": document_id,
                "name": name,
                "size": len(content),
                "chunks": len(chunks),
                "created_at": created_at,
            }

    def list_documents(self, owner: str) -> list[dict]:
        self._validate_owner(owner)
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT * FROM documents WHERE owner = ? ORDER BY created_at DESC, id DESC",
                (owner,),
            ).fetchall()
        return [self._document_dict(row) for row in rows]

    def delete(self, owner: str, document_id: str) -> bool:
        self._validate_owner(owner)
        if not isinstance(document_id, str) or not document_id or len(document_id) > 128:
            raise ValueError("文档标识无效")
        with self._connect() as connection:
            cursor = connection.execute(
                "DELETE FROM documents WHERE owner = ? AND id = ?", (owner, document_id)
            )
            return cursor.rowcount > 0

    def search(self, owner: str, query: str, top_k: int = 5) -> list[dict]:
        self._validate_owner(owner)
        if not isinstance(query, str) or len(query) > 16000:
            raise ValueError("检索问题无效或过长")
        if type(top_k) is not int or not 1 <= top_k <= 20:
            raise ValueError("检索数量必须在 1 到 20 之间")
        if not query.strip():
            return []
        with self._connect() as connection:
            rows = connection.execute(
                """SELECT c.id, c.document_id, c.text, d.name FROM chunks c
                   JOIN documents d ON c.document_id = d.id
                   WHERE d.owner = ? ORDER BY c.id""",
                (owner,),
            ).fetchall()
        if not rows:
            return []
        scores = BM25Index([row["text"] for row in rows]).scores(query)
        if self._encoder:
            scores = self._semantic_scores(owner, query, rows, scores)
        ranked = sorted(zip(rows, scores, strict=True), key=lambda pair: pair[1], reverse=True)
        return [
            {
                "document_id": row["document_id"],
                "name": row["name"],
                "text": row["text"],
                "score": round(float(score), 6),
            }
            for row, score in ranked
            if score > 0
        ][:top_k]

    def _semantic_scores(self, owner, query, rows, lexical_scores):
        assert self._encoder is not None
        with self._connect() as connection:
            cached_rows = connection.execute(
                """SELECT e.chunk_id, e.vector FROM embeddings e
                   JOIN chunks c ON c.id = e.chunk_id JOIN documents d ON d.id = c.document_id
                   WHERE d.owner = ? AND e.model = ?""",
                (owner, self._encoder.identity),
            ).fetchall()
        try:
            cached = {row["chunk_id"]: json.loads(row["vector"]) for row in cached_rows}
        except json.JSONDecodeError as exc:
            raise RagStorageError("知识库的语义缓存已损坏，请重新上传文档") from exc
        missing = [row for row in rows if row["id"] not in cached]
        if missing:
            vectors = self._encoder.encode_documents([row["text"] for row in missing])
            pairs = list(zip(missing, vectors, strict=True))
            cached = {**cached, **{row["id"]: vector for row, vector in pairs}}
            with self._connect() as connection:
                # A document can be deleted during encoding; do not revive it.
                for row, vector in pairs:
                    connection.execute(
                        """INSERT OR REPLACE INTO embeddings(chunk_id, model, vector)
                           SELECT c.id, ?, ? FROM chunks c JOIN documents d ON d.id = c.document_id
                           WHERE c.id = ? AND d.owner = ?""",
                        (self._encoder.identity, json.dumps(vector), row["id"], owner),
                    )
        query_vector = self._encoder.encode_query(query)
        from .rag_semantic import fuse_scores

        return fuse_scores(query_vector, [cached[row["id"]] for row in rows], lexical_scores)

    @staticmethod
    def _document_dict(row) -> dict:
        return {key: row[key] for key in ("id", "name", "size", "chunks", "created_at")}

    @staticmethod
    def _validate_owner(owner: str) -> None:
        if not isinstance(owner, str) or not owner.strip() or len(owner) > 256:
            raise ValueError("知识库用户标识无效")
