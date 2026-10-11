"""Bounded recall over this conversation's saved, complete earlier turns."""

import re
from dataclasses import dataclass
from datetime import UTC, datetime

from .assistant_memory import standalone_references
from .memory_contract import RetrievalState, completed_state
from .memory_ranking import RankedTurn, event_windows, rank_direct, select_memory
from .memory_transaction import transaction
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
            "char_start": turn.get(f"{role}_start", 0),
            "char_end": turn.get(f"{role}_start", 0) + len(turn[f"{role}_text"]),
            "memory_tier": turn.get(f"{role}_memory", {}).get("tier", "mid"),
            "source_current": turn.get(f"{role}_memory", {}).get("source_current", True),
            "created_at": turn[f"{role}_created_at"],
            "truncated": turn[f"{role}_length"] > len(turn[f"{role}_text"]),
            "score": round(float(item.score), 6),
            "reason": item.reason,
            "anchor_ids": list(item.anchor_ids),
            "relation_confirmed": item.relation_confirmed,
        }
        for role in ("user", "assistant")
    ]


@dataclass(frozen=True)
class MemoryContext:
    history: list
    references: list
    status: RetrievalState = RetrievalState.NOT_SEARCHED
    next_cursor: int | None = None

    def __post_init__(self):
        if not isinstance(self.status, RetrievalState):
            raise ValueError("invalid retrieval execution state")

    @property
    def complete(self) -> bool:
        """Deprecated legacy projection: skipped lookup must never be True."""
        return self.status == RetrievalState.OK


def conversation_context_result(chat, owner: str, identifier: str, query: str, *, recent_messages=None) -> MemoryContext:
    turns = chat.recent_turns(owner, identifier, RECENT_TURNS, RECENT_CHARS)
    history, before = recent_history(turns)
    if recent_messages is not None:
        history = [{"role": row["role"], "content": row["content"]} for row in recent_messages]
        with transaction(chat) as db:
            first = db.execute("""SELECT m.sequence FROM messages m JOIN conversations c ON c.id=m.conversation_id
                WHERE c.owner=? AND c.id=? AND m.id=?""", (owner, identifier, recent_messages[0]["id"])).fetchone() if recent_messages else None
        before = first[0] if first else chat.message_position(owner, identifier) + 1
    terms = memory_terms(query)
    if not before or not terms:
        return MemoryContext(history, [], RetrievalState.NOT_SEARCHED)
    result = chat.memory_lookup(
        owner, identifier, before, terms, CANDIDATE_TURNS, EXCERPT_CHARS
    )
    candidates = result["turns"]
    if not candidates:
        solo = standalone_references(chat, owner, identifier, before, terms)
        return MemoryContext(history, solo["references"], completed_state(result["complete"], solo["complete"]), result.get("next_before"))
    now, scope = datetime.now(UTC), (owner, identifier)
    windows = event_windows(rank_direct(candidates, terms, now=now, scope=scope))
    event_result = chat.memory_events(owner, identifier, before, windows) if windows else {"turns": [], "complete": True}
    chosen = select_memory(candidates, terms, nearby_events=event_result["turns"], now=now, scope=scope,
                           max_turns=MEMORY_TURNS, max_chars=MEMORY_CHARS)
    refs = [ref for item in chosen for ref in _references(item)]
    solo = standalone_references(chat, owner, identifier, before, terms,
                                 max_chars=MEMORY_CHARS - sum(len(ref["text"]) for ref in refs))
    refs += solo["references"]
    assert sum(len(ref["text"]) for ref in refs) <= MEMORY_CHARS
    return MemoryContext(history, refs, completed_state(result["complete"], event_result["complete"], solo["complete"]), result.get("next_before"))


def conversation_context(chat, owner: str, identifier: str, query: str) -> tuple[list, list]:
    result = conversation_context_result(chat, owner, identifier, query)
    return result.history, result.references
