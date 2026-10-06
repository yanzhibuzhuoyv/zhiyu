"""Bounded adaptive lexical Bloom directory: positives still need source checks."""

import hashlib
import hmac
import re
from collections.abc import Sequence
from dataclasses import dataclass

from .body_codec import MAX_BODY_BYTES
from .source_codec import ChatStorageError

MAGIC = b"ZYBL1\0"
MAX_BLOOM_BYTES = 1024 * 1024
HASH_COUNT = 7
BITS_PER_TERM = 16
_METADATA_BYTES = len(MAGIC) + 4 + 1 + 4
_HEADER_BYTES = _METADATA_BYTES + 32


def _terms(tokens) -> tuple[str, ...]:
    if not isinstance(tokens, Sequence) or isinstance(tokens, (str, bytes)):
        raise ChatStorageError("冷记忆词项类型无效")
    for term in tokens:
        if (not isinstance(term, str) or len(term) > MAX_BODY_BYTES * 2 + 1
                or not re.fullmatch(r"t(?:[0-9a-f]{2})+", term)):
            raise ChatStorageError("冷记忆词项编码无效")
    return tuple(tokens)


def _bit_count(term_count: int) -> int:
    return min(max(128, term_count * BITS_PER_TERM), (MAX_BLOOM_BYTES - _HEADER_BYTES) * 8)


def _positions(term: str, bit_count: int):
    digest = hashlib.blake2b(term.encode("ascii"), digest_size=16, person=b"ZhiYuBloom1").digest()
    first = int.from_bytes(digest[:8], "big")
    step = int.from_bytes(digest[8:], "big") | 1
    return ((first + index * step) % bit_count for index in range(HASH_COUNT))


@dataclass(frozen=True)
class Bloom:
    bit_count: int
    term_count: int
    bits: bytes

    def may_contain_any(self, tokens: Sequence[str]) -> bool:
        terms = _terms(tokens)
        return any(all(self.bits[position // 8] & (1 << (position % 8))
                       for position in _positions(term, self.bit_count)) for term in terms)


def encode_bloom(tokens: Sequence[str]) -> bytes:
    terms = set(_terms(tokens))
    count = len(terms)
    if count > MAX_BODY_BYTES:
        raise ChatStorageError("冷记忆词项数量超过上限")
    bit_count = _bit_count(count)
    bits = bytearray(bit_count // 8)
    for term in terms:
        for position in _positions(term, bit_count):
            bits[position // 8] |= 1 << (position % 8)
    metadata = MAGIC + bit_count.to_bytes(4, "big") + bytes([HASH_COUNT]) + count.to_bytes(4, "big")
    payload = bytes(bits)
    return metadata + hashlib.sha256(metadata + payload).digest() + payload


def decode_bloom(value: bytes) -> Bloom:
    if (not isinstance(value, bytes) or not _HEADER_BYTES < len(value) <= MAX_BLOOM_BYTES
            or not value.startswith(MAGIC)):
        raise ChatStorageError("冷记忆目录格式无效")
    bit_count = int.from_bytes(value[len(MAGIC):len(MAGIC) + 4], "big")
    hashes = value[len(MAGIC) + 4]
    count = int.from_bytes(value[len(MAGIC) + 5:_METADATA_BYTES], "big")
    if (hashes != HASH_COUNT or count > MAX_BODY_BYTES or bit_count != _bit_count(count)
            or bit_count % 8 or len(value) != _HEADER_BYTES + bit_count // 8):
        raise ChatStorageError("冷记忆目录长度或版本无效")
    metadata, payload = value[:_METADATA_BYTES], value[_HEADER_BYTES:]
    if not hmac.compare_digest(hashlib.sha256(metadata + payload).digest(),
                               value[_METADATA_BYTES:_HEADER_BYTES]):
        raise ChatStorageError("冷记忆目录完整性校验失败")
    return Bloom(bit_count, count, payload)
