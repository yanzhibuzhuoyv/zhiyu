import importlib.util
import json
from pathlib import Path

import pytest

_PATH = Path(__file__).resolve().parents[2] / "scripts" / "import_legacy_rag.py"
_SPEC = importlib.util.spec_from_file_location("legacy_import", _PATH)
legacy_import = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(legacy_import)


def make_metadata(tmp_path, entries):
    source = tmp_path / "metadata.json"
    source.write_text(json.dumps({"entries": entries}), encoding="utf-8")
    return source


def test_legacy_import_groups_chunks_and_never_follows_source_paths(tmp_path):
    source = make_metadata(
        tmp_path,
        {
            "1": {"source": r"C:\private\missing.pdf", "chunk_index": 1, "text": "内容。后半段。"},
            "0": {"source": r"C:\private\missing.pdf", "chunk_index": 0, "text": "前半段。内容。"},
            "2": {"source": "../../missing.pdf", "chunk_index": 0, "text": "另一份资料。"},
        },
    )
    original = source.read_bytes()

    documents, chunks = legacy_import.read_legacy_documents(source)

    assert chunks == 3
    assert len(documents) == 2
    assert documents[0][0] != documents[1][0]
    assert documents[0][0].endswith(".md")
    assert documents[0][1].decode() == "前半段。内容。后半段。"
    assert source.read_bytes() == original
    assert not (tmp_path / "missing.pdf").exists()


def test_dry_run_reports_counts_without_upload_token_or_private_text(tmp_path, monkeypatch, capsys):
    source = make_metadata(
        tmp_path,
        {
            "0": {"source": "private-name.txt", "chunk_index": 0, "text": "private body"},
        },
    )
    monkeypatch.setattr(
        legacy_import, "upload_document", lambda *args: pytest.fail("dry run uploaded")
    )
    monkeypatch.delenv("ZHIYU_TOKEN", raising=False)

    assert legacy_import.main(["--source", str(source), "--dry-run"]) == 0

    output = capsys.readouterr().out
    assert "1 个文档" in output
    assert "1 个块" in output
    assert "private" not in output


def test_import_requires_token_and_https_for_remote_destination(tmp_path, monkeypatch, capsys):
    source = make_metadata(
        tmp_path,
        {
            "0": {"source": "private-name.txt", "chunk_index": 0, "text": "private body"},
        },
    )
    monkeypatch.setenv("ZHIYU_TOKEN", "session-token")

    assert legacy_import.main(["--source", str(source), "--api-url", "http://example.com"]) == 1
    assert "HTTPS" in capsys.readouterr().err


def test_import_uploads_with_current_user_token_and_is_read_only(tmp_path, monkeypatch):
    source = make_metadata(
        tmp_path,
        {
            "0": {"source": "old.txt", "chunk_index": 0, "text": "knowledge body"},
        },
    )
    original = source.read_bytes()
    monkeypatch.setenv("ZHIYU_TOKEN", "session-token")
    uploads = []
    monkeypatch.setattr(legacy_import, "upload_document", lambda *args: uploads.append(args))

    assert legacy_import.main(["--source", str(source)]) == 0

    assert len(uploads) == 1
    assert uploads[0][0:2] == ("http://127.0.0.1:8000", "session-token")
    assert uploads[0][2].endswith(".md")
    assert source.read_bytes() == original


def test_invalid_legacy_metadata_is_rejected(tmp_path):
    source = make_metadata(tmp_path, {"0": {"source": "old", "text": None}})

    with pytest.raises(ValueError):
        legacy_import.read_legacy_documents(source)


@pytest.mark.parametrize(
    "url",
    [
        "https://user:password@example.com",
        "https://example.com?key=private",
        "https://example.com/#private",
        "http://127.0.0.1:bad",
        "https://example.com/api",
    ],
)
def test_import_rejects_ambiguous_or_credential_containing_urls(url):
    with pytest.raises(ValueError):
        legacy_import.validate_api_url(url)


def test_import_cannot_silently_use_an_anonymous_shared_library(tmp_path, monkeypatch, capsys):
    source = make_metadata(
        tmp_path,
        {
            "0": {"source": "old.txt", "chunk_index": 0, "text": "knowledge body"},
        },
    )
    monkeypatch.delenv("ZHIYU_TOKEN", raising=False)
    monkeypatch.setattr(legacy_import.sys.stdin, "isatty", lambda: False)

    assert legacy_import.main(["--source", str(source)]) == 1

    assert "登录令牌" in capsys.readouterr().err
