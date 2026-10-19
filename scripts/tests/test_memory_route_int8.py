"""Pure policy tests. No Android, model inference, network or user data."""
import math
import unittest

from scripts.memory_route_int8 import MODEL_IDENTITY, ResearchRouter, eligible_input
from scripts.memory_route_linear import LinearPolicy


class FakeEncoder:
    model_identity = MODEL_IDENTITY

    def fits_input(self, text):
        return eligible_input(text, lambda _: 8)

    def encode_documents(self, texts):
        return [[1.0, 0.0] for _ in texts]


def router(encoder=None, identity=MODEL_IDENTITY):
    return ResearchRouter(encoder or FakeEncoder(), [("present", [1., 0.]),
        ("retrieve", [0., 1.])], [2., -2.], 0., LinearPolicy(.5, .6), identity)


class Int8RoutingContractTest(unittest.TestCase):
    def test_identity_mismatch_preserves_retrieval(self):
        self.assertEqual(router(identity="old-fp32-head").decide("下雨了", []).reason,
                         "model_identity_mismatch")

    def test_context_can_only_veto_not_promote_query(self):
        subject = router()
        daily = {"present_affinity": .9, "nearest_similarity": .8}
        old = {"present_affinity": .4, "nearest_similarity": .8}
        self.assertEqual(subject.from_scores([old, daily]).route, "retrieve")
        self.assertEqual(subject.from_scores([daily, old]).reason, "context_veto")

    def test_threshold_equality_is_not_skip(self):
        self.assertEqual(router().from_scores([
            {"present_affinity": .6, "nearest_similarity": .9}]).route, "retrieve")

    def test_whole_prefix_tokens_and_utf16_are_required(self):
        self.assertFalse(eligible_input("🙂" * 4097, lambda _: 3))
        self.assertFalse(eligible_input("甲", lambda _: 513))
        self.assertTrue(eligible_input("乙", lambda _: 512))

    def test_huge_unk_word_cannot_represent_its_tail(self):
        self.assertFalse(eligible_input("q" * 101 + "告诉我旧日期", lambda _: 9))

    def test_invalid_context_retains_retrieval(self):
        self.assertEqual(router().decide("来陪我", [{"role": "tool", "content": "x"}]).route,
                         "retrieve")

    def test_encoder_nonfinite_or_dimension_error_retains_retrieval(self):
        class Bad(FakeEncoder):
            def encode_documents(self, texts):
                return [[math.nan, 0.] for _ in texts]
        class Short(FakeEncoder):
            def encode_documents(self, texts):
                return [[1.] for _ in texts]
        for encoder in (Bad(), Short()):
            self.assertEqual(router(encoder).decide("陪我走走", []).route, "retrieve")

    def test_valid_query_is_not_silently_disabled(self):
        self.assertEqual(router().decide("我刚在路边见到一朵花", []).route, "present")


if __name__ == "__main__":
    unittest.main()
