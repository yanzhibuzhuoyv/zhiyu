"""Lossless archive research must preserve originals and never migrate its input."""

import importlib.util
import json
import sqlite3
import sys
from contextlib import closing
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "eval_memory_compression", Path(__file__).parents[1] / "eval_memory_compression.py",
)
study = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = study
SPEC.loader.exec_module(study)


@pytest.mark.parametrize("text", ["", "我想吃苹果🙂\n", "e\u0301é\0" * 300, "承诺🌙" * 16000],
                         ids=["empty", "short", "combining-and-nul", "long-emoji"])
@pytest.mark.parametrize("level", [1, 6, 9])
def test_round_trip_keeps_exact_unicode_bytes_and_codepoint_offsets(text, level):
    packed = study.encode_candidate(text, level)
    restored = study.decode_candidate(packed)
    assert restored == text
    assert restored.encode() == text.encode()
    assert restored[3:17] == text[3:17]


def test_short_or_uncompressible_content_uses_raw_fallback_with_header_counted():
    packed = study.encode_candidate("🙂", 6)
    assert packed[4:5] == b"R"
    assert len(packed) == len("🙂".encode()) + study.HEADER_BYTES


@pytest.mark.parametrize("value", [b"", b"wrong-format", b"ZMC1Q\0\0\0\0"])
def test_unknown_or_truncated_storage_is_rejected(value):
    with pytest.raises(ValueError):
        study.decode_candidate(value)


def test_corruption_length_expansion_and_trailing_stream_are_rejected():
    packed = study.encode_candidate("多条原文保持独立" * 2000, 6)
    for bad in (packed[:-1], packed + b"trailing", packed[:5] + (1).to_bytes(4, "big") + packed[9:],
                packed[:5] + (study.MAX_BODY_BYTES + 1).to_bytes(4, "big") + packed[9:]):
        with pytest.raises(ValueError):
            study.decode_candidate(bad)


@pytest.mark.parametrize("level", [0, 10, True])
def test_invalid_parameters_and_oversize_are_rejected(level):
    with pytest.raises(ValueError):
        study.encode_candidate("原话", level)


def fixture(path):
    with closing(sqlite3.connect(path)) as db, db:
        db.executescript("""
            CREATE TABLE messages(sequence INTEGER PRIMARY KEY, id TEXT, role TEXT,
                content TEXT, sources BLOB, created_at TEXT, conversation_id TEXT);
            CREATE TABLE memory_fragments(message_sequence INTEGER, char_start INTEGER,
                char_end INTEGER);
            CREATE INDEX fragment_order ON memory_fragments(message_sequence,char_start);
        """)
        rows = [(1, "user-1", "user", "苹果🙂e\u0301\n" * 500, b"blob-source", "utc-1", "chat"),
                (2, "ai-1", "assistant", "回应\0🌙" * 900, "[]", "utc-2", "chat")]
        db.executemany("INSERT INTO messages VALUES(?,?,?,?,?,?,?)", rows)
        for row in rows:
            length = len(row[3])
            db.executemany("INSERT INTO memory_fragments VALUES(?,?,?)",
                           ((row[0], offset, min(offset + 768, length))
                            for offset in range(0, length, 640)))
    return rows


def test_database_study_is_read_only_streams_all_rows_and_checks_all_original_offsets(tmp_path):
    path = tmp_path / "source.sqlite3"
    rows = fixture(path)
    before = path.read_bytes()
    report = study.evaluate_database(path)
    assert report["status"] == "passed"
    assert report["messages"] == 2
    assert report["raw_body_bytes"] == sum(len(row[3].encode()) for row in rows)
    assert report["source_unchanged"] and path.read_bytes() == before
    assert report["fragments"] == 11
    assert set(report["candidates"]) == {"1", "6", "9"}
    for result in report["candidates"].values():
        assert result["verified_messages"] == 2
        assert result["verified_fragment_offsets"] == report["fragments"]
        assert result["restored_rows_sha256"] == report["original_rows_sha256"]
        assert result["encoded_body_bytes"] < report["raw_body_bytes"]


@pytest.mark.parametrize("mutation", [
    "UPDATE memory_fragments SET char_end=999999 WHERE message_sequence=1",
    "INSERT INTO memory_fragments VALUES(999,0,1)",
    "UPDATE messages SET content=x'00' WHERE sequence=1",
])
def test_bad_original_or_orphan_offsets_stop_the_study_without_changing_source(tmp_path, mutation):
    path = tmp_path / "bad.sqlite3"
    fixture(path)
    with closing(sqlite3.connect(path)) as db, db:
        db.execute(mutation)
    before = path.read_bytes()
    with pytest.raises(ValueError):
        study.evaluate_database(path)
    assert path.read_bytes() == before


def test_missing_input_never_creates_a_database_and_output_cannot_overwrite_it(tmp_path):
    path = tmp_path / "missing.sqlite3"
    with pytest.raises(FileNotFoundError):
        study.evaluate_database(path)
    assert not path.exists()
    fixture(path)
    with pytest.raises(ValueError):
        study.check_output_path(path, path)


def test_body_bound_is_explicit(monkeypatch):
    monkeypatch.setattr(study, "MAX_BODY_BYTES", 32)
    with pytest.raises(ValueError):
        study.encode_candidate("🙂" * 9, 6)


def test_nontext_and_invalid_utf8_fail_instead_of_replacing_original_characters():
    with pytest.raises(TypeError):
        study.encode_candidate(b"bytes", 6)
    with pytest.raises(ValueError):
        study.decode_candidate(b"ZMC1R" + (1).to_bytes(4, "big") + b"\xff")


def test_cli_writes_explicit_payload_report_without_overwriting_source(tmp_path, monkeypatch, capsys):
    source = tmp_path / "source" / "chat.sqlite3"
    source.parent.mkdir()
    fixture(source)
    output = tmp_path / "report.json"
    monkeypatch.setattr(sys, "argv", ["study", "--database", str(source), "--output", str(output)])
    study.main()
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["status"] == "passed" and report["source_unchanged"]
    assert "No full SQLite rewrite" in report["limitation"]
    assert json.loads(capsys.readouterr().out)["messages"] == 2
    before = output.read_bytes()
    with pytest.raises(ValueError):
        study.main()
    assert output.read_bytes() == before


@pytest.mark.parametrize("levels", [(), (1, 1)])
def test_empty_or_duplicate_candidates_are_rejected(tmp_path, levels):
    path = tmp_path / "chat.sqlite3"
    fixture(path)
    with pytest.raises(ValueError):
        study.evaluate_database(path, levels)


def test_directory_input_is_rejected(tmp_path):
    with pytest.raises(ValueError):
        study.evaluate_database(tmp_path)
