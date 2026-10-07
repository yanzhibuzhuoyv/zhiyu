"""Safety contracts use invented vectors, not linguistic accuracy assertions."""

import math

import pytest

from scripts.memory_route_policy import PrototypeRouter, RoutePolicy, route_input


class Encoder:
    def __init__(self):
        self.fits = True
        self.failure = False
        self.vector = [1.0, 0.0]
        self.calls = []

    def fits_input(self, text):
        return self.fits

    def encode_documents(self, texts):
        self.calls.append(tuple(texts))
        if self.failure:
            raise RuntimeError("PRIVATE source is not an error message")
        return [self.vector for _ in texts]


def router(encoder=None, **parameters):
    return PrototypeRouter(
        encoder or Encoder(),
        [("present", [1.0, 0.0]), ("retrieve", [0.0, 1.0])],
        RoutePolicy(**parameters),
    )


def test_confident_present_uses_symmetric_documents_without_query_prefix():
    encoder = Encoder()
    decision = router(encoder).decide("当前合成输入", [])
    assert decision.route == "present" and decision.reason == "present_match"
    assert encoder.calls == [("当前合成输入",)]
    assert decision.present_score == 1.0 and decision.retrieve_score == 0.0


@pytest.mark.parametrize("vector", [[0.0, 1.0], [0.7, 0.7], [-1.0, 0.0]])
def test_retrieve_match_tie_or_low_score_cannot_skip(vector):
    encoder = Encoder()
    encoder.vector = vector
    assert router(encoder).decide("合成输入", []).route == "retrieve"


def test_recent_context_can_veto_but_never_turn_uncertain_query_into_skip():
    encoder = Encoder()

    def encode(texts):
        encoder.calls.append(tuple(texts))
        return [[1.0, 0.0] if text == "合成输入" else [0.0, 1.0] for text in texts]

    encoder.encode_documents = encode
    context = [{"role": "user", "content": "上一条原话"}]
    decision = router(encoder).decide("合成输入", context)
    assert decision.route == "retrieve" and decision.reason == "context_veto"
    assert len(encoder.calls) == 1 and len(encoder.calls[0]) == 2
    encoder.vector = [0.7, 0.7]
    encoder.encode_documents = lambda texts: [encoder.vector, [1.0, 0.0]]
    assert router(encoder).decide("合成输入", context).route == "retrieve"


def test_input_token_limit_has_no_encoding_or_truncated_skip():
    encoder = Encoder()
    encoder.fits = False
    decision = router(encoder).decide("完整输入尾部", [])
    assert decision.route == "retrieve" and decision.reason == "input_limit"
    assert not encoder.calls


@pytest.mark.parametrize(
    "vector", [[float("nan"), 0], [float("inf"), 0], [0, 0], [1], ["x", 1]]
)
def test_bad_vectors_fall_back_without_private_exception_text(vector):
    encoder = Encoder()
    encoder.vector = vector
    decision = router(encoder).decide("合成输入", [])
    assert decision.route == "retrieve" and decision.reason == "encoder_error"
    assert "PRIVATE" not in repr(decision)


def test_encoder_fault_is_safe_and_does_not_change_input():
    encoder = Encoder()
    encoder.failure = True
    context = [{"role": "assistant", "content": "原话完整保留"}]
    assert router(encoder).decide("合成输入", context).route == "retrieve"
    assert context == [{"role": "assistant", "content": "原话完整保留"}]


@pytest.mark.parametrize(
    "settings",
    [
        {"min_similarity": math.nan},
        {"min_similarity": 1.1},
        {"min_margin": -0.1},
        {"min_margin": True},
    ],
)
def test_policy_rejects_invalid_parameters(settings):
    with pytest.raises(ValueError):
        RoutePolicy(**settings)


@pytest.mark.parametrize(
    "prototypes",
    [
        [],
        [("present", [1, 0])],
        [("other", [1, 0]), ("retrieve", [0, 1])],
        [("present", [1]), ("retrieve", [0, 1])],
    ],
)
def test_invalid_or_one_sided_prototypes_rejected(prototypes):
    with pytest.raises(ValueError):
        PrototypeRouter(Encoder(), prototypes, RoutePolicy())


@pytest.mark.parametrize(
    "text,context",
    [
        ("", []),
        (12, []),
        ("x", [None]),
        ("x", [{"role": "system", "content": "instructions"}]),
        ("x", [{"role": "user", "content": ""}]),
        ("x", [{"role": "user", "content": "x", "extra": True}]),
        ("x", [{"role": "user", "content": "x"}] * 5),
    ],
)
def test_invalid_external_case_input_rejected(text, context):
    with pytest.raises(ValueError):
        route_input(text, context)


def test_render_keeps_exact_unicode_and_current_query_last():
    assert (
        route_input("现在\n🙂", [{"role": "user", "content": "先前🙂"}])
        == "最近对话：\nuser：先前🙂\n当前用户：现在\n🙂"
    )


def test_nonunit_embeddings_normalized_for_cosine():
    encoder = Encoder()
    encoder.vector = [100, 0]
    decision = router(encoder).decide("x", [])
    assert decision.present_score == 1.0 and decision.route == "present"
