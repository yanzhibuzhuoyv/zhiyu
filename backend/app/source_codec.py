"""Lossless source JSON storage, with bounded decoding and old TEXT compatibility."""

import json
import zlib

MAGIC = b"ZYSC1\0"
MAX_SOURCE_BYTES = 1024 * 1024
COMPRESSION_THRESHOLD = 1024
_HEADER_BYTES = len(MAGIC) + 4


class ChatStorageError(RuntimeError):
    """Stored chat data is invalid; API handlers must not expose its raw value."""


def _validate_sources(value) -> list:
    if not isinstance(value, list) or any(not isinstance(source, dict) for source in value):
        raise ChatStorageError("聊天来源数据格式无效")
    return value


def _reject_nonfinite(value: str):
    raise ValueError("Non-finite JSON number")


def encode_sources(sources: list) -> str | bytes:
    _validate_sources(sources)
    try:
        text = json.dumps(sources, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        raw = text.encode("utf-8")
    except (ValueError, TypeError, RecursionError, UnicodeError) as exc:
        raise ChatStorageError("聊天来源数据无法编码") from exc
    if len(raw) > MAX_SOURCE_BYTES:
        raise ChatStorageError("聊天来源数据超过存储上限")
    if len(raw) < COMPRESSION_THRESHOLD:
        return text
    packed = MAGIC + len(raw).to_bytes(4, "big") + zlib.compress(raw, level=6)
    return packed if len(packed) <= len(raw) * 0.75 else text


def _unpack(value: bytes) -> bytes:
    if not value.startswith(MAGIC) or not _HEADER_BYTES < len(value) <= MAX_SOURCE_BYTES + _HEADER_BYTES:
        raise ChatStorageError("聊天来源压缩格式无效")
    expected = int.from_bytes(value[len(MAGIC):_HEADER_BYTES], "big")
    if not 1 <= expected <= MAX_SOURCE_BYTES:
        raise ChatStorageError("聊天来源展开长度无效")
    try:
        decoder = zlib.decompressobj()
        raw = decoder.decompress(value[_HEADER_BYTES:], MAX_SOURCE_BYTES + 1)
    except zlib.error as exc:
        raise ChatStorageError("聊天来源压缩内容已损坏") from exc
    if (len(raw) != expected or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail):
        raise ChatStorageError("聊天来源压缩长度或结束标记无效")
    return raw


def decode_sources(value: str | bytes) -> list:
    if isinstance(value, bytes):
        raw = _unpack(value)
    elif isinstance(value, str) and len(value) <= MAX_SOURCE_BYTES:
        try:
            raw = value.encode("utf-8")
        except UnicodeError as exc:
            raise ChatStorageError("聊天来源文本编码无效") from exc
    else:
        raise ChatStorageError("聊天来源存储类型或长度无效")
    if len(raw) > MAX_SOURCE_BYTES:
        raise ChatStorageError("聊天来源文本超过读取上限")
    try:
        decoded = json.loads(raw.decode("utf-8"), parse_constant=_reject_nonfinite)
        # Escaped lone surrogates and overflowed exponents may survive json.loads,
        # but cannot be safely emitted by the API's UTF-8 JSON response.
        json.dumps(decoded, ensure_ascii=False, allow_nan=False).encode("utf-8")
    except (ValueError, RecursionError) as exc:
        raise ChatStorageError("聊天来源 JSON 内容已损坏") from exc
    return _validate_sources(decoded)
