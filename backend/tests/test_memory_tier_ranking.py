from datetime import UTC, datetime

import pytest

from app.memory_ranking import RankingWeights, rank_direct, select_memory

NOW = datetime(2026, 10, 6, tzinfo=UTC)
WEIGHTS = RankingWeights(recency_bonus=0)


def turn(sequence=1, user="我想吃苹果。", assistant="我陪你。", **extra):
    return {"sequence": sequence, "user_id": f"u{sequence}", "assistant_id": f"a{sequence}",
            "user_text": user, "assistant_text": assistant, "user_start": 0, "assistant_start": 0,
            "user_length": len(user), "assistant_length": len(assistant),
            "user_created_at": NOW.isoformat(), "assistant_created_at": NOW.isoformat(),
            "owner": "owner", "conversation_id": "chat", **extra}


def metadata(tier, *, start=0, end=7, validity="current", pinned=False):
    return {"tier": tier, "override": None, "pinned": pinned,
            "annotations": [{"id": "source", "span_start": start, "span_end": end,
                             "tier": tier, "validity": validity, "pinned": pinned}]}


@pytest.mark.parametrize("tier,factor", [("mid", 1), ("long", 1.05), ("permanent", 1.1)])
def test_relevant_current_original_tier_changes_real_ranking_score(tier, factor):
    original = turn()
    base = rank_direct([original], ["苹果"], now=NOW, weights=WEIGHTS)[0]
    tagged = turn(user_memory=metadata(tier))
    result = rank_direct([tagged], ["苹果"], now=NOW, weights=WEIGHTS)[0]
    assert result.direct_score == pytest.approx(base.direct_score * factor)
    assert result.turn["user_text"] == original["user_text"]


@pytest.mark.parametrize("validity", ["superseded", "retracted", "uncertain"])
def test_invalidated_original_does_not_keep_permanent_boost(validity):
    original = turn()
    base = rank_direct([original], ["苹果"], now=NOW, weights=WEIGHTS)[0]
    tagged = turn(user_memory=metadata("permanent", validity=validity))
    result = rank_direct([tagged], ["苹果"], now=NOW, weights=WEIGHTS)[0]
    assert result.direct_score == base.direct_score


def test_unrelated_birthday_span_in_same_excerpt_cannot_boost_apple_query():
    text = "我的生日是十月六日。我想吃苹果。"
    base = rank_direct([turn(user=text)], ["苹果"], now=NOW, weights=WEIGHTS)[0]
    tagged = turn(user=text, user_memory=metadata("permanent", start=0, end=10))
    assert rank_direct([tagged], ["苹果"], now=NOW, weights=WEIGHTS)[0].direct_score == base.direct_score


def test_annotation_outside_returned_excerpt_cannot_boost_match():
    tagged = turn(user_start=100, user_memory=metadata("permanent", start=0, end=7))
    base = rank_direct([turn(user_start=100)], ["苹果"], now=NOW, weights=WEIGHTS)[0]
    assert rank_direct([tagged], ["苹果"], now=NOW, weights=WEIGHTS)[0].direct_score == base.direct_score


def test_assistant_metadata_never_amplifies_user_fact():
    tagged = turn(assistant_memory=metadata("permanent"))
    base = rank_direct([turn()], ["苹果"], now=NOW, weights=WEIGHTS)[0]
    assert rank_direct([tagged], ["苹果"], now=NOW, weights=WEIGHTS)[0].direct_score == base.direct_score


def test_promise_boost_remains_small_and_only_for_its_related_original_span():
    tagged = turn(user_memory=metadata("permanent", pinned=True))
    base = rank_direct([turn()], ["苹果"], now=NOW, weights=WEIGHTS)[0]
    assert rank_direct([tagged], ["苹果"], now=NOW, weights=WEIGHTS)[0].direct_score == pytest.approx(base.direct_score * 1.15)


def test_tier_never_makes_an_unrelated_source_eligible_or_replaces_latest_correction():
    old = turn(user_memory=metadata("permanent"))
    corrected = turn(3, "更正：我不喜欢苹果了。")
    unrelated = turn(5, "生日是十月六日。", user_memory=metadata("permanent"))
    selected = select_memory([old, corrected, unrelated], ["苹果"], now=NOW, max_turns=1)
    assert [item.turn["sequence"] for item in selected] == [3]


def test_manual_metadata_mapping_and_cold_penalty_are_used_by_actual_ranking():
    original = turn()
    base = rank_direct([original], ["苹果"], now=NOW, weights=WEIGHTS)[0]
    tagged = turn(user_memory={**metadata("permanent"), "manual_tier": "long",
                               "override": {"tier": "long"}, "source_current": True,
                               "ranking_tier": "long", "index_state": "cold"})
    assert rank_direct([tagged], ["苹果"], now=NOW, weights=WEIGHTS)[0].direct_score == pytest.approx(base.direct_score * 1.05 * 0.90)


def test_linked_date_correction_cannot_displace_latest_user_denial():
    candidates = [turn(1, "我的公历生日是03-06"),
                  turn(3, "更正：我的公历生日是03-07", correction_of=("u1",)),
                  turn(5, "我想读星空笔记"), turn(7, "我想喝红茶"),
                  turn(9, "更正：我现在不喜欢苹果了")]
    selected = select_memory(candidates, ["03", "06", "苹果", "星空", "红茶"], now=NOW)
    assert 9 in {item.turn["sequence"] for item in selected}
    assert 3 in {item.turn["sequence"] for item in selected}
