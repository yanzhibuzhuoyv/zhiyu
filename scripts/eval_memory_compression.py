"""Read-only, per-original lossless compression study; never a production migration.

Reports encoded payload bytes, not a rewritten database's size or RAG quality.
Use a stopped, consistent synthetic SQLite copy, never a live user database.
"""

import argparse
import hashlib
import json
import sqlite3
import time
import zlib
from contextlib import closing
from pathlib import Path

MAGIC = b"ZMC1"
HEADER_BYTES = 9
MAX_BODY_BYTES = 16 * 1024 * 1024


def encode_candidate(text, level):
    if type(level) is not int or not 1 <= level <= 9:
        raise ValueError("Compression level must be an integer from 1 to 9")
    if not isinstance(text, str):
        raise TypeError("Expected original TEXT body")
    raw = text.encode("utf-8")
    if len(raw) > MAX_BODY_BYTES:
        raise ValueError("Body exceeds research decoding bound")
    compressed = zlib.compress(raw, level)
    codec, payload = (b"Z", compressed) if len(compressed) < len(raw) else (b"R", raw)
    return MAGIC + codec + len(raw).to_bytes(4, "big") + payload


def decode_candidate(value):
    if not isinstance(value, bytes) or not HEADER_BYTES <= len(value) <= MAX_BODY_BYTES + HEADER_BYTES:
        raise ValueError("Invalid research frame length")
    if value[:4] != MAGIC or value[4:5] not in (b"Z", b"R"):
        raise ValueError("Unknown research frame")
    expected = int.from_bytes(value[5:9], "big")
    if expected > MAX_BODY_BYTES:
        raise ValueError("Body exceeds research decoding bound")
    try:
        if value[4:5] == b"R":
            raw = value[9:]
        else:
            decoder = zlib.decompressobj()
            raw = decoder.decompress(value[9:], expected + 1)
            if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
                raise ValueError("Invalid compressed end marker or expansion")
        if len(raw) != expected:
            raise ValueError("Restored body length differs")
        return raw.decode("utf-8")
    except (zlib.error, UnicodeError) as error:
        raise ValueError("Corrupt research frame") from error


def fingerprints(path):
    result = {}
    for suffix in ("", "-wal", "-shm"):
        file = Path(str(path) + suffix)
        if file.exists():
            with file.open("rb") as stream:
                result[file.name] = {
                    "bytes": file.stat().st_size,
                    "sha256": hashlib.file_digest(stream, "sha256").hexdigest(),
                }
    return result


