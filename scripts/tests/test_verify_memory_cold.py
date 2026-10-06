import importlib.util
import json
import sys
from pathlib import Path
from unittest.mock import patch

import pytest

sys.path.insert(0, str(Path(__file__).parents[1]))
sys.path.insert(0, str(Path(__file__).parents[2] / "backend"))
SPEC = importlib.util.spec_from_file_location("verify_memory_cold", Path(__file__).parents[1] / "verify_memory_cold.py")
proof = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(proof)

RUN = "f" * 32


def workspace(tmp_path, monkeypatch):
    root = tmp_path / f"SYNTHETIC-MEMORY-COLD-{RUN}"
    data = root / "data"
    data.mkdir(parents=True)
    database = data / "chat.sqlite3"
    database.write_bytes(b"synthetic-copy-only")
    monkeypatch.setenv("ZHIYU_TEST_SCOPE", "memory-cold-copy")
    monkeypatch.setenv("ZHIYU_TEST_RUN", RUN)
    monkeypatch.setattr(proof, "SOURCE_SHA", proof.file_sha(database))
    marker = {"scope": "memory-cold-copy", "run_id": RUN,
              "source_sha256": proof.SOURCE_SHA, "directory": "data"}
    (root / "SYNTHETIC_MEMORY_COLD_COPY.json").write_text(json.dumps(marker), encoding="utf-8")
    return data, root / "report.json"


@pytest.mark.parametrize("suffix", ["-wal", "-shm"])
def test_guard_rejects_hardlinked_sidecar_without_touching_alias(tmp_path, monkeypatch, suffix):
    data, report = workspace(tmp_path, monkeypatch)
    outside = tmp_path / ("outside" + suffix)
    outside.write_bytes(b"")
    (data / ("chat.sqlite3" + suffix)).hardlink_to(outside)
    digest = proof.file_sha(outside)
    with pytest.raises(proof.CheckFailure):
        proof.guard(data, report, RUN)
    assert proof.file_sha(outside) == digest and not report.exists()


@pytest.mark.parametrize("choice", ["missing-scope", "wrong-run", "bad-run", "wrong-name",
                                    "existing-report", "report-in-data", "wrong-sha", "bad-marker",
                                    "existing-compacted", "pending-wal"])
def test_guard_rejects_unowned_paths_before_mutation(tmp_path, monkeypatch, choice):
    data, report = workspace(tmp_path, monkeypatch)
    original = (data / "chat.sqlite3").read_bytes()
    run = RUN
    if choice == "missing-scope":
        monkeypatch.delenv("ZHIYU_TEST_SCOPE")
    elif choice == "wrong-run":
        monkeypatch.setenv("ZHIYU_TEST_RUN", "a" * 32)
    elif choice == "bad-run":
        run = "../unsafe"
    elif choice == "wrong-name":
        data = data.parent
    elif choice == "existing-report":
        report.write_bytes(b"keep-report")
    elif choice == "report-in-data":
        report = data / "report.json"
    elif choice == "wrong-sha":
        monkeypatch.setattr(proof, "SOURCE_SHA", "0" * 64)
    elif choice == "bad-marker":
        (data.parent / "SYNTHETIC_MEMORY_COLD_COPY.json").write_text("{}")
    elif choice == "existing-compacted":
        (data.parent / "compacted").mkdir()
    else:
        (data / "chat.sqlite3-wal").write_bytes(b"pending-commit")
    with pytest.raises(proof.CheckFailure):
        proof.guard(data, report, run)
    original_path = tmp_path / f"SYNTHETIC-MEMORY-COLD-{RUN}" / "data" / "chat.sqlite3"
    assert original_path.read_bytes() == original
    assert not (original_path.parent.parent / "compacted" / "chat.sqlite3").exists()
    if choice == "existing-report":
        assert report.read_bytes() == b"keep-report"
    else:
        assert not report.exists()


def test_cli_rejected_scope_never_writes_failure_report_or_exposes_data(tmp_path, monkeypatch, capsys):
    data, report = workspace(tmp_path, monkeypatch)
    monkeypatch.delenv("ZHIYU_TEST_SCOPE")
    monkeypatch.setattr(sys, "argv", ["verify_memory_cold.py", "--data-dir", str(data),
                                     "--report", str(report), "--run-id", RUN])
    with pytest.raises(SystemExit) as exited:
        proof.main()
    assert exited.value.code == 1
    assert json.loads(capsys.readouterr().out) == {"status": "failed", "failure_type": "CheckFailure"}
    assert not report.exists()


