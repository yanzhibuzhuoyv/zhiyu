import hashlib
import random
import zlib

import pytest

from app.body_codec import MAGIC, MAX_BODY_BYTES, decode_body, encode_body
from app.source_codec import ChatStorageError

HEADER_BYTES = len(MAGIC) + 4 + 32


def packet(raw: bytes, *, declared=None, digest=None, magic=MAGIC):
    size = len(raw) if declared is None else declared
    checksum = hashlib.sha256(raw).digest() if digest is None else digest
    return magic + size.to_bytes(4, "big") + checksum + zlib.compress(raw)


@pytest.mark.parametrize("text", ["", "少量原文", "a\0b", "é e\u0301", "👩🏽‍🚀\u200d❤️"],
                         ids=["empty", "chinese", "nul", "combining", "emoji"])
def test_short_text_remains_text_and_preserves_every_code_point(text):
    encoded = encode_body(text)
    assert isinstance(encoded, str)
    assert encoded == text
    assert decode_body(encoded) == text


def test_long_unicode_round_trip_and_header_integrity():
    text = "é e\u0301\0👩🏽‍🚀 中文原话\r\n" * 2000
    raw = text.encode("utf-8")
    encoded = encode_body(text)
    assert isinstance(encoded, bytes)
    assert MAGIC == b"ZYBC1\0"
    assert encoded.startswith(MAGIC)
    assert int.from_bytes(encoded[len(MAGIC):len(MAGIC) + 4], "big") == len(raw)
    assert encoded[len(MAGIC) + 4:HEADER_BYTES] == hashlib.sha256(raw).digest()
    assert len(encoded) * 10 <= len(raw) * 9
    assert decode_body(encoded) == text


def test_exact_compression_threshold_uses_utf8_bytes():
    assert isinstance(encode_body("a" * 1023), str)
    assert isinstance(encode_body("a" * 1024), bytes)
    assert isinstance(encode_body("汉" * 341), str)
    assert isinstance(encode_body("汉" * 342), bytes)


@pytest.mark.parametrize("raw_size,total_size,compresses", [
    (1024, 921, True), (1024, 922, False), (1025, 922, True), (1025, 923, False),
])
def test_compression_savings_include_header_and_require_ten_percent(
        monkeypatch, raw_size, total_size, compresses):
    levels = []

    def compress(raw, *, level):
        levels.append(level)
        return b"x" * (total_size - HEADER_BYTES)

    monkeypatch.setattr("app.body_codec.zlib.compress", compress)
    encoded = encode_body("a" * raw_size)
    assert isinstance(encoded, bytes) is compresses
    assert levels == [6]
    if not compresses:
        assert encoded == "a" * raw_size


def test_high_entropy_short_body_is_not_replaced_by_larger_container():
    generator = random.Random(47)
    text = "".join(chr(generator.randrange(128)) for _ in range(1024))
    assert HEADER_BYTES + len(zlib.compress(text.encode(), level=6)) > len(text) * 0.9
    assert encode_body(text) == text


def test_old_text_remains_compatible_without_claiming_checksum_validation():
    original = "旧的合法原话"
    equally_valid_changed = "新的合法原话"
    assert len(original.encode()) == len(equally_valid_changed.encode())
    assert decode_body(original) == original
    assert decode_body(equally_valid_changed) == equally_valid_changed


