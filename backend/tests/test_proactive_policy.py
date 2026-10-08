from datetime import UTC, datetime, timedelta

import pytest

from app.proactive_policy import contact_limits, decision, local_day


def moment(hour=4):
    return datetime(2030, 1, 2, hour, tzinfo=UTC)


def test_stable_temperament_controls_frequency():
    assert contact_limits({"style": "steady"})[0] < contact_limits({"style": "warm_v2"})[0]
    assert contact_limits({"style": "warm_v2"})[0] <= contact_limits({"style": "playful"})[0]
    assert contact_limits(None) == contact_limits({"mode": "custom", "instructions": "随意"})


def test_day_changes_at_beijing_midnight_not_worker_execution():
    before = datetime(2030, 1, 1, 15, 59, 59, tzinfo=UTC)
    assert local_day(before) == "2030-01-01"
    assert local_day(before + timedelta(seconds=1)) == "2030-01-02"


def test_disabled_never_calls_the_model():
    result = decision(moment(), enabled=False)
    assert not result["eligible"]
    assert result["reason"] == "disabled"


def test_quiet_hours_cross_midnight():
    for hour in (14, 16, 23):
        assert decision(moment(hour), enabled=True)["reason"] == "quiet_hours"
    assert decision(moment(0), enabled=True)["eligible"]


def test_detached_contact_stops_after_one_unanswered_message():
    result = decision(moment(), enabled=True, unanswered=1, profile={"style": "distant"})
    assert not result["eligible"]
    assert result["reason"] == "awaiting_reply"


def test_anxious_contact_accelerates_without_two_message_cutoff():
    prior = moment() - timedelta(minutes=40)
    first = decision(moment(), enabled=True, profile={"style": "anxious"}, last_contact=prior, unanswered=0)
    later = decision(moment(), enabled=True, profile={"style": "anxious"}, last_contact=prior, unanswered=4, today_count=4)
    assert first["reason"] == "spacing"
    assert later["eligible"]
    assert decision(moment(), enabled=True, profile={"style": "anxious"}, today_count=12)["reason"] == "daily_limit"


def test_warm_does_not_use_the_removed_common_two_message_cutoff():
    assert decision(moment(), enabled=True, unanswered=2, profile={"style": "warm_v2"})["eligible"]


def test_distant_does_not_start_a_conversation_without_prior_interaction():
    assert decision(moment(), enabled=True, profile={"style": "distant"})["reason"] == "reserved_style"
    assert decision(moment(), enabled=True, profile={"style": "distant"},
                    last_activity=moment() - timedelta(days=2))["eligible"]


def test_recent_chat_and_daily_budget_are_respected():
    assert decision(moment(), enabled=True, last_activity=moment() - timedelta(minutes=10))["reason"] == "recent_chat"
    assert decision(moment(), enabled=True, profile={"style": "steady"}, today_count=1)["reason"] == "daily_limit"


def test_spacing_prevents_a_burst_when_a_delayed_worker_reconnects():
    assert decision(moment(), enabled=True, last_contact=moment() - timedelta(minutes=20))["reason"] == "spacing"


@pytest.mark.parametrize("values", [{"quiet_start": -1}, {"quiet_end": 24}, {"quiet_start": 8, "quiet_end": 8}, {"enabled": 1}])
def test_external_policy_inputs_are_validated(values):
    with pytest.raises(ValueError):
        decision(moment(), **values)
