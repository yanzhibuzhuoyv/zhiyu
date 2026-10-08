"""Explicit local operator import; ordinary API clients cannot write shared media."""

import argparse
import json
from pathlib import Path

from .media_assets import MediaLibraries
from .media_models import AssetMetadata


def import_manifest(directory: Path, manifest: Path):
    manifest = manifest.resolve()
    if manifest.stat().st_size > 1024 * 1024:
        raise ValueError("素材清单最大为 1 MiB")
    values = json.loads(manifest.read_text(encoding="utf-8-sig"))
    if not isinstance(values, list) or not 1 <= len(values) <= 1000:
        raise ValueError("素材清单必须包含 1 到 1000 项")
    inputs = []
    for item in values:
        if not isinstance(item, dict) or set(item) != {"kind", "file", "metadata"}:
            raise ValueError("清单每项必须包含 kind、file、metadata")
        if item["kind"] not in {"image", "sticker"} or not isinstance(item["file"], str):
            raise ValueError("清单素材类型或文件名无效")
        candidate = (manifest.parent / item["file"]).resolve()
        if not candidate.is_relative_to(manifest.parent) or not candidate.is_file() or candidate.stat().st_size > 5 * 1024 * 1024:
            raise ValueError("素材文件必须位于清单目录中，且不超过 5 MiB")
        metadata = AssetMetadata.model_validate(item["metadata"])
        inputs.append((item["kind"], candidate, metadata))
    libraries = MediaLibraries(directory)
    imported = [libraries.ingest(kind, path.read_bytes(), metadata) for kind, path, metadata in inputs]
    return [{key: asset[key] for key in ("id", "kind", "sha256", "category")} for asset in imported]


def main(argv=None):
    parser = argparse.ArgumentParser(description="知语独立素材库导入工具")
    parser.add_argument("--media-dir", type=Path, required=True)
    parser.add_argument("manifest", type=Path)
    values = parser.parse_args(argv)
    try:
        result = import_manifest(values.media_dir.resolve(), values.manifest)
    except (ValueError, OSError) as exc:
        parser.exit(2, f"素材导入失败：{exc}\n")
    print(json.dumps({"imported": result}, ensure_ascii=False))


if __name__ == "__main__":
    main()
