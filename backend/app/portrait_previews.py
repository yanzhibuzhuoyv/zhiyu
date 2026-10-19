"""Owner-scoped, restart-safe original/cutout previews with explicit confirmation."""
import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

from .media_models import AssetMetadata
from .media_storage import connection
from .visual_profiles import VisualConflict


class PortraitPreviews:
    def __init__(self, root):
        self.path = root / "portrait.sqlite3"
        with connection(self.path) as db:
            db.execute("""CREATE TABLE IF NOT EXISTS previews (
                id TEXT PRIMARY KEY, owner TEXT NOT NULL, conversation_id TEXT NOT NULL,
                expected_version INTEGER NOT NULL, original_id TEXT NOT NULL, subject_id TEXT NOT NULL,
                bbox TEXT NOT NULL, method TEXT NOT NULL, expires_at TEXT NOT NULL, choice TEXT)""")

    @staticmethod
    def _metadata(owner, profile, image, kind):
        return AssetMetadata(category="portrait", tags=["portrait", "reference", kind], owner=owner,
                             contains_person=True, visual_id=profile["visual_id"],
                             visual_version=profile["visual_version"] + 1, reference_sha256=image.sha256,
                             source_label="用户导入的 OC 原图" if kind == "original" else "用户确认前的 OC 主体预览",
                             license="private-user-upload", angle="reference",
                             gender=profile["gender"], hair=profile["hair"])

    def save(self, media, owner, identifier, profile, result):
        profile = {**profile, "visual_id": profile["visual_id"] or uuid4().hex}
        original = media.ingest("image", result.original.data, self._metadata(owner, profile, result.original, "original"))
        subject = media.ingest("image", result.subject.data, self._metadata(owner, profile, result.subject, "subject"))
        preview_id = uuid4().hex
        with connection(self.path, write=True) as db:
            db.execute("INSERT INTO previews VALUES(?,?,?,?,?,?,?,?,?,NULL)", (
                preview_id, owner, identifier, profile["visual_version"], original["id"], subject["id"],
                json.dumps(result.bbox), result.method, (datetime.now(UTC) + timedelta(hours=24)).isoformat(),
            ))
        return self.get(media, owner, identifier, preview_id)

    def _row(self, owner, identifier, preview_id):
        with connection(self.path) as db:
            row = db.execute("SELECT * FROM previews WHERE id=? AND owner=? AND conversation_id=?",
                             (preview_id, owner, identifier)).fetchone()
        if row is None:
            raise LookupError("人物预览不存在")
        if row["choice"] is None and datetime.fromisoformat(row["expires_at"]) < datetime.now(UTC):
            raise VisualConflict("人物预览已过期，请重新导入")
        return dict(row)

    def get(self, media, owner, identifier, preview_id):
        row = self._row(owner, identifier, preview_id)
        original = media.images.get(row["original_id"], owner)
        subject = media.images.get(row["subject_id"], owner)
        if original is None or subject is None:
            raise LookupError("人物预览图片暂时不可用")
        return {"id": row["id"], "original_id": original["id"], "subject_id": subject["id"],
                "original_url": f"/api/media/assets/{original['id']}", "subject_url": f"/api/media/assets/{subject['id']}",
                "original_sha256": original["sha256"], "subject_sha256": subject["sha256"],
                "original_width": original["width"], "original_height": original["height"],
                "bbox": json.loads(row["bbox"]), "method": row["method"], "needs_confirmation": True,
                "expected_visual_version": row["expected_version"], "choice": row["choice"]}

    def confirm(self, media, visual, owner, identifier, preview_id, choice, *, configured=False):
        row = self._row(owner, identifier, preview_id)
        if row["choice"] is not None and row["choice"] != choice:
            raise VisualConflict("这次预览已采用另一张图片，请重新导入")
        asset = media.images.get(row[f"{choice}_id"], owner)
        if asset is None:
            raise LookupError("人物预览图片暂时不可用")
        current = visual.get(owner, identifier, configured=configured)
        already_attached = (current["reference_id"] == asset["id"]
                            and current["visual_version"] == row["expected_version"] + 1)
        if not already_attached:
            if row["choice"] is not None:
                raise VisualConflict("OC 已更新，请重新导入人物预览")
            visual.attach(owner, identifier, asset, expected_version=row["expected_version"])
        # If a process stops after attach, replay recognizes the identical reference above.
        with connection(self.path, write=True) as db:
            db.execute("UPDATE previews SET choice=? WHERE id=? AND owner=? AND conversation_id=?",
                       (choice, preview_id, owner, identifier))
        return visual.get(owner, identifier, configured=configured)
