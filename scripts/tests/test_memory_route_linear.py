"""Invented-vector contracts; no model, cloud, or Chinese accuracy claims."""

import math

import pytest

from scripts.memory_route_linear import LinearPolicy, LinearRouter


class Encoder:
    def fits_input(self, text):
        return len(text) < 100

    def encode_documents(self, texts):
        return [[1.0, 0.0] for text in texts]


def router(coefficients=(10, -10), intercept=0, policy=None):
    return LinearRouter(
        Encoder(),
        [("present", [1, 0]), ("retrieve", [0, 1])],
        coefficients,
        intercept,
        policy or LinearPolicy(),
    )


def test_supervised_decision_changes_with_intent_head_not_nearest_label():
    candidate = router()
    assert candidate.decide("合成输入", []).route == "present"
    assert router((-10, 10)).decide("同一个向量输入", []).route == "retrieve"


def test_out_of_distribution_similarity_can_veto_high_affinity():
    decision = router((1000, 1000)).from_scores(
        [{"present_affinity": 1, "nearest_similarity": 0.2}]
    )
    assert decision.route == "retrieve" and decision.reason == "low_support"


def test_affinity_threshold_tie_is_retrieval_and_context_only_vetoes():
    candidate = router(policy=LinearPolicy(0.6, 0.8))
    good = {"present_affinity": 0.9, "nearest_similarity": 0.9}
    tie = {"present_affinity": 0.8, "nearest_similarity": 0.9}
    assert candidate.from_scores([tie]).route == "retrieve"
    assert candidate.from_scores([good, tie]).reason == "context_veto"
    assert candidate.from_scores([tie, good]).route == "retrieve"


@pytest.mark.parametrize(
    "coefficients,intercept",
    [([1], 0), ([math.nan, 1], 0), ([True, 1], 0), ([1, 0], math.inf), ([1, 0], True)],
)
def test_invalid_model_parameters_rejected(coefficients, intercept):
    with pytest.raises(ValueError):
        router(coefficients, intercept)


@pytest.mark.parametrize(
    "values",
    [
        [],
        [{}],
        [{"present_affinity": math.nan, "nearest_similarity": 1}],
        [{"present_affinity": 2, "nearest_similarity": 1}],
        [{"present_affinity": True, "nearest_similarity": 1}],
        [{"present_affinity": 1, "nearest_similarity": 2}],
    ],
)
def test_invalid_replay_scores_rejected(values):
    with pytest.raises(ValueError):
        router().from_scores(values)


@pytest.mark.parametrize("value", [math.nan, True, -1, 1.01])
def test_invalid_policy_rejected(value):
    with pytest.raises(ValueError):
        LinearPolicy(value, 0.9)
    with pytest.raises(ValueError):
        LinearPolicy(0.6, value)


def test_low_affinity_policy_and_wrong_policy_type_rejected():
    with pytest.raises(ValueError):
        LinearPolicy(0.6, 0.49)
    with pytest.raises(TypeError):
        router(policy="incorrect")


def test_long_input_is_never_encoded_and_overflow_fails_closed():
    candidate = router()
    candidate.encoder.encode_documents = lambda texts: pytest.fail(
        "long text must not encode"
    )
    assert candidate.decide("甲" * 100, []).reason == "input_limit"
    assert router((1e308, 0), 1e308).decide("输入", []).reason == "encoder_error"


def test_sigmoid_extreme_logits_do_not_overflow():
    assert router((1000, 0)).scores([1, 0])["present_affinity"] == 1
    assert router((-1000, 0)).scores([1, 0])["present_affinity"] == 0


def test_dimension_and_return_count_fail_closed_without_input_details():
    candidate = router()
    candidate.encoder.encode_documents = lambda texts: [[1, 0, 0]]
    assert candidate.decide("PRIVATE synthetic", []).reason == "encoder_error"
    candidate.encoder.encode_documents = lambda texts: []
    assert candidate.decide("PRIVATE synthetic", []).reason == "encoder_error"
