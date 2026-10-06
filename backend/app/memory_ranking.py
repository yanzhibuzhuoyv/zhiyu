"""Rank bounded, already-scoped original turns; never summarize or merge memories.

Message timestamps guide retrieval proximity, not the date of an event in its text.
The index must enforce owner/conversation/before before its candidate LIMIT. Optional
scope metadata here additionally rejects known foreign rows, not an unscoped index.
"""

import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import UTC, datetime, timedelta

from .memory_priority import source_factor
from .vendor_rag.bm25 import BM25Index, _tokenize


@dataclass(frozen=True)
class RankingWeights:
    bm25_fraction: float = 0.20
    assistant_weight: float = 0.65
    recency_bonus: float = 0.05
    event_weight: float = 0.65
    event_window_hours: float = 12

    def validate(self):
        if not (0 <= self.bm25_fraction <= 1 and 0 <= self.assistant_weight <= 1
                and 0 <= self.recency_bonus <= 1 and 0 <= self.event_weight <= 1
                and 0 < self.event_window_hours <= 48):
            raise ValueError("记忆检索权重不合法")


DEFAULT_WEIGHTS = RankingWeights()
STRONG_USER_COVERAGE = 0.60
MAX_EVENT_ANCHORS = 2
MAX_EVENT_PAIRS = 1
EVENT_TERMS = tuple(dict.fromkeys(term for term in _tokenize(
    "生日 纪念 庆祝 今天 昨天 前天 两天前 那天 当天 当时 那晚 前晚 刚刚 "
    "birthday anniversary celebrate celebrated celebrating celebration today yesterday"
) if len(term) > 1))
_TIME_CUE = re.compile(r"两天前|前天|昨天|今天|那天|当天|当时|那晚|前晚|刚刚|\byesterday\b|\btoday\b|that (?:day|evening)", re.I)
_FIRST_PERSON = re.compile(r"我|\b(?:i|we|my|our)\b", re.I)
_AI_SELF = re.compile(r"我.{0,8}(?:AI|人工智能|机器人)|(?:AI|人工智能|机器人).{0,8}我|\b(?:AI|robot)\b", re.I)
_EVENT = re.compile(r"生日|纪念|庆祝|\bbirthday\b|\banniversary\b|\bcelebrat\w*", re.I)
_EPISODE = re.compile(r"今天|昨天|前天|两天前|那晚|前晚|当天|那天|当时|刚刚|过生日|生日聚会|生日晚餐|庆祝|\btoday\b|\byesterday\b|that (?:day|evening)|\bcelebrated\b|birthday (?:party|dinner)", re.I)
_NOT_EPISODE = re.compile(r"明天|后天|下周|下个|明年|以后|到时候|准备|计划|打算|假如|假设|如果|假装|\b(?:tomorrow|next|future|if|imagine|pretend)\b", re.I)
_OTHER_SUBJECT = re.compile(r"(?:朋友|同事|同学|邻居|明星|妈妈|爸爸|母亲|父亲|姐姐|哥哥|妹妹|弟弟|公司|电影|角色|机器人|AI|人工智能).{0,8}(?:生日|纪念)|(?:你|它)(?:的|今天|昨天|前天|也|是|过){0,3}(?:生日|纪念)|(?:friend|colleague|mother|father|sister|brother|company|character|robot|AI|your|its).{0,12}(?:birthday|anniversary)", re.I)
_CORRECTION = re.compile(r"更正|纠正|记错|改成|改为|不对|不是.{0,24}而是|\b(?:correction|corrected|actually|instead|updated)\b|no longer", re.I)
_INTENT = frozenset("想吃 想喝 想买 想看 想听 想去 想玩 想读 想做 想学 想要".split())


@dataclass(frozen=True)
class RankedTurn:
    turn: Mapping
    score: float
    direct_score: float
    user_coverage: float
    matched_roles: tuple[str, ...]
    reason: str = "direct"
    anchor_ids: tuple[str, ...] = ()
    relation_confirmed: bool = False


@dataclass(frozen=True)
class EventWindow:
    anchor_id: str
    anchor_sequence: int
    anchor_time: datetime
    start: datetime
    end: datetime


def _instant(value) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed.astimezone(UTC) if parsed.tzinfo is not None else None
    except (ValueError, TypeError):
        return None


def _now(value: datetime | None) -> datetime:
    current = value or datetime.now(UTC)
    if current.tzinfo is None:
        raise ValueError("记忆检索当前时间必须包含时区")
    return current.astimezone(UTC)


def _scope_matches(turn: Mapping, scope: tuple[str, str] | None) -> bool:
    if scope is None:
        return True
    owner, conversation = scope
    return (("owner" not in turn or turn["owner"] == owner)
            and ("conversation_id" not in turn or turn["conversation_id"] == conversation))


def _unique(turns: Sequence[Mapping], scope: tuple[str, str] | None) -> list[Mapping]:
    result = {}
    for turn in turns:
        if _scope_matches(turn, scope):
            result.setdefault(turn["sequence"], turn)
    return list(result.values())


