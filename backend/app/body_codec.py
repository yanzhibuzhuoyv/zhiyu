"""Lossless chat body storage with bounded expansion and legacy TEXT support."""

import hashlib
import hmac
import zlib

from app.source_codec import ChatStorageError

MAGIC = b"ZYBC1\0"
MAX_BODY_BYTES = 16 * 1024 * 1024
COMPRESSION_THRESHOLD = 1024
_LENGTH_END = len(MAGIC) + 4
_HEADER_BYTES = _LENGTH_END + 32


def _encode_utf8(text: str) -> bytes:
    if not isinstance(text, str) or len(text) > MAX_BODY_BYTES:
        raise ChatStorageError("聊天正文存储类型或长度无效")
    try:
        raw = text.encode("utf-8")
    except UnicodeError as exc:
        raise ChatStorageError("聊天正文文本编码无效") from exc
    if len(raw) > MAX_BODY_BYTES:
        raise ChatStorageError("聊天正文超过存储上限")
    return raw


def encode_body(text: str) -> str | bytes:
    """Keep exact TEXT unless a versioned compressed body saves at least 10%."""
    raw = _encode_utf8(text)
    if len(raw) < COMPRESSION_THRESHOLD:
        return text
    try:
        compressed = zlib.compress(raw, level=6)
    except zlib.error as exc:
        raise ChatStorageError("聊天正文无法压缩编码") from exc
    packed = MAGIC + len(raw).to_bytes(4, "big") + hashlib.sha256(raw).digest() + compressed
    return packed if len(packed) * 10 <= len(raw) * 9 else text


def _unpack(value: bytes) -> bytes:
    if not value.startswith(MAGIC) or not _HEADER_BYTES < len(value) <= MAX_BODY_BYTES + _HEADER_BYTES:
        raise ChatStorageError("聊天正文压缩格式无效")
    expected = int.from_bytes(value[len(MAGIC):_LENGTH_END], "big")
    if expected > MAX_BODY_BYTES:
        raise ChatStorageError("聊天正文展开长度无效")
    try:
        decoder = zlib.decompressobj()
        raw = decoder.decompress(value[_HEADER_BYTES:], expected + 1)
    except zlib.error as exc:
        raise ChatStorageError("聊天正文压缩内容已损坏") from exc
    if len(raw) != expected or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ChatStorageError("聊天正文压缩长度或结束标记无效")
    if not hmac.compare_digest(hashlib.sha256(raw).digest(), value[_LENGTH_END:_HEADER_BYTES]):
        raise ChatStorageError("聊天正文完整性校验失败")
    return raw


def decode_body(value: str | bytes) -> str:
    """Read exact original text; legacy TEXT has no stored integrity checksum."""
    if isinstance(value, str):
        _encode_utf8(value)
        return value
    if not isinstance(value, bytes):
        raise ChatStorageError("聊天正文存储类型无效")
    raw = _unpack(value)
    try:
        return raw.decode("utf-8")
    except UnicodeError as exc:
        raise ChatStorageError("聊天正文压缩文本编码无效") from exc
