"""Bounded, exact annotation links; no cross-topic or cross-owner anchors."""

_PATH = """WITH RECURSIVE path AS (
    SELECT id origin_id,id,message_sequence,conversation_id,kind,topic_key,subject,validity
    FROM memory_annotations WHERE message_sequence=? AND validity='superseded'
    UNION
    SELECT older.origin_id,newer.id,newer.message_sequence,newer.conversation_id,
        newer.kind,newer.topic_key,newer.subject,newer.validity
    FROM path older JOIN memory_annotation_links l ON l.older_id=older.id
    JOIN memory_annotations newer ON newer.id=l.newer_id
    WHERE l.relation='supersedes' AND newer.conversation_id=older.conversation_id
      AND newer.kind=older.kind AND newer.topic_key=older.topic_key
      AND newer.subject=older.subject LIMIT 1001)
    SELECT DISTINCT p.origin_id,m.sequence target_sequence,a.char_start span_start
    FROM path p JOIN messages m ON m.sequence=p.message_sequence
    JOIN memory_annotations a ON a.id=p.id
    WHERE p.validity='current' AND m.conversation_id=p.conversation_id
    ORDER BY m.sequence,p.origin_id,a.char_start LIMIT 512"""


def details(db, sequences, header):
    """Caller has authorized sources; joins keep every traversed link in scope."""
    targets, result = set(), []
    for sequence in dict.fromkeys(sequences):
        header(db, sequence)
        for row in db.execute(_PATH, (sequence,)):
            target = row["target_sequence"]
            if target not in targets and len(targets) >= 64:
                continue
            targets.add(target)
            result.append({**dict(row), "source_sequence": sequence})
            if len(result) >= 1024:
                return result
    return result