def test_guard_rejects_hardlink_aliases_to_any_other_file(tmp_path, monkeypatch):
    data, report = workspace(tmp_path, monkeypatch)
    other = tmp_path / "original-alias"
    try:
        other.hardlink_to(data / "chat.sqlite3")
    except OSError:
        pytest.skip("Hardlinks are unavailable on this temporary filesystem")
    before = other.read_bytes()
    with pytest.raises(proof.CheckFailure):
        proof.guard(data, report, RUN)
    assert other.read_bytes() == before


def fixture_chat(data):
    from app.database import ChatDatabase
    from smoke_long_fields import long_payload

    (data / "chat.sqlite3").unlink()
    chat = ChatDatabase(data)
    identifier = chat.create_conversation("never-print-owner", "synthetic", "demo", False)["id"]
    for turn in (1, 250, 9000):
        chat.save_turn("never-print-owner", identifier, long_payload(2200, turn, "user"),
                       long_payload(4000, turn, "assistant"), [])
    for index in range(25):
        chat.save_turn("never-print-owner", identifier, f"filler-{index}", "quiet reply", [])
    with chat.connection() as db:
        from app.body_codec import decode_body

        for sequence, content in db.execute("SELECT sequence,content FROM messages").fetchall():
            db.execute("UPDATE messages SET content=? WHERE sequence=?", (decode_body(content), sequence))
    with chat.connection() as db:
        db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    return chat, identifier


def test_cooling_batch_fault_rolls_back_originals_directory_state_and_fts(tmp_path, monkeypatch):
    data, _ = workspace(tmp_path, monkeypatch)
    chat, identifier = fixture_chat(data)
    baseline = proof.canonical(chat)["digests"]
    original = chat.cold.cool
    calls = []

    def fail(*args, **kwargs):
        original(*args, **kwargs)
        calls.append(args[1])
        if len(calls) == 2:
            raise proof.CheckFailure("synthetic batch fault")

    monkeypatch.setattr(chat.cold, "cool", fail)
    with pytest.raises(proof.CheckFailure):
        proof.cool_batches(chat, "never-print-owner", identifier)
    assert proof.canonical(chat)["digests"] == baseline
    with chat.connection() as db:
        assert db.execute("SELECT count(*) FROM memory_cold_directory").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM memory_index_state").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM memory_fragments").fetchone()[0] > 0


def test_small_real_copy_verification_restart_warm_compact_and_readonly_probes(tmp_path, monkeypatch, capsys):
    data, report = workspace(tmp_path, monkeypatch)
    chat, _ = fixture_chat(data)
    monkeypatch.setattr(proof, "EXPECTED_ROWS", 56)
    monkeypatch.setattr(proof, "SOURCE_SHA", proof.file_sha(chat.path))
    marker = data.parent / "SYNTHETIC_MEMORY_COLD_COPY.json"
    marker.write_text(json.dumps({"scope": "memory-cold-copy", "run_id": RUN,
                                 "source_sha256": proof.SOURCE_SHA, "directory": "data"}))
    result = proof.run(data, report, RUN)
    assert result["status"] == "passed"
    assert result["cold"]["cold_messages"] == 56
    assert result["cold"]["active_messages"] == 0
    assert result["restart"]["fragments"] == 0
    assert len(result["cold_probes"]["normal_context"]) == 4
    assert result["canonical_unchanged"] and result["compacted"]["canonical_unchanged"]
    assert result["warm"]["warmed"] == 2
    assert result["body_migration"]["visited"] == 56 and result["body_migration"]["converted"] == 6
    assert result["compacted"]["readonly"]
    assert "never-print-owner" not in json.dumps(result)
    assert "LONGFIELD" not in report.read_text(encoding="utf-8")
    assert "never-print-owner" not in capsys.readouterr().out


def test_early_probe_uses_cursor_pages_and_verifies_the_original_excerpt(tmp_path, monkeypatch):
    data, _ = workspace(tmp_path, monkeypatch)
    chat, identifier = fixture_chat(data)
    samples = proof.canonical(chat)["samples"]
    proof.cool_batches(chat, "never-print-owner", identifier)
    original = chat.cold.search
    with chat.connection() as db:
        bound = db.execute("""SELECT max(pair_bytes)+64 FROM (SELECT (sequence-1)/2 pair,
            sum(length(bloom)) pair_bytes FROM memory_cold_directory GROUP BY pair)""").fetchone()[0]

    def bounded(*args, **kwargs):
        if "cursor" in kwargs:
            with patch("app.memory_cold_search.MAX_DIRECTORY_BYTES", bound):
                return original(*args, **kwargs)
        return original(*args, **kwargs)

    monkeypatch.setattr(chat.cold, "search", bounded)
    result = proof.probes(chat, "never-print-owner", identifier, samples)
    assert len(result["normal_context"]) == 4
    assert all(item["passed"] for item in result["early_paged_recovery"])
    assert any(item["pages"] > 1 for item in result["early_paged_recovery"])
