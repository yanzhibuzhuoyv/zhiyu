from dataclasses import replace
from datetime import timedelta

import pytest
from memory_ranking_cases import NOW, cases, turn

from app.conversation_memory import memory_terms
from app.memory_ranking import (
    DEFAULT_WEIGHTS,
    RankingWeights,
    event_windows,
    rank_direct,
    select_memory,
)


@pytest.mark.parametrize("case", list(cases()), ids=lambda case: case.name)
def test_independent_originals_rank_without_cross_scope_or_date_assertions(case):
    selected = select_memory(case.candidates, memory_terms(case.query), nearby_events=case.events,
                             now=NOW, scope=("owner", "chat"))
    ids = {item.turn["sequence"] for item in selected}
    assert case.expected <= ids
    assert not case.forbidden & ids
    originals = {item["sequence"]: item for item in (*case.candidates, *case.events)}
    assert all(item.turn == originals[item.turn["sequence"]] for item in selected)
    assert len(selected) <= 4
    assert [item.turn["sequence"] for item in selected] == sorted(ids)
    for item in selected:
        if item.reason == "nearby_event":
            assert item.anchor_ids and item.relation_confirmed is False
            assert item.turn["user_created_at"] == originals[item.turn["sequence"]]["user_created_at"]


def test_direct_user_relevance_dominates_time_and_assistant_role():
    early = turn(1, "AuroraCafe anniversary is July ninth.", days=800)
    recent_partial = turn(3, "anniversary ordinary discussion", days=0)
    ai_statement = turn(5, "普通交谈。", "AuroraCafe anniversary is August tenth.", days=0)
    ranked = rank_direct([recent_partial, ai_statement, early], ["auroracafe", "anniversary"], now=NOW)
    assert ranked[0].turn["sequence"] == 1
    assert ranked[0].user_coverage == 1
    assert ranked[1].matched_roles == ("assistant",)


def test_event_expansion_requires_strong_user_hit_and_valid_message_time():
    weak = turn(1, "普通 anniversary discussion")
    ai = turn(3, "普通聊天。", "AuroraCafe anniversary plan")
    invalid_time = turn(5, "AuroraCafe anniversary plan")
    invalid_time["user_created_at"] = "unknown"
    assert not event_windows(rank_direct([weak], ["auroracafe", "anniversary"], now=NOW))
    assert not event_windows(rank_direct([ai], ["auroracafe", "anniversary"], now=NOW))
    assert not event_windows(rank_direct([invalid_time], ["auroracafe", "anniversary"], now=NOW))


def test_windows_use_record_time_only_and_never_invent_event_dates():
    item = turn(1, "两天前吃过鸡翅。", days=10)
    windows = event_windows(rank_direct([item], ["鸡翅"], now=NOW))
    assert len(windows) == 1
    assert windows[0].anchor_id == "u-1"
    assert windows[0].start == NOW - timedelta(days=10, hours=12)
    assert windows[0].end == NOW - timedelta(days=10) + timedelta(hours=12)
    assert "event_date" not in windows[0].__dataclass_fields__


def test_complete_pairs_and_budget_never_create_persistent_summary_or_mutate_text():
    originals = [turn(1, "anniversary " + "原" * 2000, "话" * 2000), turn(3, "anniversary fact", "Original answer")]
    selected = select_memory(originals, ["anniversary"], now=NOW, max_chars=100)
    assert [item.turn["sequence"] for item in selected] == [3]
    assert originals[0]["user_text"].endswith("原" * 2000)
    assert selected[0].turn["assistant_text"] == "Original answer"


def test_no_meaningful_terms_return_no_facts_or_events():
    assert not select_memory([turn(1, "今天生日")], [], nearby_events=[turn(3, "今天纪念日")], now=NOW)


def test_invalid_timestamp_gets_no_recency_bonus_but_remains_recallable():
    old = turn(1, "AuroraCafe anniversary July ninth")
    old["user_created_at"] = "2026-10-01"
    old["assistant_created_at"] = "invalid"
    item = rank_direct([old], ["auroracafe"], now=NOW)[0]
    assert item.score == item.direct_score
    assert select_memory([old], ["auroracafe"], now=NOW)[0].turn == old


def test_event_query_does_not_replace_stronger_direct_fact_or_latest_correction():
    direct = [turn(1, "鸡翅晚饭计划"), turn(3, "鸡翅晚饭安排"), turn(5, "鸡翅晚饭餐厅"), turn(7, "更正，鸡翅晚饭改成周末。")]
    event = turn(9, "今天是我的生日，一起庆祝。")
    selected = select_memory(direct, ["鸡翅", "晚饭"], nearby_events=[event], now=NOW)
    assert {item.turn["sequence"] for item in selected} == {1, 3, 5, 7}


def test_turn_quota_zero_has_no_side_effect_and_negative_weights_are_rejected():
    assert not select_memory([turn(1, "anniversary")], ["anniversary"], now=NOW, max_turns=0)
    with pytest.raises(ValueError):
        rank_direct([turn(1, "anniversary")], ["anniversary"], now=NOW, weights=RankingWeights(recency_bonus=-1))


def test_event_weight_zero_disables_expansion_and_user_scope_defense_filters_known_foreign_rows():
    direct = [turn(1, "鸡翅很香"), turn(3, "鸡翅秘密", owner="foreign")]
    selected = select_memory(direct, ["鸡翅"], nearby_events=[turn(5, "今天生日")], now=NOW,
                             scope=("owner", "chat"), weights=replace(DEFAULT_WEIGHTS, event_weight=0))
    assert [item.turn["sequence"] for item in selected] == [1]


def test_event_duplicate_keeps_one_original_pair_and_only_latest_event_slot():
    anchor = turn(1, "今天一起吃鸡翅过生日。")
    event = turn(3, "今天庆祝我的生日。")
    selected = select_memory([anchor], ["鸡翅"], nearby_events=[anchor, event, event], now=NOW)
    assert [item.turn["sequence"] for item in selected] == [1, 3]


def test_third_party_event_and_hypothetical_event_do_not_become_user_birthday_context():
    anchor = turn(1, "前天吃了鸡翅。")
    others = [turn(3, "今天是我妈妈的生日，一起庆祝。"), turn(5, "今天是朋友生日。"),
              turn(7, "假如今天是我的生日，那会很开心。"), turn(9, "AI 说今天是它的生日。")]
    assert [item.turn["sequence"] for item in select_memory([anchor], ["鸡翅"], nearby_events=others, now=NOW)] == [1]


def test_addressing_ai_does_not_mislabel_the_users_own_event_as_ai_self_description():
    anchor = turn(1, "前天吃了鸡翅。")
    birthday = turn(3, "你记得今天是我的生日吗？")
    selected = select_memory([anchor], ["鸡翅"], nearby_events=[birthday], now=NOW)
    assert {item.turn["sequence"] for item in selected} == {1, 3}
    assert selected[1].relation_confirmed is False


def test_recent_event_that_is_already_a_direct_hit_does_not_hide_another_distinct_event():
    anchor = turn(5, "今天吃了鸡翅过生日。")
    other = turn(3, "今天是我们的纪念日，我们一起庆祝。", hours=1)
    selected = select_memory([anchor], ["鸡翅"], nearby_events=[anchor, other], now=NOW)
    assert {item.turn["sequence"] for item in selected} == {3, 5}
