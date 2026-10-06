import hashlib
from dataclasses import FrozenInstanceError, replace
from datetime import UTC, datetime, timedelta, timezone

import pytest

from app.memory_lifecycle import Decision, Observation, Policy

BASE = datetime(2026, 1, 1, tzinfo=UTC)
NOW = BASE + timedelta(days=30)


def observation(index=1, *, days=0, **overrides):
    fields = dict(id=f"a-{index}", message_id=f"m-{index}", role="user",
                  created_at=BASE + timedelta(days=days), subject="user", kind="wish",
                  polarity="affirm", topic_key="fruit-topic", source_span=(0, 10),
                  text_sha=hashlib.sha256(f"source-{index}".encode()).hexdigest(),
                  validity="current", explicit_keep=False, promise_state=None)
    return Observation(**{**fields, **overrides})


def repeated(kind="wish", **overrides):
    return tuple(observation(index + 1, days=days, kind=kind, **overrides)
                 for index, days in enumerate((0, 3, 6)))


def test_repeated_wishes_gain_topic_attention_without_becoming_preferences_or_permanent():
    originals = repeated()
    decisions = Policy().evaluate(originals, NOW)
    assert len(decisions) == 3
    assert {item.tier for item in decisions.values()} == {"long"}
    assert {item.independent_evidence_count for item in decisions.values()} == {3}
    assert all(item.kind == "wish" for item in originals)
    assert all(decision.evidence_ids == ("a-1", "a-2", "a-3") for decision in decisions.values())
    assert all(not decision.pinned for decision in decisions.values())


@pytest.mark.parametrize("kind", ["preference", "habit"])
def test_affirmative_stable_kinds_need_independent_spaced_evidence(kind):
    items = repeated(kind)
    assert {result.tier for result in Policy().evaluate(items[:2], NOW).values()} == {"mid"}
    assert {result.tier for result in Policy().evaluate(items, NOW).values()} == {"long"}
    assert all(result.tier != "permanent" for result in Policy().evaluate(items, NOW).values())


def test_verified_profile_is_protected_without_topic_name_special_cases():
    opaque = observation(kind="profile", topic_key="verified-field-37")
    misleading = observation(2, kind="episodic", topic_key="birthday")
    decision = Policy().evaluate([opaque, misleading], NOW)
    assert decision[opaque.id].tier == "permanent"
    assert decision[opaque.id].reason == "verified_user_profile"
    assert decision[misleading.id].tier == "mid"


@pytest.mark.parametrize("kind", ["wish", "preference", "habit", "promise", "episodic"])
def test_user_explicit_keep_protects_only_that_original(kind):
    kept = observation(kind=kind, explicit_keep=True)
    unkept = observation(2, days=3, kind=kind)
    decision = Policy().evaluate([kept, unkept], NOW)
    assert decision[kept.id].tier == "permanent"
    assert decision[kept.id].reason == "explicit_user_keep"
    assert decision[unkept.id].tier == "mid"


@pytest.mark.parametrize("overrides", [
    {"role": "assistant"}, {"subject": "assistant"}, {"subject": "third"},
    {"subject": "unknown"}, {"polarity": "deny"}, {"polarity": "quote"},
    {"polarity": "hypothesis"}, {"validity": "uncertain"},
    {"validity": "superseded"}, {"validity": "retracted"},
], ids=["ai_echo", "ai_subject", "friend", "unknown", "negative", "quoted",
        "hypothetical", "uncertain", "corrected", "withdrawn"])
def test_unverified_or_ineligible_observations_never_promote_personal_facts(overrides):
    originals = repeated("profile", explicit_keep=True, **overrides)
    results = Policy().evaluate(originals, NOW)
    assert {item.tier for item in results.values()} == {"mid"}
    assert {item.independent_evidence_count for item in results.values()} == {0}
    assert all(not item.evidence_ids for item in results.values())


def test_wishes_do_not_count_as_affirmative_preference_evidence():
    preferences = [observation(4, days=7, kind="preference")]
    results = Policy().evaluate([*repeated(), *preferences], NOW)
    assert results["a-4"].tier == "mid"
    assert results["a-4"].independent_evidence_count == 1
    assert results["a-4"].evidence_ids == ("a-4",)


