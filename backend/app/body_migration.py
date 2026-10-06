"""Owner-scoped, bounded batches; preserves message identity and original FTS."""

from .body_codec import decode_body, encode_body
from .source_codec import ChatStorageError

DEFAULT_BATCH_ROWS = 128
DEFAULT_BATCH_BYTES = 8 * 1024 * 1024


def validate_batch(after, batch_rows, byte_limit, through=None):
    if type(after) is not int or after < 0:
        raise ValueError("迁移游标无效")
    if type(batch_rows) is not int or not 1 <= batch_rows <= 128:
        raise ValueError("迁移批量必须在1到128之间")
    if type(byte_limit) is not int or not 1024 <= byte_limit <= DEFAULT_BATCH_BYTES:
        raise ValueError("迁移字节预算无效")
    if through is not None and (type(through) is not int or through < after):
        raise ValueError("迁移范围无效")


def migrate_batch(db, identifier, after, batch_rows, byte_limit, through):
    # Metadata only: never materialize a batch of long canonical bodies.
    targets = db.execute("""SELECT sequence,length(CAST(content AS BLOB)) raw_bytes
        FROM messages WHERE conversation_id=? AND sequence>? AND sequence<=? AND typeof(content)='text'
        ORDER BY sequence LIMIT ?""", (identifier, after, through, batch_rows)).fetchall()
    report = {"after": after, "visited": 0, "converted": 0, "raw_bytes": 0,
              "stored_bytes": 0, "oversized_row": False}
    budget = 0
    for target in targets:
        estimate = 2 * target["raw_bytes"]
        if report["visited"] and budget + estimate > byte_limit:
            break
        original = db.execute("SELECT content FROM messages WHERE sequence=? AND conversation_id=?",
                              (target["sequence"], identifier)).fetchone()[0]
        encoded = encode_body(original)
        if decode_body(encoded) != original:
            raise ChatStorageError("正文编码往返验证失败")
        if isinstance(encoded, bytes):
            db.execute("UPDATE messages SET content=? WHERE sequence=? AND conversation_id=?",
                       (encoded, target["sequence"], identifier))
        stored_bytes = len(encoded) if isinstance(encoded, bytes) else len(encoded.encode("utf-8"))
        budget += target["raw_bytes"] + stored_bytes
        report = {"after": target["sequence"], "visited": report["visited"] + 1,
                  "converted": report["converted"] + int(isinstance(encoded, bytes)),
                  "raw_bytes": report["raw_bytes"] + target["raw_bytes"],
                  "stored_bytes": report["stored_bytes"] + stored_bytes,
                  "oversized_row": report["oversized_row"] or estimate > byte_limit}
        # Release this original and encoded payload before reading the next row.
        del original, encoded
    remaining = db.execute("""SELECT 1 FROM messages WHERE conversation_id=? AND sequence>?
        AND sequence<=? AND typeof(content)='text' LIMIT 1""", (identifier, report["after"], through)).fetchone()
    return {**report, "through": through, "complete": remaining is None}
