"""Bounded recall over this conversation's saved, complete earlier turns."""

import re
from datetime import UTC, datetime

from .memory_ranking import RankedTurn, event_windows, rank_direct, select_memory
from .vendor_rag.bm25 import _tokenize

RECENT_TURNS = 20
RECENT_CHARS = 48000
CANDIDATE_TURNS = 256
EXCERPT_CHARS = 768
MEMORY_TURNS = 4
MEMORY_CHARS = 6144
QUERY_TERMS = 32

_GENERIC_CHINESE = re.compile(
    "你还记得|记不记得|你记得|还记得|记得|以前|之前|曾经|告诉过|说过|"
    "什么|怎样|如何|怎么|那个|我们|你们|他们|她们|[的了吗呢呀啊我你他她还最是]"
)
_GENERIC_ASCII = frozenset(
    "we you me my your our ours i a an the is are was were what which how where "
    "do did does at in to of and or remember recall earlier before tell told".split()
)


def memory_terms(query: str) -> list[str]:
    """Require a meaningful word/bigram; generic recall alone cannot select facts."""
    cleaned = _GENERIC_CHINESE.sub(" ", query[:16000])
    terms = dict.fromkeys(
        term for term in _tokenize(cleaned) if len(term) > 1 and term not in _GENERIC_ASCII
    )
    return list(terms)[:QUERY_TERMS]


def recent_history(turns: list[dict]) -> tuple[list[dict], int]:
    """Select complete newest turns within the direct-context character budget."""
    selected, budget = [], RECENT_CHARS
    before = turns[0]["sequence"] + 2 if turns else 0
    for turn in turns:
        length = turn["user_length"] + turn["assistant_length"]
        if length > budget:
            break
        selected.append(turn)
        budget -= length
        before = turn["sequence"]
    history = [
        {"role": role, "content": turn[f"{role}_text"]}
        for turn in reversed(selected)
        for role in ("user", "assistant")
    ]
    return history, before


def _references(item: RankedTurn) -> list[dict]:
    turn = item.turn
    return [
        {
            "message_id": turn[f"{role}_id"],
            "turn_id": turn["user_id"],
            "role": role,
            "text": turn[f"{role}_text"],
            "created_at": turn[f"{role}_created_at"],
            "truncated": turn[f"{role}_length"] > len(turn[f"{role}_text"]),
            "score": round(float(item.score), 6),
            "reason": item.reason,
            "anchor_ids": list(item.anchor_ids),
            "relation_confirmed": item.relation_confirmed,
        }
        for role in ("user", "assistant")
    ]


def conversation_context(chat, owner: str, identifier: str, query: str) -> tuple[list, list]:
    turns = chat.recent_turns(owner, identifier, RECENT_TURNS, RECENT_CHARS)
    history, before = recent_history(turns)
    terms = memory_terms(query)
    if not before or not terms:
        return history, []
    candidates = chat.memory_candidates(
        owner, identifier, before, terms, CANDIDATE_TURNS, EXCERPT_CHARS
    )
    if not candidates:
        return history, []
    now, scope = datetime.now(UTC), (owner, identifier)
    windows = event_windows(rank_direct(candidates, terms, now=now, scope=scope))
    events = chat.memory_event_candidates(owner, identifier, before, windows) if windows else []
    chosen = select_memory(candidates, terms, nearby_events=events, now=now, scope=scope,
                           max_turns=MEMORY_TURNS, max_chars=MEMORY_CHARS)
    refs = [ref for item in chosen for ref in _references(item)]
    assert sum(len(ref["text"]) for ref in refs) <= MEMORY_CHARS
    return history, refs
