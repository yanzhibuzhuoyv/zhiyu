"""Create or stream-verify the pinned ZYQPACK1 offline Qwen model bundle."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import os
import re
import stat
import struct
import tempfile

MAGIC = b"ZYQPACK1"
MANIFEST_SHA = "9232afcbed6d188a9bdc58c0b8841b35bb12773aaee9fc745dc8027641c656e6"
CHUNK_BYTES = 64 * 1024
MAX_MANIFEST_BYTES = 65536
MAX_FILE_BYTES = 256 * 1024**3
ROLES = {"student", "adapter", "dit", "vae"}
SIGMAS = [1.0, .978453, .95418, .926626, .89508, .845148, .704534, .414568, 0.0]


def _plain_path(path, *, missing_final=False):
    """Reject links and Windows reparse points in every component, not only leaf."""
    for component in [*reversed(path.parents), path]:
        try:
            info = os.lstat(component)
        except FileNotFoundError:
            if component == path and missing_final:
                return None
            raise
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("Linked/reparse paths are not permitted")
    return info


def _open_regular(path):
    before = _plain_path(path)
    if not stat.S_ISREG(before.st_mode):
        raise ValueError("Expected a regular model/package file")
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    try:
        after = os.fstat(fd)
        if not stat.S_ISREG(after.st_mode) or not os.path.samestat(before, after):
            raise ValueError("Source changed while opening")
        _plain_path(path)
        return fd
    except BaseException:
        os.close(fd)
        raise


def _read_exact(fd, size, package_hash, cancelled):
    blocks = []
    while size:
        cancelled()
        block = os.read(fd, min(CHUNK_BYTES, size))
        if not block:
            raise ValueError("Truncated package header/manifest")
        blocks.append(block)
        package_hash.update(block)
        size -= len(block)
    return b"".join(blocks)


def _fields(value, expected):
    if not isinstance(value, dict) or value.keys() != expected:
        raise ValueError("Unknown or missing manifest fields")
    return value


def _integer(value, low, high):
    if type(value) not in (int, float) or not math.isfinite(value) or int(value) != value or not low <= value <= high:
        raise ValueError("Invalid manifest integer/size")
    return int(value)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate manifest fields")
        result[key] = value
    return result


def _reject_constant(value):
    raise ValueError("Non-finite JSON values are forbidden")


def _file_specs(value):
    if not isinstance(value, list) or len(value) != 4:
        raise ValueError("Exactly four model files are required")
    for item in value:
        _fields(item, {"role", "path", "bytes", "sha256"})
        path = item["path"]
        if not isinstance(path, str) or not 1 <= len(path) <= 240 or not all(
                re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", part) and part not in (".", "..") for part in path.split("/")):
            raise ValueError("Unsafe model path")
        _integer(item["bytes"], 1, MAX_FILE_BYTES)
        if not isinstance(item["role"], str) or item["role"] not in ROLES:
            raise ValueError("Unsupported model role")
        if not isinstance(item["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
            raise ValueError("Invalid model SHA-256")
    if {item["role"] for item in value} != ROLES or len({item["path"].lower() for item in value}) != 4:
        raise ValueError("Duplicate model roles/paths")
    return value


def _manifest(raw, trusted_sha):
    if not isinstance(trusted_sha, str) or not re.fullmatch(r"[0-9a-f]{64}", trusted_sha):
        raise ValueError("Invalid external trust anchor")
    if not 1 <= len(raw) <= MAX_MANIFEST_BYTES or hashlib.sha256(raw).hexdigest() != trusted_sha:
        raise ValueError("Manifest size or trusted SHA-256 mismatch")
    root = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    _fields(root, {"format_version", "pack_id", "architecture", "capabilities", "files", "runtime", "conditioning", "sampling"})
    if _integer(root["format_version"], 1, 1) != 1 or root["architecture"] != "qwen-image-2.1-turbo":
        raise ValueError("Unsupported model architecture")
    if not isinstance(root["pack_id"], str) or not re.fullmatch(r"[a-z0-9_-]{1,64}", root["pack_id"]):
        raise ValueError("Invalid pack ID")
    if root["capabilities"] != ["text_to_image_background"]:
        raise ValueError("Unsupported model capabilities")
    _file_specs(root["files"])
    runtime = _fields(root["runtime"], {"llama_cpp_commit", "stable_diffusion_cpp_commit", "bridge_version"})
    for key in ("llama_cpp_commit", "stable_diffusion_cpp_commit"):
        if not isinstance(runtime[key], str) or not re.fullmatch(r"[0-9a-f]{40}", runtime[key]):
            raise ValueError("Invalid runtime lock")
    _integer(runtime["bridge_version"], 1, 1)
    condition = _fields(root["conditioning"], {"adapter_format", "in_dim", "out_dim", "layers", "drop_tokens"})
    if condition != {"adapter_format": "zqadap1_f32_le", "in_dim": 6144, "out_dim": 4096,
                     "layers": [-1, -4, -7, -10, -13, -16], "drop_tokens": 14}:
        raise ValueError("Unsupported conditioning")
    sampling = _fields(root["sampling"], {"width", "height", "steps", "sigmas"})
    for key, expected in (("width", 512), ("height", 512), ("steps", 8)):
        _integer(sampling[key], expected, expected)
    grid = sampling["sigmas"]
    if not isinstance(grid, list) or len(grid) != len(SIGMAS) or not all(
            type(actual) in (int, float) and math.isfinite(actual) and abs(actual - expected) <= 1e-9
            for actual, expected in zip(grid, SIGMAS)):
        raise ValueError("Unsupported sampling grid")
    return root


def _source_manifest(source, trusted_sha, cancelled):
    info = _plain_path(source)
    if not stat.S_ISDIR(info.st_mode):
        raise ValueError("Source must be a plain directory")
    fd = _open_regular(source / "manifest.json")
    try:
        blocks, remaining = [], MAX_MANIFEST_BYTES + 1
        while remaining:
            cancelled()
            block = os.read(fd, min(CHUNK_BYTES, remaining))
            if not block:
                break
            blocks.append(block)
            remaining -= len(block)
        raw = b"".join(blocks)
        return raw, _manifest(raw, trusted_sha)
    finally:
        os.close(fd)


def _write_all(fd, data):
    remaining = memoryview(data)
    while remaining:
        count = os.write(fd, remaining)
        if count <= 0:
            raise OSError("Model package writing made no progress")
        remaining = remaining[count:]


def _stream_payload(fd, spec, package_hash, cancelled, output_fd=None):
    digest, remaining = hashlib.sha256(), spec["bytes"]
    while remaining:
        cancelled()
        block = os.read(fd, min(CHUNK_BYTES, remaining))
        if not block:
            raise ValueError("Truncated model payload")
        digest.update(block)
        package_hash.update(block)
        if output_fd is not None:
            _write_all(output_fd, block)
        remaining -= len(block)
    if digest.hexdigest() != spec["sha256"]:
        raise ValueError("Model payload SHA-256 mismatch")


def _copy_source(source, spec, output_fd, package_hash, cancelled):
    path = source / spec["path"]
    fd = _open_regular(path)
    try:
        before = os.fstat(fd)
        if before.st_size != spec["bytes"]:
            raise ValueError("Model source size mismatch")
        _stream_payload(fd, spec, package_hash, cancelled, output_fd)
        cancelled()
        if os.read(fd, 1):
            raise ValueError("Model source grew during copy")
        after = os.fstat(fd)
        if (after.st_size, after.st_mtime_ns, after.st_ctime_ns) != (before.st_size, before.st_mtime_ns, before.st_ctime_ns):
            raise ValueError("Model source changed during copy")
        _plain_path(path)
    finally:
        os.close(fd)


def _receipt(manifest, manifest_size, trusted_sha, package_hash):
    payload_bytes = sum(item["bytes"] for item in manifest["files"])
    return {"format": MAGIC.decode("ascii"), "manifest_sha256": trusted_sha,
            "package_sha256": package_hash.hexdigest(), "package_bytes": 12 + manifest_size + payload_bytes,
            "payload_bytes": payload_bytes, "pack_id": manifest["pack_id"],
            "files": manifest["files"], "verified": True}


def _publish_new(temp, output):
    # Windows rename is atomic and rejects an existing target. POSIX rename would
    # overwrite a concurrent file, so atomically link the same inode there instead.
    _plain_path(output, missing_final=True)
    if os.name == "nt":
        os.rename(temp, output)
    else:
        os.link(temp, output, follow_symlinks=False)
        fd = os.open(output.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def package_model(source: Path, output: Path, *, trusted_sha=MANIFEST_SHA,
                  check_cancelled=lambda: None):
    """trusted_sha injection is for library fixtures; CLI always uses the release pin."""
    source, output = Path(source).absolute(), Path(output).absolute()
    if output.suffix.lower() != ".zyqwen" or output.is_relative_to(source):
        raise ValueError("Output must be a .zyqwen outside the source tree")
    _plain_path(output.parent)
    existing = _plain_path(output, missing_final=True)
    raw, manifest = _source_manifest(source, trusted_sha, check_cancelled)
    if existing is not None:
        try:
            return verify_package(output, trusted_sha=trusted_sha, check_cancelled=check_cancelled)
        except ValueError as failure:
            raise FileExistsError("Existing output differs; it was preserved") from failure
    fd, name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)
    temp = Path(name)
    try:
        digest = hashlib.sha256()
        header = MAGIC + struct.pack(">I", len(raw)) + raw
        _write_all(fd, header)
        digest.update(header)
        for spec in manifest["files"]:
            _copy_source(source, spec, fd, digest, check_cancelled)
        os.fsync(fd)
        os.close(fd)
        fd = None
        check_cancelled()
        _publish_new(temp, output)
        return _receipt(manifest, len(raw), trusted_sha, digest)
    finally:
        if fd is not None:
            os.close(fd)
        # Only the one exclusively created same-directory temp belongs to this run.
        temp.unlink(missing_ok=True)


def verify_package(path: Path, *, trusted_sha=MANIFEST_SHA,
                   check_cancelled=lambda: None):
    fd = _open_regular(Path(path).absolute())
    try:
        before = os.fstat(fd)
        digest = hashlib.sha256()
        if _read_exact(fd, 8, digest, check_cancelled) != MAGIC:
            raise ValueError("Invalid model package magic")
        size = struct.unpack(">I", _read_exact(fd, 4, digest, check_cancelled))[0]
        if not 1 <= size <= MAX_MANIFEST_BYTES:
            raise ValueError("Invalid package manifest length")
        raw = _read_exact(fd, size, digest, check_cancelled)
        manifest = _manifest(raw, trusted_sha)
        for spec in manifest["files"]:
            _stream_payload(fd, spec, digest, check_cancelled)
        check_cancelled()
        if os.read(fd, 1):
            raise ValueError("Trailing package bytes are forbidden")
        after = os.fstat(fd)
        if (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise ValueError("Package changed during verification")
        _plain_path(Path(path).absolute())
        return _receipt(manifest, size, trusted_sha, digest)
    finally:
        os.close(fd)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--source", type=Path)
    mode.add_argument("--verify", "--inspect", dest="verify", type=Path,
                      help="Stream-check every digest and EOF; print the verified receipt")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    if bool(args.source) != bool(args.output):
        parser.error("--source requires --output; verify/inspect must not specify --output")
    try:
        receipt = package_model(args.source, args.output) if args.source else verify_package(args.verify)
        print(json.dumps(receipt, indent=2))
        return 0
    except (OSError, ValueError) as failure:
        parser.exit(1, f"Model package failed: {type(failure).__name__}\n")
    except KeyboardInterrupt:
        parser.exit(130, "Model package cancelled; existing files were preserved\n")


if __name__ == "__main__":
    raise SystemExit(main())
