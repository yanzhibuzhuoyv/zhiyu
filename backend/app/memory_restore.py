"""Restore originals that become protected after a topic strategy replay."""

from datetime import UTC, datetime

from .body_codec import decode_body


def restore_protected(chat, db, identifier):
    after = 0
    while True:
        rows = db.execute("""SELECT DISTINCT m.sequence FROM memory_index_state s
            JOIN messages m ON m.sequence=s.sequence
            LEFT JOIN memory_annotations a ON a.message_sequence=m.sequence AND a.validity='current'
            LEFT JOIN memory_manual_tiers t ON t.message_sequence=m.sequence
            WHERE m.conversation_id=? AND s.state='cold' AND m.sequence>?
              AND (t.tier IN ('long','permanent') OR a.pinned=1
                   OR (t.tier IS NULL AND a.tier IN ('long','permanent')))
            ORDER BY m.sequence LIMIT 128""", (identifier, after)).fetchall()
        if not rows:
            return
        sequences = [row["sequence"] for row in rows]
        metadata = chat.annotations.metadata(db, sequences, datetime.now(UTC))
        for sequence in sequences:
            meta = metadata[sequence]
            if meta["tier"] in {"long", "permanent"} or meta["pinned"]:
                original = decode_body(db.execute("SELECT content FROM messages WHERE sequence=?", (sequence,)).fetchone()[0])
                chat.cold.warm(db, sequence, identifier, original, chat.memory)
        after = sequences[-1]
