"""Independent image/sticker registries and opaque, authenticated asset files."""

import hashlib
import re
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from .media_images import normalize_image
from .media_models import AssetMetadata
from .media_storage import MediaStorageError, connection


class AssetLibrary:
    def __init__(self, root: Path, kind: str):
        self.kind = kind
        self.path = root / ("images.sqlite3" if kind == "image" else "stickers.sqlite3")
        self.assets_dir = root / "assets" / kind
        self.assets_dir.mkdir(parents=True, exist_ok=True)
        with connection(self.path) as db:
            db.executescript("""CREATE TABLE IF NOT EXISTS assets (
                id TEXT PRIMARY KEY, owner TEXT, category TEXT NOT NULL, sha256 TEXT NOT NULL,
                mime_type TEXT NOT NULL, extension TEXT NOT NULL, width INTEGER NOT NULL,
                height INTEGER NOT NULL, metadata TEXT NOT NULL, created_at TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS idx_assets_owner_category ON assets(owner, category);""")

    def ingest(self, raw: bytes, metadata: AssetMetadata):
        image = normalize_image(raw)
        identifier = uuid4().hex
        target = self.assets_dir / f"{image.sha256}.{image.extension}"
        values = {"id": identifier, "kind": self.kind, "sha256": image.sha256,
                  "mime_type": image.mime_type, "extension": image.extension,
                  "width": image.width, "height": image.height,
                  "created_at": datetime.now(UTC).isoformat(), **metadata.model_dump()}
        try:
            if target.is_symlink() or not target.resolve().is_relative_to(self.assets_dir.resolve()):
                raise MediaStorageError("素材文件路径无效")
            try:
                with target.open("xb") as stream:
                    stream.write(image.data)
            except FileExistsError as exc:
                if target.stat().st_size > 5 * 1024 * 1024 or hashlib.sha256(target.read_bytes()).hexdigest() != image.sha256:
                    raise MediaStorageError("素材文件完整性校验失败") from exc
            target.chmod(0o600)
            with connection(self.path, write=True) as db:
                db.execute("INSERT INTO assets VALUES (?,?,?,?,?,?,?,?,?,?)", (
                    identifier, metadata.owner, metadata.category, image.sha256, image.mime_type,
                    image.extension, image.width, image.height,
                    metadata.model_dump_json(), values["created_at"],
                ))
        except OSError as exc:
            raise MediaStorageError("素材文件暂时无法保存") from exc
        return values

    def candidates(self, owner: str):
        with connection(self.path) as db:
            rows = db.execute("SELECT * FROM assets WHERE owner IS NULL OR owner=? ORDER BY created_at DESC LIMIT 512",
                              (owner,)).fetchall()
        return [self._decode(row) for row in rows]

    def get(self, identifier: str, owner: str):
        if not re.fullmatch(r"[a-f0-9]{32}", identifier):
            return None
        with connection(self.path) as db:
            row = db.execute("SELECT * FROM assets WHERE id=? AND (owner IS NULL OR owner=?)",
                             (identifier, owner)).fetchone()
        if row is None:
            return None
        asset = self._decode(row)
        sensitive = asset["contains_person"] or asset["gender"] != "unspecified" or asset["hair"] != "unspecified"
        # Unbound OC templates are an operator catalogue, never a random user's face.
        return None if sensitive and asset["owner"] != owner else asset

    def file(self, identifier: str, owner: str):
        asset = self.get(identifier, owner)
        if asset is None:
            return None
        return self.verified_file(asset)

    def verified_file(self, asset):
        if not re.fullmatch(r"[a-f0-9]{64}", asset["sha256"]) or asset["extension"] not in {"png", "gif"}:
            raise MediaStorageError("素材文件记录格式无效")
        target = (self.assets_dir / f"{asset['sha256']}.{asset['extension']}").resolve()
        if not target.is_relative_to(self.assets_dir.resolve()) or not target.is_file():
            return None
        try:
            if target.stat().st_size > 5 * 1024 * 1024 or hashlib.sha256(target.read_bytes()).hexdigest() != asset["sha256"]:
                raise MediaStorageError("素材文件完整性校验失败")
        except OSError as exc:
            raise MediaStorageError("素材文件暂时无法读取") from exc
        return target, asset

    def template_assets(self):
        with connection(self.path) as db:
            rows = db.execute("""SELECT * FROM assets WHERE owner IS NULL
                AND json_extract(metadata,'$.oc_template_id') IS NOT NULL
                ORDER BY created_at DESC LIMIT 512""").fetchall()
        return [self._decode(row) for row in rows]

    def _decode(self, row):
        try:
            metadata = AssetMetadata.model_validate_json(row["metadata"]).model_dump()
        except (ValueError, TypeError) as exc:
            raise MediaStorageError("素材元数据格式损坏") from exc
        return {**dict(row), **metadata, "kind": self.kind}


class MediaLibraries:
    def __init__(self, root: Path):
        root.mkdir(parents=True, exist_ok=True)
        self.images = AssetLibrary(root, "image")
        self.stickers = AssetLibrary(root, "sticker")

    def ingest(self, kind, raw, metadata):
        if kind not in {"image", "sticker"}:
            raise ValueError("素材库类型无效")
        return (self.images if kind == "image" else self.stickers).ingest(raw, metadata)

    def file(self, identifier, owner):
        return self.images.file(identifier, owner) or self.stickers.file(identifier, owner)
