"""Data-only bounded archive format; no archive path is ever extracted directly."""
import io
import json
import re
import stat
import zipfile
import zlib

from .assets import build_asset
from .image_io import decode_source, sha

MAX_PACKAGE = 64 * 1024 * 1024
MAX_EXPANDED = 128 * 1024 * 1024
SAFE_FILE = re.compile(r"source\.png|asset\.json|definition\.json|manifest\.json|textures/[a-f0-9]{64}\.png")


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False,
                      separators=(",", ":"), sort_keys=True).encode("utf-8")


def parse_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("DUPLICATE_JSON_KEY")
            result[key] = value
        return result
    def reject(value):
        raise ValueError("NONFINITE_JSON")
    try:
        return json.loads(raw, object_pairs_hook=pairs, parse_constant=reject)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise ValueError("INVALID_JSON") from error


def payload(result):
    files = {**result["files"], "asset.json": json_bytes(result["asset"]),
             "definition.json": json_bytes(result["definition"])}
    total = sum(len(raw) for raw in files.values())
    if total > MAX_EXPANDED or len(files) > 67:
        raise ValueError("ASSET_RESOURCE_BUDGET")
    manifest = {"format": "rig2d-planar-package-1", "files": {name: sha(raw) for name, raw in files.items()}}
    return {**files, "manifest.json": json_bytes(manifest)}


def pack(files):
    if sum(len(raw) for raw in files.values()) > MAX_EXPANDED:
        raise ValueError("ASSET_RESOURCE_BUDGET")
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in sorted(files):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IFREG | 0o600) << 16
            archive.writestr(info, files[name])
    raw = stream.getvalue()
    if len(raw) > MAX_PACKAGE:
        raise ValueError("PACKAGE_TOO_LARGE")
    return raw


def unpack(raw):
    if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_PACKAGE:
        raise ValueError("INVALID_OR_LARGE_PACKAGE")
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            if len(entries) > 68 or len(set(names)) != len(names):
                raise ValueError("DUPLICATE_OR_LARGE_ARCHIVE")
            total = 0
            for entry in entries:
                mode = entry.external_attr >> 16
                if not SAFE_FILE.fullmatch(entry.filename) or entry.is_dir() or stat.S_ISLNK(mode):
                    raise ValueError("UNSAFE_ARCHIVE_PATH")
                total += entry.file_size
                if entry.flag_bits & 1 or entry.file_size > MAX_EXPANDED or total > MAX_EXPANDED:
                    raise ValueError("ARCHIVE_RESOURCE_BUDGET")
                if entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                    raise ValueError("UNSUPPORTED_ARCHIVE_COMPRESSION")
            files = {entry.filename: archive.read(entry) for entry in entries}
    except (zipfile.BadZipFile, OSError, RuntimeError, EOFError, NotImplementedError, zlib.error) as error:
        raise ValueError("INVALID_PACKAGE") from error
    required = {"manifest.json", "asset.json", "definition.json", "source.png"}
    if not required <= set(files):
        raise ValueError("INCOMPLETE_PACKAGE")
    manifest = parse_json(files["manifest.json"])
    if not isinstance(manifest, dict) or set(manifest) != {"format", "files"}:
        raise ValueError("INVALID_MANIFEST")
    hashes = manifest["files"]
    if manifest["format"] != "rig2d-planar-package-1" or not isinstance(hashes, dict):
        raise ValueError("INVALID_MANIFEST")
    if set(hashes) != set(files) - {"manifest.json"}:
        raise ValueError("MANIFEST_FILE_MISMATCH")
    if any(not isinstance(digest, str) or digest != sha(files[name]) for name, digest in hashes.items()):
        raise ValueError("MANIFEST_HASH_MISMATCH")
    asset, definition = parse_json(files["asset.json"]), parse_json(files["definition.json"])
    if not isinstance(asset, dict) or not isinstance(definition, dict):
        raise ValueError("INVALID_ASSET")
    rebuilt = build_asset(decode_source(files["source.png"]), definition, asset.get("name"))
    if rebuilt["asset"] != asset or payload(rebuilt) != files:
        raise ValueError("SOURCE_DERIVATION_MISMATCH")
    return rebuilt, files
