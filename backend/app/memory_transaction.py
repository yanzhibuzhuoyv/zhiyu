"""Translate SQLite failures, including context entry and final commit failures."""

import sqlite3
from contextlib import contextmanager

from .source_codec import ChatStorageError


@contextmanager
def transaction(chat, *, write=False):
    try:
        with chat.connection() as db:
            db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            yield db
    except sqlite3.Error as error:
        raise ChatStorageError("聊天记忆暂时无法读写") from error
