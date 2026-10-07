from copy import deepcopy

import pytest

from app.memory_attention import MemoryAttention


def originals():
    return [{"message_id": "user-old", "role": "user", "text": "我上次在雨天的书店遇到了旧朋友。",
             "created_at": "2026-09-02T01:00:00+00:00", "char_start": 0, "char_end": 17,
             "source_current": False},
            {"message_id": "assistant-old", "role": "assistant", "text": "你愿意说说当时的感受吗？",
             "char_start": 0, "char_end": 13}]


def test_attention_never_filters_rewrites_or_mutates_original_evidence():
    attention, refs = MemoryAttention(), originals()
    before = deepcopy(refs)
    attention.remember("owner", "chat", refs)
    decorated = attention.decorate("owner", "chat", refs)
    assert refs == before
    assert len(decorated) == 2
    for raw, result in zip(refs, decorated, strict=True):
        assert all(result[k] == v for k, v in raw.items())
        assert result is not raw
        assert result["recently_offered"] is True
        assert result["presentation_hint"] == "optional_background"


def test_same_topic_is_not_same_original_and_scope_cannot_leak():
    attention, refs = MemoryAttention(), originals()
    attention.remember("owner", "chat", refs)
    new = [{**refs[0], "message_id": "different-source"}]
    assert not attention.decorate("owner", "chat", new)[0]["recently_offered"]
    assert not attention.decorate("other-owner", "chat", refs)[0]["recently_offered"]
    assert not attention.decorate("owner", "other-chat", refs)[0]["recently_offered"]


def test_last_successful_offer_replaces_not_accumulates_all_past_ids():
    attention, refs = MemoryAttention(), originals()
    attention.remember("owner", "chat", refs)
    attention.remember("owner", "chat", [{"message_id": "new"}])
    assert not any(r["recently_offered"] for r in attention.decorate("owner", "chat", refs))
    attention.remember("owner", "chat", [])
    assert not attention.decorate("owner", "chat", [{"message_id": "new"}])[0]["recently_offered"]


def test_forget_and_restart_safely_fall_back_without_suppressing_evidence():
    attention, refs = MemoryAttention(), originals()
    attention.remember("owner", "chat", refs)
    attention.forget("owner", "chat")
    for cache in (attention, MemoryAttention()):
        decorated = cache.decorate("owner", "chat", refs)
        assert len(decorated) == len(refs)
        assert not any(r["recently_offered"] for r in decorated)


def test_lru_scopes_and_ids_are_bounded_without_body_retention():
    attention = MemoryAttention(max_scopes=2)
    attention.remember("a", "chat", [{"message_id": str(i), "text": "秘密原文"} for i in range(20)])
    assert sum(r["recently_offered"] for r in attention.decorate("a", "chat", [{"message_id": str(i)} for i in range(20)])) == 8
    attention.remember("b", "chat", [{"message_id": "b"}])
    attention.decorate("a", "chat", [])
    attention.remember("c", "chat", [{"message_id": "c"}])
    assert not attention.decorate("b", "chat", [{"message_id": "b"}])[0]["recently_offered"]
    assert attention.decorate("a", "chat", [{"message_id": "0"}])[0]["recently_offered"]


@pytest.mark.parametrize("limit", [0, -1, True, 1.5, 4097])
def test_invalid_cache_capacity_is_rejected(limit):
    with pytest.raises(ValueError):
        MemoryAttention(max_scopes=limit)