def _coverage(text: str, terms: frozenset[str]) -> float:
    return len(terms.intersection(_tokenize(text))) / len(terms) if terms else 0


def _query_terms(terms: Sequence[str]) -> frozenset[str]:
    """Remove generic desire/action bigrams and their boundary to the following noun.

    Applies equally to food, books, music etc.; does not parse or rewrite saved facts.
    """
    ignored = set()
    for index, term in enumerate(terms):
        if term in _INTENT:
            ignored.add(term)
            if index + 1 < len(terms) and terms[index + 1].startswith(term[-1]):
                ignored.add(terms[index + 1])
    return frozenset(term for term in terms if term not in ignored)


def _role_score(coverage: float, bm25: float, peak: float, weights: RankingWeights) -> float:
    if not coverage:
        return 0
    density = bm25 / peak if peak else 0
    return coverage * (1 - weights.bm25_fraction) + density * weights.bm25_fraction


def _time_bonus(turn: Mapping, roles: tuple[str, ...], now: datetime, cap: float) -> float:
    times = [_instant(turn[f"{role}_created_at"]) for role in roles]
    ages = [(now - time).total_seconds() / 86400 for time in times if time and time <= now]
    # Saturating bonus only: even an 800-day-old exact fact retains full direct relevance.
    return cap / (1 + min(ages) / 30) if ages else 0


def rank_direct(candidates: Sequence[Mapping], terms: Sequence[str], *, now: datetime | None = None,
                weights: RankingWeights = DEFAULT_WEIGHTS,
                scope: tuple[str, str] | None = None) -> list[RankedTurn]:
    weights.validate()
    current, query = _now(now), _query_terms(terms)
    turns = _unique(candidates, scope)
    if not query or not turns:
        return []
    documents = [turn[f"{role}_text"] for turn in turns for role in ("user", "assistant")]
    scores = BM25Index(documents).scores(" ".join(query))
    peak = max(scores, default=0)
    ranked = []
    for index, turn in enumerate(turns):
        user = _coverage(turn["user_text"], query)
        assistant = _coverage(turn["assistant_text"], query)
        roles = tuple(role for role, coverage in (("user", user), ("assistant", assistant)) if coverage)
        direct = max(_role_score(user, scores[index * 2], peak, weights) * source_factor(turn, "user", query),
                     _role_score(assistant, scores[index * 2 + 1], peak, weights)
                     * weights.assistant_weight * source_factor(turn, "assistant", query))
        if direct > 0:
            ranked.append(RankedTurn(turn, direct + _time_bonus(turn, roles, current, weights.recency_bonus), direct, user, roles))
    return sorted(ranked, key=lambda item: (item.score, item.turn["sequence"]), reverse=True)


def event_windows(ranked: Sequence[RankedTurn], *, weights: RankingWeights = DEFAULT_WEIGHTS) -> list[EventWindow]:
    weights.validate()
    if not weights.event_weight:
        return []
    windows = []
    radius = timedelta(hours=weights.event_window_hours)
    for item in ranked:
        time = _instant(item.turn["user_created_at"])
        if item.user_coverage < STRONG_USER_COVERAGE or time is None:
            continue
        windows.append(EventWindow(item.turn["user_id"], item.turn["sequence"], time, time - radius, time + radius))
        if len(windows) >= MAX_EVENT_ANCHORS:
            break
    return windows


def _time_cues(text: str) -> set[str]:
    aliases = {"两天前": "前天", "那天": "那次", "当天": "那次", "当时": "那次",
               "那晚": "那次", "that day": "那次", "that evening": "那次",
               "yesterday": "昨天", "today": "今天"}
    return {aliases.get(cue.lower(), cue.lower()) for cue in _TIME_CUE.findall(text)}


def _event_supported(text: str, anchor_text: str) -> bool:
    # Conservative lexical episode filter, not a calendar/fact parser. No date is inferred.
    if _NOT_EPISODE.search(text) or _OTHER_SUBJECT.search(text) or _AI_SELF.search(text):
        return False
    labeled_episode = _EVENT.search(text) and _EPISODE.search(text)
    # Shared temporal wording supports arbitrary topics as tentative context. It
    # does not resolve a calendar date or establish that these are the same event.
    shared_time = _FIRST_PERSON.search(text) and _time_cues(text).intersection(_time_cues(anchor_text))
    return bool(labeled_episode or shared_time)


def _event_ranked(events: Sequence[Mapping], direct: Sequence[RankedTurn],
                  weights: RankingWeights, scope) -> list[RankedTurn]:
    windows = event_windows(direct, weights=weights)
    anchors = {item.turn["user_id"]: item for item in direct}
    results = []
    proximity = {}
    for turn in _unique(events, scope):
        time = _instant(turn["user_created_at"])
        if time is None:
            continue
        nearby = [window for window in windows if window.start <= time <= window.end
                  and _event_supported(turn["user_text"], anchors[window.anchor_id].turn["user_text"])]
        if not nearby:
            continue
        closest = min(nearby, key=lambda window: abs((time - window.anchor_time).total_seconds()))
        anchor = anchors[closest.anchor_id]
        # Event relevance cannot exceed its originating direct hit; proximity only breaks ties.
        score = anchor.direct_score * weights.event_weight
        proximity[turn["sequence"]] = abs((time - closest.anchor_time).total_seconds())
        results.append(RankedTurn(turn, score, 0, 0, ("user",), "nearby_event", (closest.anchor_id,)))
    return sorted(results, key=lambda item: (item.score, -proximity[item.turn["sequence"]], item.turn["sequence"]), reverse=True)


