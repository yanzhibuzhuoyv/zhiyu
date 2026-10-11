"""Apply the small Qwen Edge external-conditioning fork to one pinned checkout.

Does not download or touch model weights. Run on an independently cloned runtime;
the original qwen-image-edge model directory is never an accepted output target.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

REPOSITORY = Path(__file__).resolve().parents[1]
FORK = REPOSITORY / "native/qwen-edge/diffusion"


def git(source: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", "-C", str(source), *arguments], check=check,
                          capture_output=True, text=True, encoding="utf-8")


def verify_pin(source: Path, lock: dict) -> None:
    commit = git(source, "rev-parse", "HEAD").stdout.strip()
    if commit != lock["commit"]:
        raise ValueError(f"sd.cpp checkout is {commit}, expected {lock['commit']}")
    for relative, expected in lock["submodules"].items():
        actual = git(source / relative, "rev-parse", "HEAD").stdout.strip()
        if actual != expected:
            raise ValueError(f"submodule {relative} is {actual}, expected {expected}")


def verify_prepared(source: Path) -> dict:
    """Read-only configure gate: a matching revision alone cannot prove the fork."""
    source = source.resolve(strict=True)
    if any(part.lower() == 'qwen-image-edge' for part in source.parts):
        raise ValueError('refusing to use the original model directory')
    lock = json.loads((FORK / 'upstream-lock.json').read_text(encoding='utf-8'))
    verify_pin(source, lock)
    patch = FORK / 'patches/external-condition.patch'
    if hashlib.sha256(patch.read_bytes()).hexdigest() != lock['patch_sha256']:
        raise ValueError('patch content differs from pinned hash')
    for name, target in (('qwen_edge_external_condition.h', 'include'),
                         ('qwen_edge_condition.hpp', 'src/conditioning')):
        destination = source / target / name
        if (not destination.resolve().is_relative_to(source) or not destination.is_file()
                or destination.read_bytes() != (FORK / 'include' / name).read_bytes()):
            raise ValueError(f'Expected exact prepared fork header: {name}')
    if git(source, 'apply', '--reverse', '--check', str(patch), check=False).returncode:
        raise ValueError('Expected fork patch is not applied')
    return {'source': str(source), 'upstream_commit': lock['commit'], 'verified_only': True}


def prepare(source: Path) -> dict:
    source = source.resolve(strict=True)
    if any(part.lower() == "qwen-image-edge" for part in source.parts):
        raise ValueError("refusing to modify the original model directory")
    lock = json.loads((FORK / "upstream-lock.json").read_text(encoding="utf-8"))
    verify_pin(source, lock)
    patch = FORK / "patches/external-condition.patch"
    patch_hash = hashlib.sha256(patch.read_bytes()).hexdigest()
    if patch_hash != lock["patch_sha256"]:
        raise ValueError("patch content differs from pinned hash")
    headers = {
        "qwen_edge_external_condition.h": "include/qwen_edge_external_condition.h",
        "qwen_edge_condition.hpp": "src/conditioning/qwen_edge_condition.hpp",
    }
    for filename, relative in headers.items():
        header = FORK / "include" / filename
        destination = source / relative
        if not destination.resolve().is_relative_to(source):
            raise ValueError(f"fork header destination escapes checkout: {relative}")
        if destination.exists() and destination.read_bytes() != header.read_bytes():
            raise ValueError(f"refusing to overwrite changed fork header {relative}")
    already_applied = git(source, "apply", "--reverse", "--check", str(patch), check=False).returncode == 0
    if not already_applied:
        git(source, "apply", "--check", str(patch))
        git(source, "apply", str(patch))
    copied = {}
    for filename, relative in headers.items():
        header = FORK / "include" / filename
        destination = source / relative
        shutil.copyfile(header, destination)
        copied[relative] = hashlib.sha256(header.read_bytes()).hexdigest()
    return {"upstream_commit": lock["commit"], "patch_sha256": patch_hash,
            "submodules": lock["submodules"], "copied_headers_sha256": copied,
            "already_applied": already_applied, "source": str(source)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    options = parser.parse_args()
    report = verify_prepared(options.source) if options.verify_only else prepare(options.source)
    if options.report:
        with options.report.open("x", encoding="utf-8") as output:
            json.dump(report, output, indent=2, ensure_ascii=False)
    print(json.dumps({"upstream_commit": report["upstream_commit"],
                      "verified_only": options.verify_only,
                      "already_applied": report.get("already_applied")}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
