"""Read-only exact credential scan. No credential, excerpt or credential digest is emitted."""
import argparse
import json
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def contains(stream, needles):
    tail = b""
    overlap = max(map(len, needles)) - 1
    while chunk := stream.read(1024 * 1024):
        data = tail + chunk
        if any(needle in data for needle in needles):
            return True
        tail = data[-overlap:] if overlap else b""
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--credential-source", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    if args.report.exists():
        raise ValueError("Use a fresh report path")
    original = args.credential_source.read_bytes()
    key = original.decode("utf-8-sig").strip()
    if not 1 <= len(key) <= 4096 or any(not 33 <= ord(char) <= 126 for char in key):
        raise ValueError("Invalid credential source format")
    needles = tuple(key.encode(encoding) for encoding in ("utf-8", "utf-16-le", "utf-16-be"))
    files = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
                           cwd=ROOT, check=True, capture_output=True).stdout.split(b"\0")
    source_count = source_matches = archive_count = archive_matches = 0
    for raw in files:
        if not raw:
            continue
        path = ROOT / raw.decode("utf-8")
        if not path.is_file():
            continue
        source_count += 1
        with path.open("rb") as stream:
            source_matches += int(contains(stream, needles))
    # Includes native binaries/models and DEX; does not extract or execute archive entries.
    with zipfile.ZipFile(args.apk) as archive:
        for item in archive.infolist():
            if item.is_dir():
                continue
            if item.file_size > 256 * 1024 * 1024:
                raise ValueError("Unexpected oversized APK entry")
            archive_count += 1
            with archive.open(item) as stream:
                archive_matches += int(contains(stream, needles))
    report = {"source_files_scanned": source_count, "source_credential_matches": source_matches,
              "apk_entries_scanned": archive_count, "apk_credential_matches": archive_matches,
              "credential_source_unchanged": args.credential_source.read_bytes() == original,
              "credential_digest_recorded": False, "credential_in_arguments": False}
    report["passed"] = source_matches == archive_matches == 0 and report["credential_source_unchanged"]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report))
    if not report["passed"]:
        raise SystemExit("Delivery credential check failed; no sensitive excerpts emitted")


if __name__ == "__main__":
    main()