@pytest.mark.parametrize("duplicate", ["utc_day", "message", "hash"],
                         ids=["same_day", "same_source_message", "copied_original"])
def test_duplicates_do_not_increase_independent_evidence(duplicate):
    first = observation(1)
    second = observation(2, days=3)
    third = observation(3, days=6)
    if duplicate == "utc_day":
        third = replace(third, created_at=second.created_at + timedelta(hours=12))
    elif duplicate == "message":
        third = replace(third, message_id=first.message_id, source_span=(20, 30))
    else:
        third = replace(third, text_sha=first.text_sha.upper())
    results = Policy().evaluate([first, second, third], NOW)
    assert {item.independent_evidence_count for item in results.values()} == {2}
    assert {item.tier for item in results.values()} == {"mid"}


def test_same_hash_on_other_topic_is_not_deduplicated_across_topics():
    items = [*repeated(), observation(4, days=7, topic_key="another-topic",
                                    text_sha=observation(1).text_sha)]
    results = Policy().evaluate(items, NOW)
    assert results["a-1"].independent_evidence_count == 3
    assert results["a-4"].independent_evidence_count == 1


def test_retry_and_input_order_are_deterministic_without_modifying_observations():
    items = repeated()
    expected = Policy().evaluate(items, NOW)
    assert Policy().evaluate([items[2], items[0], items[1], items[0]], NOW) == expected
    assert len(expected) == 3
    assert items == repeated()
    with pytest.raises(FrozenInstanceError):
        items[0].kind = "preference"
    with pytest.raises(FrozenInstanceError):
        expected["a-1"].tier = "permanent"


def test_conflicting_reused_annotation_id_is_rejected():
    item = observation()
    with pytest.raises(ValueError, match="标注"):
        Policy().evaluate([item, replace(item, topic_key="different")], NOW)


def test_future_observations_cannot_upgrade_earlier_replay():
    items = repeated()
    at_three_days = Policy().evaluate(items, BASE + timedelta(days=3))
    assert at_three_days["a-1"].tier == "mid"
    assert at_three_days["a-1"].independent_evidence_count == 2
    assert at_three_days["a-3"].reason == "not_observed_yet"
    assert at_three_days["a-3"].independent_evidence_count == 0
    assert Policy().evaluate(items, BASE + timedelta(days=6))["a-1"].tier == "long"


def test_real_elapsed_time_is_required_in_addition_to_different_utc_buckets():
    items = [observation(1, created_at=BASE + timedelta(hours=23, minutes=59)),
             observation(2, days=3), observation(3, days=6)]
    assert Policy().evaluate(items, NOW)["a-1"].tier == "mid"
    assert Policy(min_elapsed_days=5).evaluate(items, NOW)["a-1"].tier == "long"


def test_policy_parameters_can_raise_evidence_and_time_thresholds():
    items = [*repeated(), observation(4, days=9)]
    assert Policy(min_independent_evidence=4).evaluate(items[:3], NOW)["a-1"].tier == "mid"
    assert Policy(min_independent_evidence=4).evaluate(items, NOW)["a-1"].tier == "long"
    assert Policy(min_elapsed_days=10).evaluate(items, NOW)["a-1"].tier == "mid"


def test_correction_metadata_leaves_old_original_available_without_current_fact_upgrade():
    previous = observation(1, kind="profile", validity="superseded")
    corrected = observation(2, days=6, kind="profile")
    results = Policy().evaluate([previous, corrected], NOW)
    assert results[previous.id].tier == "mid"
    assert results[previous.id].reason == "not_current"
    assert results[corrected.id].tier == "permanent"
    assert previous.source_span == (0, 10)
    assert previous.text_sha == observation(1).text_sha


@pytest.mark.parametrize("role,subject", [("user", "user"), ("assistant", "assistant")])
@pytest.mark.parametrize("state", ["pending", "overdue", "fulfilled", "cancelled", None])
def test_promises_pin_only_unresolved_affirmative_current_originals(role, subject, state):
    item = observation(kind="promise", role=role, subject=subject, promise_state=state)
    result = Policy().evaluate([item], NOW)[item.id]
    assert result.pinned is (state in {"pending", "overdue"})
    assert result.tier == "mid"
    assert result.independent_evidence_count == (1 if role == "user" else 0)
    assert item.promise_state == state