@pytest.mark.parametrize("text", ["a" * MAX_BODY_BYTES,
                                 "汉" * (MAX_BODY_BYTES // 3) + "a" * (MAX_BODY_BYTES % 3)],
                         ids=["ascii_max", "utf8_max"])
def test_maximum_byte_size_is_accepted_for_old_text_and_new_compressed_body(text):
    assert MAX_BODY_BYTES == 16 * 1024 * 1024
    assert len(text.encode("utf-8")) == MAX_BODY_BYTES
    assert decode_body(text) == text
    encoded = encode_body(text)
    assert isinstance(encoded, bytes)
    assert decode_body(encoded) == text


@pytest.mark.parametrize("operation", [encode_body, decode_body], ids=["encode", "decode"])
@pytest.mark.parametrize("text", ["a" * (MAX_BODY_BYTES + 1),
                                 "汉" * (MAX_BODY_BYTES // 3 + 1)],
                         ids=["ascii_over", "utf8_over"])
def test_oversized_utf8_fails_closed(operation, text):
    with pytest.raises(ChatStorageError, match="聊天正文"):
        operation(text)


@pytest.mark.parametrize("operation", [encode_body, decode_body], ids=["encode", "decode"])
@pytest.mark.parametrize("value", [None, 123, {}, [], bytearray(b"abc"), memoryview(b"abc")],
                         ids=["none", "number", "dict", "list", "bytearray", "memoryview"])
def test_bad_types_fail_closed_without_replacement(operation, value):
    with pytest.raises(ChatStorageError, match="聊天正文"):
        operation(value)


def test_encode_rejects_raw_bytes_instead_of_implicitly_decoding():
    with pytest.raises(ChatStorageError, match="聊天正文"):
        encode_body(b"raw text")


@pytest.mark.parametrize("operation", [encode_body, decode_body], ids=["encode", "decode"])
@pytest.mark.parametrize("text", ["\ud800", "private-sentinel\udfff"],
                         ids=["high_surrogate", "low_surrogate"])
def test_invalid_unicode_raises_safe_error_without_user_content(operation, text):
    with pytest.raises(ChatStorageError, match="聊天正文") as caught:
        operation(text)
    assert "private-sentinel" not in str(caught.value)


@pytest.mark.parametrize("value", [b"", b"old raw bytes", MAGIC, MAGIC + b"\0" * 36,
                                 packet(b"original", magic=b"ZYBC2\0"),
                                 MAGIC + b"x" * (MAX_BODY_BYTES + HEADER_BYTES)],
                         ids=["empty", "raw", "only_magic", "header_only", "version", "large_blob"])
def test_bad_container_formats_are_rejected(value):
    with pytest.raises(ChatStorageError, match="聊天正文"):
        decode_body(value)


@pytest.mark.parametrize("value", [
    packet(b"hello")[:-2], packet(b"hello") + b"trailing",
    packet(b"hello") + zlib.compress(b"second stream"),
    packet(b"hello", declared=4), packet(b"hello", declared=6),
    packet(b"hello", declared=MAX_BODY_BYTES + 1),
    MAGIC + (5).to_bytes(4, "big") + hashlib.sha256(b"hello").digest() + b"invalid-zlib",
], ids=["truncated", "trailing", "concatenated", "declared_short", "declared_long",
        "declared_oversized", "broken_zlib"])
def test_compressed_length_stream_and_trailing_data_are_validated(value):
    with pytest.raises(ChatStorageError, match="聊天正文"):
        decode_body(value)


def test_a_valid_empty_compressed_packet_can_be_read():
    assert decode_body(packet(b"")) == ""


def test_corruption_that_remains_equal_length_valid_utf8_fails_checksum():
    original = "原文".encode()
    altered = "改写".encode()
    assert len(original) == len(altered)
    corrupted = packet(altered, digest=hashlib.sha256(original).digest())
    with pytest.raises(ChatStorageError, match="聊天正文"):
        decode_body(corrupted)


def test_checksum_comparison_uses_constant_time_function(monkeypatch):
    observed = []

    def compare(actual, expected):
        observed.append((actual, expected))
        return False

    monkeypatch.setattr("app.body_codec.hmac.compare_digest", compare)
    with pytest.raises(ChatStorageError, match="聊天正文"):
        decode_body(packet(b"private-sentinel"))
    checksum = hashlib.sha256(b"private-sentinel").digest()
    assert observed == [(checksum, checksum)]


def test_valid_checksum_does_not_allow_invalid_utf8():
    with pytest.raises(ChatStorageError, match="聊天正文"):
        decode_body(packet(b"\xff"))


def test_declared_size_limits_expansion_even_when_global_cap_is_larger(monkeypatch):
    real_decompressobj = zlib.decompressobj
    limits = []

    class Decoder:
        def __init__(self):
            self.inner = real_decompressobj()

        def decompress(self, data, max_length):
            limits.append(max_length)
            return self.inner.decompress(data, max_length)

        def __getattr__(self, name):
            return getattr(self.inner, name)

    monkeypatch.setattr("app.body_codec.zlib.decompressobj", Decoder)
    with pytest.raises(ChatStorageError, match="聊天正文"):
        decode_body(packet(b"x" * (1024 * 1024), declared=2))
    assert limits == [3]


def test_global_expansion_bomb_is_rejected():
    with pytest.raises(ChatStorageError, match="聊天正文"):
        decode_body(packet(b"x" * (MAX_BODY_BYTES + 1), declared=MAX_BODY_BYTES))


def test_zlib_encoding_errors_become_safe_storage_errors(monkeypatch):
    def fail(*args, **kwargs):
        raise zlib.error("private-sentinel")

    monkeypatch.setattr("app.body_codec.zlib.compress", fail)
    with pytest.raises(ChatStorageError, match="聊天正文") as caught:
        encode_body("x" * 1024)
    assert "private-sentinel" not in str(caught.value)
