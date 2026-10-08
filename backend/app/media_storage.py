"""Separate SQLite transactions for media, independent of the original-text RAG."""

import sqlite3
from contextlib import contextmanager


class MediaStorageError(RuntimeError):
    pass


@contextmanager
def connection(path, *, write=False):
    try:
        db = sqlite3.connect(path, timeout=15)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA journal_mode=WAL")
        try:
            with db:
                if write:
                    db.execute("BEGIN IMMEDIATE")
                yield db
        finally:
            db.close()
    except sqlite3.Error as exc:
        raise MediaStorageError("素材数据库暂时无法读写") from exc
