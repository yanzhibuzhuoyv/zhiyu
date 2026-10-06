"""Experimental policy for verified source annotations, without text extraction.

Callers must scope observations to one identity/conversation before evaluation.
The policy cannot verify hashes, span contents, birthdays or correction links;
those are upstream responsibilities. Days are UTC sampling buckets, not local
calendar dates or dates of the events mentioned in a message. No database write,
text merge, automatic cold-index removal or production ranking change occurs here.
"""

import math
import re
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta

POLICY_VERSION = "experimental-v1"
_KINDS = frozenset({"wish", "preference", "habit", "profile", "promise", "episodic"})
_STATES = frozenset({"current", "superseded", "retracted", "uncertain"})
_PROMISE_STATES = frozenset({"pending", "overdue", "fulfilled", "cancelled"})
_TIERS = frozenset({"mid", "long", "permanent"})


def _require_utc(value, label="记忆重放时间") -> datetime:
    if (not isinstance(value, datetime) or value.tzinfo is None
            or value.utcoffset() != timedelta(0)):
        raise ValueError(f"{label}必须包含明确 UTC 时区")
    return value


def _finite_number(value) -> bool:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def _member(value, choices) -> bool:
    return isinstance(value, str) and value in choices


@dataclass(frozen=True)
class Observation:
    id: str
    message_id: str
    role: str
    created_at: datetime
    subject: str
    kind: str
    polarity: str
    topic_key: str
    source_span: tuple[int, int]
    text_sha: str
    validity: str = "current"
    explicit_keep: bool = False
    promise_state: str | None = None
    # Assigned only after upstream verifies an independent episode, never per retry.
    episode_id: str | None = None

    def __post_init__(self):
        for value in (self.id, self.message_id, self.topic_key):
            if not isinstance(value, str) or not value or len(value) > 256:
                raise ValueError("原文标注标识无效")
        valid = (_member(self.role, {"user", "assistant"})
                 and _member(self.subject, {"user", "assistant", "third", "unknown"})
                 and _member(self.kind, _KINDS)
                 and _member(self.polarity, {"affirm", "deny", "quote", "hypothesis"})
                 and _member(self.validity, _STATES) and isinstance(self.explicit_keep, bool))
        if not valid:
            raise ValueError("原文标注类别或状态无效")
        _require_utc(self.created_at, "原文标注时间")
        span = self.source_span
        if (not isinstance(span, tuple) or len(span) != 2
                or any(not isinstance(part, int) or isinstance(part, bool) for part in span)
                or not 0 <= span[0] < span[1]):
            raise ValueError("原文标注字符范围无效")
        if not isinstance(self.text_sha, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", self.text_sha):
            raise ValueError("原文标注 SHA256 无效")
        if self.promise_state is not None and (self.kind != "promise"
                                               or not _member(self.promise_state, _PROMISE_STATES)):
            raise ValueError("原文标注承诺状态无效")
        if self.episode_id is not None and (
                not isinstance(self.episode_id, str) or not 1 <= len(self.episode_id) <= 256):
            raise ValueError("原文标注独立事件来源无效")


@dataclass(frozen=True)
class Decision:
    """Keep a bounded supporting sample of original IDs, not a full topic catalog."""

    observation_id: str
    tier: str
    independent_evidence_count: int
    reason: str
    pinned: bool = False
    evidence_ids: tuple[str, ...] = ()

    def __post_init__(self):
        count = self.independent_evidence_count
        if (not isinstance(self.observation_id, str) or not self.observation_id
                or not _member(self.tier, _TIERS)
                or not isinstance(count, int) or isinstance(count, bool) or count < 0
                or not isinstance(self.reason, str) or not self.reason
                or not isinstance(self.pinned, bool) or not isinstance(self.evidence_ids, tuple)
                or any(not isinstance(identifier, str) or not identifier for identifier in self.evidence_ids)):
            raise ValueError("记忆策略决策元数据无效")


def _unique(observations: Sequence[Observation]) -> list[Observation]:
    if not isinstance(observations, Sequence) or isinstance(observations, (str, bytes)):
        raise ValueError("原文标注输入容器无效")
    result = {}
    for item in observations:
        if not isinstance(item, Observation):
            raise ValueError("原文标注输入类型无效")
        existing = result.get(item.id)
        if existing is not None and existing != item:
            raise ValueError("原文标注 ID 存在冲突")
        result[item.id] = item
    return sorted(result.values(), key=lambda item: (item.created_at, item.id))


def _user_evidence(item: Observation, now: datetime) -> bool:
    return (item.created_at <= now and item.validity == "current" and item.polarity == "affirm"
            and item.role == "user" and item.subject == "user")


def _independent(items: Sequence[Observation]) -> tuple[Observation, ...]:
    """Conservatively count earliest unique UTC days, messages and source hashes."""
    chosen, days, messages, hashes, episodes = [], set(), set(), set(), set()
    verified_hashes = {item.text_sha.lower() for item in items if item.episode_id is not None}
    for item in items:
        # Unknown provenance cannot be an extra episode of an already verified text.
        if item.episode_id is None and item.text_sha.lower() in verified_hashes:
            continue
        day = item.created_at.date()
        checksum = (item.text_sha.lower(), item.episode_id)
        if (day in days or item.message_id in messages or checksum in hashes
                or item.episode_id is not None and item.episode_id in episodes):
            continue
        chosen.append(item)
        days.add(day)
        messages.add(item.message_id)
        hashes.add(checksum)
        if item.episode_id is not None:
            episodes.add(item.episode_id)
    return tuple(chosen)


@dataclass(frozen=True)
class _Evidence:
    count: int = 0
    elapsed_days: float = 0
    sample_ids: tuple[str, ...] = ()


def _summarize(group: Sequence[Observation], sample_limit: int) -> _Evidence:
    chosen = _independent(group)
    if not chosen:
        return _Evidence()
    sampled = chosen if len(chosen) <= sample_limit else (*chosen[:sample_limit - 1], chosen[-1])
    elapsed = (chosen[-1].created_at - chosen[0].created_at).total_seconds() / 86400
    return _Evidence(len(chosen), elapsed, tuple(entry.id for entry in sampled))


def _groups(items: Sequence[Observation], now: datetime, sample_limit: int) -> dict:
    groups = {}
    for item in items:
        if _user_evidence(item, now):
            groups.setdefault((item.kind, item.topic_key), []).append(item)
    return {key: _summarize(group, sample_limit) for key, group in groups.items()}


def _pinned(item: Observation, now: datetime) -> bool:
    return (item.created_at <= now and item.validity == "current" and item.polarity == "affirm"
            and item.kind == "promise" and item.subject in {"user", "assistant"}
            and item.promise_state in {"pending", "overdue"})


def _ineligible_reason(item: Observation, now: datetime) -> str | None:
    if item.created_at > now:
        return "not_observed_yet"
    if item.validity != "current":
        return "not_current"
    if item.polarity != "affirm":
        return "not_affirmative"
    if item.role != "user" or item.subject != "user":
        return "not_user_assertion"
    return None


@dataclass(frozen=True)
class Policy:
    min_independent_evidence: int = 3
    min_elapsed_days: float = 6
    long_bonus: float = 0.05
    permanent_bonus: float = 0.10
    promise_bonus: float = 0.05

    def __post_init__(self):
        count = self.min_independent_evidence
        if not isinstance(count, int) or isinstance(count, bool) or not 3 <= count <= 1000:
            raise ValueError("记忆策略独立证据门槛无效")
        if not _finite_number(self.min_elapsed_days) or self.min_elapsed_days <= 0:
            raise ValueError("记忆策略经过时间门槛无效")
        if (not all(_finite_number(value) for value in (
                self.long_bonus, self.permanent_bonus, self.promise_bonus))
                or not 0 <= self.long_bonus <= self.permanent_bonus <= 0.10
                or not 0 <= self.promise_bonus <= 0.05):
            raise ValueError("记忆策略检索加权无效")

    def _decision(self, item: Observation, group: _Evidence, now: datetime) -> Decision:
        pinned = _pinned(item, now)
        reason = _ineligible_reason(item, now)
        if reason:
            return Decision(item.id, "mid", 0, reason, pinned)
        evidence = group.sample_ids
        if item.explicit_keep:
            return Decision(item.id, "permanent", group.count, "explicit_user_keep", pinned, evidence)
        if item.kind == "profile":
            return Decision(item.id, "permanent", group.count, "verified_user_profile", pinned, evidence)
        promoted = (item.kind in {"wish", "preference", "habit"}
                    and group.count >= self.min_independent_evidence
                    and group.elapsed_days >= self.min_elapsed_days)
        reason = "repeated_topic_attention" if item.kind == "wish" else "repeated_user_evidence"
        return Decision(item.id, "long" if promoted else "mid", group.count,
                        reason if promoted else "insufficient_or_nonstable_evidence", pinned, evidence)

    def evaluate(self, observations: Sequence[Observation], now: datetime) -> dict[str, Decision]:
        """Replay supplied validity annotations without classifying or rewriting their sources."""
        current = _require_utc(now)
        items = _unique(observations)
        groups = _groups(items, current, self.min_independent_evidence)
        return {item.id: self._decision(item, groups.get((item.kind, item.topic_key), _Evidence()), current)
                for item in items}

    def priority_factor(self, decision: Decision, *, relevance: float) -> float:
        """Multiply an existing relevant score by at most 1.15; this cannot reserve a slot."""
        if not _finite_number(relevance) or relevance < 0:
            raise ValueError("记忆查询相关性无效")
        if not isinstance(decision, Decision) or decision.tier not in _TIERS:
            raise ValueError("记忆策略决策无效")
        if not relevance:
            return 1.0
        bonus = {"mid": 0.0, "long": self.long_bonus, "permanent": self.permanent_bonus}[decision.tier]
        return 1.0 + bonus + (self.promise_bonus if decision.pinned else 0.0)
