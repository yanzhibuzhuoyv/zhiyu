"""One decoded-original contract for bounded recent turns and RAG excerpts."""

from .body_codec import decode_body
from .source_codec import ChatStorageError


def decoded_turn(db, row, excerpt_chars: int) -> dict:
    values = dict(row)
    result = {key: value for key, value in values.items()
              if key not in {"user_body", "assistant_body"}}
    for role in ("user", "assistant"):
        sequence = values["sequence"] + int(role == "assistant")
        body = db.execute("SELECT content FROM messages WHERE sequence=?", (sequence,)).fetchone()
        if body is None:
            raise ChatStorageError("聊天原文不存在")
        original = decode_body(body[0])
        del body
        start = values.get(f"{role}_start", 0)
        if type(start) is not int or not 0 <= start <= len(original):
            raise ChatStorageError("聊天原文位置无效")
        result = {**result, f"{role}_start": start, f"{role}_text": original[start:start + excerpt_chars],
                  f"{role}_length": len(original)}
        del original
    return result
