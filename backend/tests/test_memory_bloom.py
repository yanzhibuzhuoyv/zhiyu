import hashlib
import random

import pytest

from app.memory_bloom import MAGIC, MAX_BLOOM_BYTES, decode_bloom, encode_bloom
from app.source_codec import ChatStorageError


def token(text):
    return "t" + text.encode("utf-8").hex()


def test_adaptive_bloom_keeps_all_terms_and_is_deterministic():
    tokens = [token(f"distinct-{number}") for number in range(10000)]
    first = encode_bloom(tokens)
    assert first == encode_bloom(list(reversed(tokens)) + tokens[:30])
    bloom = decode_bloom(first)
    assert bloom.term_count == 10000
    assert bloom.bit_count == 160000
    assert all(bloom.may_contain_any([item]) for item in tokens)
    assert len(first) < 21000


def test_empty_and_unicode_encoded_terms_have_no_false_negatives():
    empty = decode_bloom(encode_bloom([]))
    assert not empty.may_contain_any([token("missing")])
    assert not empty.may_contain_any([])
    tokens = [token("生日"), token("é"), token("👩🏽‍🚀"), token("a\0b")]
    bloom = decode_bloom(encode_bloom(tokens))
    assert all(bloom.may_contain_any([item]) for item in tokens)
    assert bloom.may_contain_any([token("unrelated"), tokens[0]])


def test_random_absent_terms_are_often_rejected_but_positive_is_never_proof():
    generator = random.Random(47)
    existing = [token(f"kept-{generator.getrandbits(64)}") for _ in range(1000)]
    absent = [token(f"absent-{generator.getrandbits(64)}") for _ in range(1000)]
    bloom = decode_bloom(encode_bloom(existing))
    assert sum(bloom.may_contain_any([item]) for item in absent) < 20
    assert all(bloom.may_contain_any([item]) for item in existing)


@pytest.mark.parametrize("value", [None, "wrong", bytearray(b"wrong"), b"", MAGIC,
                                 MAGIC + b"\0" * 45, b"ZYBL2\0" + b"\0" * 100,
                                 b"x" * (MAX_BLOOM_BYTES + 1)],
                         ids=["none", "text", "bytearray", "empty", "magic", "short", "version", "large"])
def test_wrong_type_version_header_or_length_fails_closed(value):
    with pytest.raises(ChatStorageError, match="冷记忆"):
        decode_bloom(value)


def test_bitset_metadata_checksum_truncation_and_trailing_bytes_are_checked():
    original = encode_bloom([token("private-sentinel")])
    for offset in (len(MAGIC), len(MAGIC) + 4, len(MAGIC) + 5, len(original) - 1):
        changed = bytearray(original)
        changed[offset] ^= 1
        with pytest.raises(ChatStorageError, match="冷记忆") as caught:
            decode_bloom(bytes(changed))
        assert "private-sentinel" not in str(caught.value)
    for changed in (original[:-1], original + b"trailing"):
        with pytest.raises(ChatStorageError):
            decode_bloom(changed)


def test_checksum_valid_structurally_invalid_frame_is_rejected():
    original = encode_bloom([token("one")])
    metadata_size = len(MAGIC) + 4 + 1 + 4
    meta = original[:metadata_size]
    payload = original[metadata_size + 32:]
    altered = meta[:len(MAGIC)] + (127).to_bytes(4, "big") + meta[len(MAGIC) + 4:]
    changed = altered + hashlib.sha256(altered + payload).digest() + payload
    with pytest.raises(ChatStorageError):
        decode_bloom(changed)


@pytest.mark.parametrize("tokens", [[None], ["private-sentinel"], ["t"], ["tzz"],
                                   ["t0"], "not-a-sequence", None])
def test_invalid_terms_fail_closed_without_exposing_the_term(tokens):
    with pytest.raises(ChatStorageError, match="冷记忆") as caught:
        encode_bloom(tokens)
    assert "private-sentinel" not in str(caught.value)


def test_query_term_validation_is_identical_to_build_validation():
    bloom = decode_bloom(encode_bloom([token("birthday")]))
    with pytest.raises(ChatStorageError):
        bloom.may_contain_any(["invalid"])


def test_large_vocabulary_caps_frame_without_sampling(monkeypatch):
    monkeypatch.setattr("app.memory_bloom.MAX_BLOOM_BYTES", 1024)
    tokens = [token(f"word-{index}") for index in range(10000)]
    encoded = encode_bloom(tokens)
    assert len(encoded) <= 1024
    assert all(decode_bloom(encoded).may_contain_any([item]) for item in tokens)
