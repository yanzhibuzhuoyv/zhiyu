"""Cascade contracts before running frozen, independently authored cases."""

import numpy as np
import pytest

from scripts.memory_retrieval_ab import RetrievalIndex, original_digest


class Encoder:
    def spans(self, text, *, query=False):
        return [(i, min(len(text), i + 80)) for i in range(0, len(text), 64)]

    def encode_documents(self, texts):
        return [self.encode_query(t) for t in texts]

    def encode_query(self, text):
        v = np.array([1.0, float("蓝" in text), float("陶" in text)], dtype=np.float32)
        return (v / np.linalg.norm(v)).tolist()


def messages():
    return [
        {
            "id": "u",
            "role": "user",
            "text": "我报名了蓝陶课程。",
            "created_at": "2026-09-01T01:00:00+00:00",
        },
        {
            "id": "a",
            "role": "assistant",
            "text": "我陪你练习。",
            "created_at": "2026-09-01T01:00:01+00:00",
        },
    ]


def test_orders_and_union_preserve_originals_and_exact_spans():
    originals = messages()
    digest = original_digest(originals)
    with RetrievalIndex(originals, Encoder(), scope="one") as index:
        a = index.retrieve("蓝陶课程", "lexical-first", scope="one")
        b = index.retrieve("蓝陶课程", "semantic-first", scope="one")
        union = index.retrieve("蓝陶课程", "both", scope="one")
        assert a["stages"][0] == "lexical"
        assert b["stages"][0] == "semantic"
        assert union["stages"] == ["lexical", "semantic"]
        assert len(union["references"]) == 2
        for ref in union["references"]:
            src = next(m for m in originals if m["id"] == ref["message_id"])
            assert ref["text"] == src["text"][ref["char_start"] : ref["char_end"]]
            assert ref["role"] == src["role"]
            assert ref["created_at"] == src["created_at"]
    assert digest == original_digest(originals)


def test_semantic_search_is_independent_of_zero_lexical_candidates():
    with RetrievalIndex(messages(), Encoder()) as index:
        result = index.retrieve("器皿制作", "lexical-first")
        assert result["stages"] == ["lexical", "semantic"]
        assert result["candidate_counts"]["lexical"] == 0
        assert result["references"]


def test_multi_fact_always_falls_back_and_union_is_order_independent():
    with RetrievalIndex(messages(), Encoder()) as index:
        a = index.retrieve("蓝陶课程和练习分别是什么？", "lexical-first")
        b = index.retrieve("蓝陶课程和练习分别是什么？", "semantic-first")
        assert len(a["stages"]) == len(b["stages"]) == 2
        assert a["references"] == b["references"]
        assert "compound_query" in a["trigger_reasons"]


@pytest.mark.parametrize("change", ["role", "id", "text", "created_at"])
def test_bad_originals_are_rejected(change):
    bank = messages()
    bank[0][change] = ""
    with pytest.raises(ValueError):
        RetrievalIndex(bank, Encoder())


def test_scope_order_vector_and_span_validation():
    with RetrievalIndex(messages(), Encoder(), scope="one") as index:
        with pytest.raises(ValueError):
            index.retrieve("蓝陶", "lexical-first", scope="other")
        with pytest.raises(ValueError):
            index.retrieve("蓝陶", "bad", scope="one")
        with pytest.raises(ValueError):
            index.retrieve("", "both", scope="one")

    class Bad(Encoder):
        def encode_documents(self, texts):
            return [[float("nan")]] * len(texts)

    with pytest.raises(ValueError):
        RetrievalIndex(messages(), Bad())


def test_assistant_and_question_hits_cannot_trigger_fast_stop():
    bank = messages()
    bank[0]["text"] = "蓝陶课程报名了吗？"
    with RetrievalIndex(bank, Encoder()) as index:
        result = index.retrieve("蓝陶课程报名", "lexical-first")
        assert len(result["stages"]) == 2
        assert "uncertain_source" in result["trigger_reasons"]


def test_long_tail_and_index_accounting():
    bank = messages()
    bank[0]["text"] = "前面的日常记录。" * 300 + "蓝陶课程周五开始。"
    with RetrievalIndex(bank, Encoder()) as index:
        result = index.retrieve("蓝陶课程周五", "both")
        assert any("周五" in r["text"] for r in result["references"])
        assert index.vector_bytes == len(index.fragments) * 3 * 4
        assert sum(len(r["text"]) for r in result["references"]) <= 6144