@pytest.mark.parametrize("overrides", [{"polarity": "quote"}, {"polarity": "deny"},
                                      {"validity": "superseded"}, {"subject": "third"}])
def test_invalid_or_other_person_promises_are_not_pinned(overrides):
    item = observation(kind="promise", promise_state="pending", **overrides)
    assert not Policy().evaluate([item], NOW)[item.id].pinned


def test_aging_alone_does_not_mark_a_promise_as_fulfilled():
    item = observation(kind="promise", promise_state="pending")
    result = Policy().evaluate([item], BASE + timedelta(days=2000))[item.id]
    assert result.pinned
    assert item.promise_state == "pending"


def test_tier_and_promise_factor_is_relevance_gated_and_bounded():
    item = observation(kind="promise", explicit_keep=True, promise_state="overdue")
    policy = Policy()
    decision = policy.evaluate([item], NOW)[item.id]
    assert policy.priority_factor(decision, relevance=0) == 1
    assert policy.priority_factor(decision, relevance=0.2) == pytest.approx(1.15)
    assert 0.2 * policy.priority_factor(decision, relevance=0.2) < 0.9
    assert Policy(long_bonus=0, permanent_bonus=0, promise_bonus=0).priority_factor(
        decision, relevance=1) == 1


@pytest.mark.parametrize("kind,expected", [("wish", 1), ("profile", 1.10)])
def test_default_tier_factors_are_small(kind, expected):
    policy = Policy()
    result = policy.evaluate([observation(kind=kind)], NOW)["a-1"]
    assert policy.priority_factor(result, relevance=1) == pytest.approx(expected)
    long = policy.evaluate(repeated(), NOW)["a-1"]
    assert policy.priority_factor(long, relevance=1) == pytest.approx(1.05)


@pytest.mark.parametrize("relevance", [-1, float("nan"), float("inf"), "bad", True])
def test_invalid_relevance_is_rejected(relevance):
    with pytest.raises(ValueError, match="相关性"):
        Policy().priority_factor(Decision("a", "mid", 0, "test"), relevance=relevance)


@pytest.mark.parametrize("decision", [None, "mid"])
def test_priority_requires_a_valid_decision(decision):
    with pytest.raises(ValueError, match="策略"):
        Policy().priority_factor(decision, relevance=1)


@pytest.mark.parametrize("fields", [
    {"tier": "unlimited"}, {"tier": []}, {"independent_evidence_count": -1},
    {"independent_evidence_count": True}, {"pinned": "false"}, {"evidence_ids": ["a"]},
])
def test_decision_metadata_is_validated_and_immutable(fields):
    defaults = dict(observation_id="a", tier="mid", independent_evidence_count=0, reason="test")
    with pytest.raises(ValueError, match="决策"):
        Decision(**{**defaults, **fields})


@pytest.mark.parametrize("overrides", [
    {"min_independent_evidence": 2}, {"min_independent_evidence": 3.5},
    {"min_independent_evidence": True}, {"min_independent_evidence": 1001},
    {"min_elapsed_days": 0}, {"min_elapsed_days": float("nan")},
    {"min_elapsed_days": float("inf")}, {"min_elapsed_days": "bad"},
    {"long_bonus": -0.1}, {"long_bonus": 0.11}, {"permanent_bonus": 0.04},
    {"permanent_bonus": 0.11}, {"promise_bonus": 0.06}, {"promise_bonus": True},
])
def test_invalid_policy_parameters_are_rejected(overrides):
    with pytest.raises(ValueError, match="策略"):
        Policy(**overrides)


