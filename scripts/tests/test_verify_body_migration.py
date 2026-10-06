"""The migration proof must never accept unrelated data or overwrite a source."""

import importlib.util
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[1]))
SPEC = importlib.util.spec_from_file_location(
    "verify_body_migration", Path(__file__).parents[1] / "verify_body_migration.py",
)
proof = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(proof)


@pytest.mark.parametrize("choice", ["same", "nested", "existing-target", "report-in-source", "existing-report", "wrong-source"])
def test_copy_guard_rejects_bad_scope_before_creating_a_copy(tmp_path, choice):
    source = tmp_path / "synthetic-source"
    source.mkdir()
    original = source / "chat.sqlite3"
    original.write_bytes(b"unrelated-original-database")
    target, report = tmp_path / "new-copy", tmp_path / "report.json"
    if choice == "same":
        target = source
    elif choice == "nested":
        target = source / "nested"
    elif choice == "existing-target":
        target.mkdir()
    elif choice == "report-in-source":
        report = source / "report.json"
    elif choice == "existing-report":
        report.write_bytes(b"existing-proof")
    before = original.read_bytes()
    with pytest.raises(proof.CheckFailure if hasattr(proof, "CheckFailure") else AssertionError):
        proof.run(source, target, report)
    assert original.read_bytes() == before
    if choice not in ("same", "existing-target"):
        assert not target.exists()
    if choice == "existing-report":
        assert report.read_bytes() == b"existing-proof"


@pytest.mark.parametrize("choice", ["report-in-source", "nested-target", "wrong-source", "existing-report", "missing-report-parent"])
def test_cli_failure_never_publishes_to_a_rejected_report_path(tmp_path, monkeypatch, capsys, choice):
    source = tmp_path / "synthetic-source"
    source.mkdir()
    original = source / "chat.sqlite3"
    original.write_bytes(b"unrelated-original-database")
    target, report = tmp_path / "new-copy", tmp_path / "failure.json"
    if choice == "report-in-source":
        report = source / "failure.json"
    elif choice == "nested-target":
        target = source / "nested"
    elif choice == "existing-report":
        report.write_bytes(b"existing-proof")
    elif choice == "missing-report-parent":
        report = tmp_path / "absent" / "failure.json"
    monkeypatch.setattr(sys, "argv", ["verify_body_migration.py", "--source-dir", str(source),
                                     "--data-dir", str(target), "--report", str(report)])
    before = original.read_bytes()
    with pytest.raises(SystemExit) as exited:
        proof.main()
    assert exited.value.code == 1
    assert json.loads(capsys.readouterr().out) == {"status": "failed", "failure_type": "CheckFailure"}
    assert original.read_bytes() == before
    assert not target.exists()
    if choice == "existing-report":
        assert report.read_bytes() == b"existing-proof"
    else:
        assert not report.exists()
    assert list(source.iterdir()) == [original]
