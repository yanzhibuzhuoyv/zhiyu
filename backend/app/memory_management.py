"""Authorized source metadata and reversible index maintenance, no text rewriting."""

import sqlite3
from datetime import UTC, datetime

from .body_codec import decode_body
from .memory_cold_search import MAX_SOLO_CHARS
from .memory_restore import restore_protected
from .memory_transaction import transaction
from .source_codec import ChatStorageError


class MemoryMissing(ValueError):
    pass


class MemoryConflict(ValueError):
    pass


class MemoryManagement:
    def __init__(self, chat):
        self.chat = chat

    @staticmethod
    def _source(db, owner, identifier, message_id):
        row = db.execute("""SELECT m.sequence,m.id,m.role,m.created_at FROM messages m
            JOIN conversations c ON c.id=m.conversation_id
            WHERE c.owner=? AND c.id=? AND m.id=?""", (owner, identifier, message_id)).fetchone()
        if row is None:
            raise MemoryMissing("原文不存在")
        return row

    def _render(self, db, row):
        original = decode_body(db.execute("SELECT content FROM messages WHERE sequence=?", (row["sequence"],)).fetchone()[0])
        meta = self.chat.annotations.metadata(db, [row["sequence"]], datetime.now(UTC))[row["sequence"]]
        state = self.chat.cold.get_state(db, row["sequence"])
        recognition = db.execute("SELECT mode,status FROM memory_recognition WHERE sequence=?", (row["sequence"],)).fetchone()
        allowed = {"id", "kind", "subject", "polarity", "validity", "tier", "pinned",
                   "span_start", "span_end", "date_basis", "event_date", "promise_state", "revision",
                   "confidence", "classification_version"}
        return {"message_id": row["id"], "role": row["role"], "created_at": row["created_at"],
                "text": original[:768], "char_length": len(original), "tier": meta["tier"],
                "manual_tier": meta.get("manual_tier"), "pinned": meta["pinned"],
                "reason": meta["reason"], "can_confirm_independent": meta.get("can_confirm_independent", False),
                "recognition_mode": recognition["mode"] if recognition else "none",
                "recognition_status": recognition["status"] if recognition else "unavailable",
                "annotations": [{key: value for key, value in item.items() if key in allowed}
                                for item in meta["annotations"]], **state}

    def page(self, owner, identifier, limit, before=None):
        with transaction(self.chat) as db:
            if db.execute("SELECT 1 FROM conversations WHERE owner=? AND id=?", (owner, identifier)).fetchone() is None:
                raise MemoryMissing("会话不存在")
            sequence = self._source(db, owner, identifier, before)["sequence"] if before else None
            boundary = " AND m.sequence<?" if sequence is not None else ""
            parameters = (owner, identifier, sequence, limit + 1) if sequence else (owner, identifier, limit + 1)
            rows = db.execute("""SELECT m.sequence,m.id,m.role,m.created_at FROM messages m
                JOIN conversations c ON c.id=m.conversation_id WHERE c.owner=? AND c.id=?"""
                              + boundary + " ORDER BY m.sequence DESC LIMIT ?", parameters).fetchall()
            selected = rows[:limit]
            return {"messages": [self._render(db, row) for row in selected], "has_more": len(rows) > limit,
                    "next_before": selected[-1]["id"] if len(rows) > limit else None,
                    "automatic_annotations": self.chat.auto_annotations}

    def get(self, owner, identifier, message_id):
        with transaction(self.chat) as db:
            return self._render(db, self._source(db, owner, identifier, message_id))

    def update(self, owner, identifier, message_id, edit):
        try:
            with self.chat.connection() as db:
                db.execute("BEGIN IMMEDIATE")
                row = self._source(db, owner, identifier, message_id)
                sequence = row["sequence"]
                state = self.chat.cold.get_state(db, sequence)
                if state["revision"] != edit.expected_revision:
                    raise MemoryConflict("记忆设置已更新，请刷新后重试")
                if edit.index_state == "cold" and self.chat.cold.pending_turn(db, sequence, identifier):
                    raise MemoryConflict("原文仍在等待识别，需要保留活跃检索入口")
                if edit.tier is not None:
                    self.chat.annotations.set_manual(db, sequence, edit.tier)
                if edit.confirm_independent:
                    try:
                        self.chat.annotations.confirm_episode(db, sequence)
                    except ValueError as error:
                        raise MemoryConflict("这条原文暂不支持确认独立表达") from error
                restore_protected(self.chat, db, identifier)
                meta = self.chat.annotations.metadata(db, [sequence], datetime.now(UTC))[sequence]
                requested = edit.index_state or ("active" if meta["tier"] in {"long", "permanent"} or meta["pinned"] else None)
                if requested == "cold" and (meta["tier"] in {"long", "permanent"} or meta["pinned"]):
                    raise MemoryConflict("长期、永久或未完成承诺的原文需要保留检索入口，请先调整记忆设置")
                if requested is not None:
                    original = decode_body(db.execute("SELECT content FROM messages WHERE sequence=?", (sequence,)).fetchone()[0])
                    if requested == "cold":
                        first = sequence - int(row["role"] == "assistant")
                        partner = db.execute("SELECT content FROM messages WHERE conversation_id=? AND sequence=?",
                                             (identifier, first + int(row["role"] == "user"))).fetchone()
                        if partner is None or len(original.encode("utf-8")) + len(decode_body(partner[0]).encode("utf-8")) > MAX_SOLO_CHARS:
                            raise MemoryConflict("原文过长，需要保留活跃检索入口")
                    method = self.chat.cold.cool if requested == "cold" else self.chat.cold.warm
                    method(db, sequence, identifier, original, self.chat.memory)
                if self.chat.cold.get_state(db, sequence)["revision"] == state["revision"]:
                    self.chat.cold.bump_revision(db, sequence)
                result = self._render(db, row)
            return result
        except sqlite3.Error as error:
            raise ChatStorageError("聊天记忆设置暂时无法保存") from error

    def search(self, owner, identifier, terms, cursor=None):
        with transaction(self.chat) as db:
            if db.execute("SELECT 1 FROM conversations WHERE owner=? AND id=?", (owner, identifier)).fetchone() is None:
                raise MemoryMissing("会话不存在")
            before = db.execute("SELECT coalesce(max(sequence),0)+1 FROM messages WHERE conversation_id=?", (identifier,)).fetchone()[0]
            if cursor is not None and cursor > before:
                raise MemoryConflict("归档检索游标已失效，请重新检索")
            result = self.chat.cold.search(db, owner, identifier, before, terms, cursor=cursor)
            if result.get("blocked_sequence") is not None:
                raise MemoryConflict("存在过长的归档原文，请先恢复它的活跃检索入口")
            messages = [{"message_id": turn[f"{role}_id"], "role": role,
                         "created_at": turn[f"{role}_created_at"], "text": turn[f"{role}_text"],
                         "char_start": turn.get(f"{role}_start", 0),
                         "char_end": turn.get(f"{role}_start", 0) + len(turn[f"{role}_text"])}
                        for turn in result["turns"] for role in ("user", "assistant")]
            return {"messages": messages, "complete": result["complete"], "next_cursor": result.get("next_before"),
                    "scanned": result["scanned"], "decoded": result["decoded"]}
