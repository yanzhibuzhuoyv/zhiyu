"""Small tier bonuses apply only to a relevant, current original source span."""

from collections.abc import Mapping

from .memory_lifecycle import Decision, Policy
from .vendor_rag.bm25 import _tokenize

_POLICY = Policy()
_TIERS = frozenset({"mid", "long", "permanent"})


def source_factor(turn: Mapping, role: str, query: frozenset[str]) -> float:
    meta = turn.get(f"{role}_memory")
    if not isinstance(meta, Mapping) or not query:
        return 1.0
    text = turn[f"{role}_text"]
    if not query.intersection(_tokenize(text)):
        return 1.0
    cold_factor = 0.90 if meta.get("index_state") == "cold" else 1.0
    override = meta.get("manual_tier")
    if isinstance(override, str) and override in _TIERS and meta.get("source_current", True):
        decision = Decision("manual", meta.get("ranking_tier", override), 0, "manual_source",
                            pinned=meta.get("pinned", False))
        return _POLICY.priority_factor(decision, relevance=1) * cold_factor
    start = turn.get(f"{role}_start", 0)
    factors = [1.0]
    for annotation in meta.get("annotations", ()):
        if annotation.get("validity") != "current" or annotation.get("tier") not in _TIERS:
            continue
        lower, upper = annotation["span_start"], annotation["span_end"]
        overlap = text[max(0, lower - start):max(0, min(len(text), upper - start))]
        if not overlap or not query.intersection(_tokenize(overlap)):
            continue
        decision = Decision(annotation["id"], annotation["tier"], 0, "verified_original_span",
                            pinned=annotation.get("pinned", False))
        factors.append(_POLICY.priority_factor(decision, relevance=1))
    return max(factors) * cold_factor
