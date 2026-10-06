"""Combine active and reversible cold originals inside one authorized snapshot."""

from datetime import UTC, datetime

from .body_reads import decoded_turn
from .memory_transaction import transaction
from .vendor_rag.bm25 import _tokenize


def _combine(hot, cold, terms):
    """Keep the best actual original excerpt independently for each role."""
    grouped = {turn["sequence"]: turn for turn in hot}
    query = frozenset(terms)
    for turn in cold:
        previous = grouped.get(turn["sequence"])
        if previous is None:
            grouped[turn["sequence"]] = turn
            continue
        merged = dict(previous)
        for role in ("user", "assistant"):
            old_hits = len(query.intersection(_tokenize(previous[f"{role}_text"])))
            new_hits = len(query.intersection(_tokenize(turn[f"{role}_text"])))
            if new_hits > old_hits:
                merged.update({key: value for key, value in turn.items() if key.startswith(role + "_")})
        grouped[turn["sequence"]] = merged
    return list(grouped.values())


def _metadata(chat, db, turns, now):
    sequences = list(dict.fromkeys(sequence for turn in turns
                                   for sequence in (turn["sequence"], turn["sequence"] + 1)))
    metadata = chat.annotations.metadata(db, sequences, now)
    metadata = {seq: {**meta, **chat.cold.get_state(db, seq)} for seq, meta in metadata.items()}
    return [{**turn, "user_memory": metadata[turn["sequence"]],
             "assistant_memory": metadata[turn["sequence"] + 1]} for turn in turns]


def _correction_pairs(chat, db, owner, identifier, before, candidates, excerpt_chars, terms):
    query = frozenset(terms)
    old = {item["id"]: (turn["sequence"] + int(role == "assistant"), turn["user_id"])
           for turn in candidates for role in ("user", "assistant")
           for item in turn[f"{role}_memory"]["annotations"]
           if item["validity"] == "superseded" and query.intersection(_tokenize(
               turn[f"{role}_text"][max(0, item["span_start"] - turn[f"{role}_start"]):
                                     max(0, item["span_end"] - turn[f"{role}_start"])]))}
    links = chat.annotations.correction_details(db, list(dict.fromkeys(seq for seq, _ in old.values()))) if old else []
    links = [link for link in links if link["origin_id"] in old]
    if not links:
        return []
    targets = sorted({link["target_sequence"] for link in links})
    starts = {seq: min(link["span_start"] for link in links if link["target_sequence"] == seq) for seq in targets}
    placeholders = ",".join("?" for _ in targets)
    rows = db.execute("""SELECT DISTINCT u.sequence,u.id user_id,a.id assistant_id,
        0 user_start,0 assistant_start,u.created_at user_created_at,a.created_at assistant_created_at
        FROM messages m JOIN messages u ON u.sequence=m.sequence-(m.role='assistant') AND u.role='user'
          AND u.conversation_id=m.conversation_id
        JOIN messages a ON a.sequence=u.sequence+1 AND a.role='assistant' AND a.conversation_id=u.conversation_id
        JOIN conversations c ON c.id=u.conversation_id
        WHERE c.owner=? AND c.id=? AND a.sequence<? AND m.sequence IN (""" + placeholders + ") ORDER BY u.sequence",
                      (owner, identifier, before, *targets))
    return [{**decoded_turn(db, {**dict(row), "user_start": starts.get(row["sequence"], 0),
                                "assistant_start": starts.get(row["sequence"] + 1, 0)}, excerpt_chars),
             "owner": owner, "conversation_id": identifier,
             "correction_roles": tuple(role for role in ("user", "assistant")
                                       if row["sequence"] + int(role == "assistant") in starts),
             "correction_of": tuple(dict.fromkeys(old[link["origin_id"]][1] for link in links
                                      if link["target_sequence"] in (row["sequence"], row["sequence"] + 1)))}
            for row in rows]


def lookup(chat, owner, identifier, before, terms, limit, excerpt_chars):
    with transaction(chat) as db:
        hot = chat.memory.candidates(db, owner, identifier, before, terms, limit, excerpt_chars)
        cold = chat.cold.search(db, owner, identifier, before, terms, limit=limit, excerpt_chars=excerpt_chars)
        now = datetime.now(UTC)
        tagged = _metadata(chat, db, _combine(hot, cold["turns"], terms), now)
        corrected = _correction_pairs(chat, db, owner, identifier, before, tagged, excerpt_chars, terms)
        if corrected:
            additions = _metadata(chat, db, corrected, now)
            grouped = {turn["sequence"]: turn for turn in tagged}
            for turn in additions:
                existing = grouped.get(turn["sequence"])
                replacements = {key: value for key, value in turn.items()
                                if any(key.startswith(role + "_") for role in turn["correction_roles"])}
                grouped[turn["sequence"]] = {**(existing or turn), **replacements,
                                             "correction_of": turn["correction_of"]}
            tagged = list(grouped.values())
        return {**cold, "turns": sorted(tagged, key=lambda item: item["sequence"])}


def events(chat, owner, identifier, before, windows, limit=32, excerpt_chars=768):
    from .memory_ranking import EVENT_TERMS

    with transaction(chat) as db:
        hot = chat.memory.event_candidates(db, owner, identifier, before, windows, limit, excerpt_chars)
        cold = chat.cold.search(db, owner, identifier, before, list(EVENT_TERMS), limit=limit,
                                excerpt_chars=excerpt_chars, windows=windows)
        combined = _combine(hot, cold["turns"], EVENT_TERMS)
        return {**cold, "turns": _metadata(chat, db, combined, datetime.now(UTC))}
