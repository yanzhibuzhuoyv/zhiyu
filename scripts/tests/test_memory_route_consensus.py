"""Decision contracts with invented vectors, not Chinese intent accuracy."""

import math

import pytest

from scripts.memory_route_consensus import ConsensusRouter
from scripts.memory_route_policy import RoutePolicy


class Encoder:
    def fits_input(self, text):
        return True

    def encode_documents(self, texts):
        return [[1.0, 0.0] for _ in texts]


def vector(cosine):
    return [cosine, math.sqrt(1 - cosine * cosine)]


def router(present, retrieve, policy=None):
    prototypes = [("present", vector(cos)) for cos in present]
    prototypes.extend(("retrieve", vector(cos)) for cos in retrieve)
    return ConsensusRouter(Encoder(), prototypes, policy or RoutePolicy())


def test_three_supports_required_even_if_one_present_is_perfect():
    decision = router([1.0, 0.75, 0.70], [0.90]).decide("合成问题", [])
    assert decision.route == "retrieve"
    assert decision.present_score == pytest.approx(0.70)


def test_three_strong_supports_skip_and_report_weakest_similarity():
    decision = router([1.0, 0.99, 0.97], [0.70]).decide("合成问题", [])
    assert decision.route == "present"
    assert decision.present_score == pytest.approx(0.97)
    assert decision.retrieve_score == pytest.approx(0.70)


def test_third_neighbor_tied_with_retrieve_never_skips_in_any_order():
    prototypes = [
        ("present", vector(0.99)),
        ("present", vector(0.97)),
        ("present", vector(0.90)),
        ("retrieve", vector(0.90)),
    ]
    for examples in (prototypes, list(reversed(prototypes))):
        candidate = ConsensusRouter(Encoder(), examples, RoutePolicy(0.60, 0))
        assert candidate.decide("合成问题", []).route == "retrieve"


def test_threshold_is_for_third_support_rather_than_best():
    assert router([1, 0.99, 0.79], [0.30]).decide("合成问题", []).route == "retrieve"


def test_less_than_three_present_training_cases_rejected():
    with pytest.raises(ValueError):
        router([1, 0.99], [0.2])


def test_current_uncertain_cannot_be_promoted_by_context():
    candidate = router([1, 0.99, 0.98], [0.7])
    assert (
        candidate.from_scores(
            [{"present": 0.65, "retrieve": 0.7}, {"present": 0.97, "retrieve": 0.5}]
        ).route
        == "retrieve"
    )
    assert (
        candidate.from_scores(
            [{"present": 0.97, "retrieve": 0.5}, {"present": 0.65, "retrieve": 0.7}]
        ).reason
        == "context_veto"
    )