def _fits(items: Sequence[RankedTurn], limit: int) -> bool:
    return sum(len(item.turn["user_text"]) + len(item.turn["assistant_text"]) for item in items) <= limit


def _reserve_user_context(ranked, chosen, max_turns, max_chars):
    users = [item for item in ranked if item.user_coverage]
    corrections = [item for item in ranked if item.user_coverage and _CORRECTION.search(item.turn["user_text"])]
    if not users:
        return chosen, set()
    latest = max(users, key=lambda item: item.turn["sequence"])
    reservations = [(latest, "latest_user_context")]
    if corrections and max_turns > 1:
        correction = max(corrections, key=lambda item: item.turn["sequence"])
        reservations.append((correction, "latest_correction"))
    protected = set()
    for item, reason in reservations:
        sequence = item.turn["sequence"]
        if any(entry.turn["sequence"] == sequence for entry in chosen):
            protected.add(sequence)
            continue
        proposal = [*chosen, replace(item, reason=reason)]
        if len(proposal) > max_turns:
            removable = [entry for entry in chosen if entry.turn["sequence"] not in protected]
            weakest = min(removable, key=lambda entry: entry.score, default=None)
            if weakest is None:
                continue
            proposal = [entry for entry in chosen if entry is not weakest] + [replace(item, reason=reason)]
        if _fits(proposal, max_chars):
            chosen = proposal
            protected.add(sequence)
    return chosen, protected


def _reserve_linked_corrections(candidates, chosen, scope, max_turns, max_chars, protected):
    anchors = {item.turn["user_id"] for item in chosen}
    linked = [turn for turn in _unique(candidates, scope) if anchors.intersection(turn.get("correction_of", ()))]
    for turn in sorted(linked, key=lambda item: item["sequence"], reverse=True):
        if any(item.turn["sequence"] == turn["sequence"] for item in chosen):
            continue
        anchor_ids = tuple(sorted(anchors.intersection(turn["correction_of"])))
        correction = RankedTurn(turn, max(item.score for item in chosen), 0, 0, ("user",),
                                "source_correction", anchor_ids, True)
        proposal = [*chosen, correction]
        if len(proposal) > max_turns:
            removable = [item for item in chosen if item.reason != "source_correction"
                         and item.turn["sequence"] not in protected
                         and item.turn["user_id"] not in anchor_ids]
            if not removable:
                removable = [item for item in chosen if item.turn["user_id"] in anchor_ids]
            weakest = min(removable, key=lambda item: item.score, default=None)
            if weakest is None:
                continue
            proposal = [item for item in chosen if item is not weakest] + [correction]
        if _fits(proposal, max_chars):
            chosen = proposal
    return chosen


def select_memory(candidates: Sequence[Mapping], terms: Sequence[str], *, nearby_events: Sequence[Mapping] = (),
                  now: datetime | None = None, scope: tuple[str, str] | None = None,
                  weights: RankingWeights = DEFAULT_WEIGHTS, max_turns: int = 4,
                  max_chars: int = 6144) -> list[RankedTurn]:
    """Select complete original pairs; relation metadata expresses uncertainty, never new facts."""
    if max_turns < 1 or max_chars < 1:
        return []
    ranked = rank_direct(candidates, terms, now=now, weights=weights, scope=scope)
    chosen = []
    for item in ranked:
        if len(chosen) < max_turns and _fits([*chosen, item], max_chars):
            chosen.append(item)
    chosen, protected_sequences = _reserve_user_context(ranked, chosen, max_turns, max_chars)
    added_events = 0
    for event in _event_ranked(nearby_events, ranked, weights, scope):
        if added_events >= MAX_EVENT_PAIRS:
            break
        if any(item.turn["sequence"] == event.turn["sequence"] for item in chosen):
            continue
        if not any(item.turn["user_id"] in event.anchor_ids for item in chosen):
            continue
        removable = [item for item in chosen if item.turn["user_id"] not in event.anchor_ids
                     and item.turn["sequence"] not in protected_sequences]
        proposal = [*chosen, event]
        if len(proposal) > max_turns:
            weakest = min(removable, key=lambda item: item.score, default=None)
            if weakest is None or weakest.score >= event.score:
                continue
            proposal = [item for item in chosen if item is not weakest] + [event]
        if _fits(proposal, max_chars):
            chosen = proposal
            added_events += 1
    chosen = _reserve_linked_corrections(candidates, chosen, scope, max_turns, max_chars, protected_sequences)
    return sorted(chosen, key=lambda item: item.turn["sequence"])