def update_digest(digest, row):
    # Framed, ordered complete rows include message IDs, author, time and sources.
    fields = {key: {"blob_hex": value.hex()} if isinstance(value, bytes) else value
              for key, value in row.items()}
    payload = json.dumps(fields, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    digest.update(len(payload).to_bytes(8, "big"))
    digest.update(payload)


def original_offsets(db, sequence, text):
    offsets = list(db.execute(
        "SELECT char_start,char_end FROM memory_fragments WHERE message_sequence=? ORDER BY char_start",
        (sequence,),
    ))
    if any(not 0 <= start <= end <= len(text) for start, end in offsets):
        raise ValueError("Stored fragment is outside its original Unicode body")
    return offsets


def measure_row(row, offsets, level, result, digest):
    started = time.perf_counter()
    encoded = encode_candidate(row["content"], level)
    encode_seconds = time.perf_counter() - started
    started = time.perf_counter()
    restored = decode_candidate(encoded)
    decode_seconds = time.perf_counter() - started
    if restored.encode() != row["content"].encode():
        raise ValueError("Restored original differs")
    if any(restored[start:end] != row["content"][start:end] for start, end in offsets):
        raise ValueError("Restored original fragment differs")
    update_digest(digest, {**row, "content": restored})
    return {
        **result,
        "encoded_body_bytes": result["encoded_body_bytes"] + len(encoded),
        "compressed_messages": result["compressed_messages"] + int(encoded[4:5] == b"Z"),
        "raw_fallback_messages": result["raw_fallback_messages"] + int(encoded[4:5] == b"R"),
        "verified_messages": result["verified_messages"] + 1,
        "verified_fragment_offsets": result["verified_fragment_offsets"] + len(offsets),
        "encode_seconds": result["encode_seconds"] + encode_seconds,
        "decode_seconds": result["decode_seconds"] + decode_seconds,
    }


def study_rows(db, levels):
    empty = {"encoded_body_bytes": 0, "compressed_messages": 0, "raw_fallback_messages": 0,
             "verified_messages": 0, "verified_fragment_offsets": 0,
             "encode_seconds": 0.0, "decode_seconds": 0.0}
    results = {str(level): dict(empty) for level in levels}
    digests = {str(level): hashlib.sha256() for level in levels}
    original_digest = hashlib.sha256()
    count = raw_bytes = fragment_count = 0
    for values in db.execute("SELECT * FROM messages ORDER BY sequence"):
        row = dict(values)
        if not isinstance(row["content"], str) or row["role"] not in ("user", "assistant"):
            raise ValueError("Unexpected original body or author")
        offsets = original_offsets(db, row["sequence"], row["content"])
        update_digest(original_digest, row)
        count, raw_bytes = count + 1, raw_bytes + len(row["content"].encode())
        fragment_count += len(offsets)
        results = {str(level): measure_row(row, offsets, level, results[str(level)], digests[str(level)])
                   for level in levels}
    if fragment_count != db.execute("SELECT count(*) FROM memory_fragments").fetchone()[0]:
        raise ValueError("Fragments without matching originals")
    return {
        "messages": count, "fragments": fragment_count, "raw_body_bytes": raw_bytes,
        "original_rows_sha256": original_digest.hexdigest(),
        "candidates": {key: {**value, "restored_rows_sha256": digests[key].hexdigest(),
                             "body_saving_fraction": 1 - value["encoded_body_bytes"] / raw_bytes
                             if raw_bytes else None} for key, value in results.items()},
    }


def evaluate_database(path, levels=(1, 6, 9)):
    path = path.resolve(strict=True)
    if not path.is_file():
        raise ValueError("Input must be an existing database file")
    if not levels or len(set(levels)) != len(levels):
        raise ValueError("Choose distinct compression levels")
    for level in levels:
        encode_candidate("", level)
    started = time.perf_counter()
    before = fingerprints(path)
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as db:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA query_only=ON")
        db.execute("BEGIN")
        result = study_rows(db, levels)
        if db.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise ValueError("Input database quick_check failed")
        pages = {name: db.execute("PRAGMA " + name).fetchone()[0]
                 for name in ("page_size", "page_count", "freelist_count")}
    after = fingerprints(path)
    if before != after:
        raise ValueError("Input files changed during read-only study")
    return {
        "status": "passed", "scope": "offline per-message encoded payload; production unchanged",
        "limitation": "No full SQLite rewrite, page savings, active/cold index policy, retrieval quality or natural-chat compression guarantee tested. Offsets are checked, not FTS query equivalence.",
        "frame": "ZMC1 + raw/zlib byte + uint32 UTF-8 length; 9 bytes per original included",
        "max_decoded_body_bytes": MAX_BODY_BYTES, "sqlite_version": sqlite3.sqlite_version,
        "source_before": before, "source_after": after, "source_unchanged": True,
        "source_pages": pages, "elapsed_seconds": time.perf_counter() - started, **result,
    }


def check_output_path(source, output):
    source, output = source.resolve(), output.resolve()
    if output == source or output.is_relative_to(source.parent):
        raise ValueError("Output must be outside the input database directory")
    if output.exists():
        raise ValueError("Do not overwrite existing research output")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    check_output_path(args.database, args.output)
    report = evaluate_database(args.database)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("status", "messages", "fragments", "raw_body_bytes")}))


if __name__ == "__main__":
    main()