@pytest.mark.parametrize("overrides", [
    {"id": ""}, {"message_id": ""}, {"topic_key": ""}, {"role": "system"},
    {"subject": "everyone"}, {"kind": "summary"}, {"polarity": "maybe"},
    {"validity": "deleted"}, {"created_at": BASE.replace(tzinfo=None)},
    {"created_at": BASE.astimezone(timezone(timedelta(hours=8)))},
    {"created_at": "date"}, {"source_span": (1, 1)}, {"source_span": (-1, 5)},
    {"source_span": [0, 1]}, {"source_span": (False, 5)},
    {"text_sha": "unknown"}, {"text_sha": "g" * 64}, {"explicit_keep": "yes"},
    {"promise_state": "fulfilled"}, {"kind": "promise", "promise_state": "assumed"},
    {"role": []}, {"subject": {}}, {"kind": []}, {"polarity": []},
    {"validity": {}}, {"kind": "promise", "promise_state": []},
])
def test_invalid_external_annotations_are_rejected(overrides):
    with pytest.raises(ValueError, match="标注"):
        observation(**overrides)


@pytest.mark.parametrize("now", [BASE.replace(tzinfo=None), "date",
                               BASE.astimezone(timezone(timedelta(hours=8)))])
def test_replay_requires_explicit_utc_time(now):
    with pytest.raises(ValueError, match="UTC"):
        Policy().evaluate([], now)


def test_non_observation_input_is_rejected_and_empty_replay_is_valid():
    assert Policy().evaluate([], NOW) == {}
    with pytest.raises(ValueError, match="标注"):
        Policy().evaluate([{"kind": "profile"}], NOW)


@pytest.mark.parametrize("observations", [None, "bad", {"a": "profile"}])
def test_non_sequence_observation_containers_are_rejected(observations):
    with pytest.raises(ValueError, match="标注"):
        Policy().evaluate(observations, NOW)


def test_extreme_numeric_input_fails_validation_without_overflow():
    with pytest.raises(ValueError, match="策略"):
        Policy(min_elapsed_days=10 ** 1000)
    with pytest.raises(ValueError, match="相关性"):
        Policy().priority_factor(Decision("a", "mid", 0, "test"), relevance=10 ** 1000)


def test_decisions_do_not_contain_new_fused_fact_text():
    item = observation(kind="profile")
    result = Policy().evaluate([item], NOW)[item.id]
    assert "text" not in result.__dataclass_fields__
    assert "text" not in item.__dataclass_fields__
    assert item.id == result.observation_id


def test_large_topic_groups_share_bounded_audit_references_without_quadratic_copying():
    items = tuple(observation(index + 1, days=index) for index in range(1000))
    results = Policy().evaluate(items, BASE + timedelta(days=1000))
    shared = results["a-1"].evidence_ids
    assert shared == ("a-1", "a-2", "a-1000")
    assert all(result.independent_evidence_count == 1000 for result in results.values())
    assert all(result.evidence_ids is shared for result in results.values())


def test_identical_wish_can_recur_in_verified_independent_episodes_without_becoming_preference():
    checksum = hashlib.sha256("我想吃苹果".encode()).hexdigest()
    uncertain = repeated(text_sha=checksum)
    assert {item.tier for item in Policy().evaluate(uncertain, NOW).values()} == {"mid"}
    verified = tuple(replace(item, episode_id=f"verified-episode-{index}")
                     for index, item in enumerate(uncertain))
    result = Policy().evaluate(verified, NOW)
    assert {item.tier for item in result.values()} == {"long"}
    assert all(item.kind == "wish" for item in verified)
    assert all(item.independent_evidence_count == 3 for item in result.values())
    copied = tuple(replace(item, episode_id="same-copied-episode") for item in verified)
    assert all(item.independent_evidence_count == 1 for item in Policy().evaluate(copied, NOW).values())


@pytest.mark.parametrize("episode", ["", 7, "x" * 257])
def test_invalid_verified_episode_provenance_is_rejected(episode):
    with pytest.raises(ValueError):
        observation(episode_id=episode)


@pytest.mark.parametrize("unknown_index", [0, 1, 2])
def test_unverified_copy_does_not_add_evidence_alongside_verified_same_text_episodes(unknown_index):
    checksum = hashlib.sha256("我想吃苹果".encode()).hexdigest()
    items = tuple(replace(item, episode_id=None if index == unknown_index else f"verified-{index}")
                  for index, item in enumerate(repeated(text_sha=checksum)))
    results = Policy().evaluate(items, NOW)
    assert all(decision.tier == "mid" for decision in results.values())
    assert all(decision.independent_evidence_count == 2 for decision in results.values())
    assert all(items[unknown_index].id not in decision.evidence_ids for decision in results.values())
