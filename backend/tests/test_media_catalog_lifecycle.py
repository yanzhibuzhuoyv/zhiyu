import json
import sqlite3
from contextlib import closing

import pytest
from test_media_assets import image_bytes

from app.media_assets import MediaLibraries
from app.media_cli import main
from app.media_models import AssetMetadata


def metadata(**values):
    return AssetMetadata(**{"source_label": "测试作者授权", "license": "test-only", **values})


def test_retired_stickers_leave_catalog_but_keep_historical_attachments(tmp_path):
    stores = MediaLibraries(tmp_path)
    old = stores.ingest("sticker", image_bytes(), metadata(category="object", source_label="旧表情"))
    new = stores.ingest("sticker", image_bytes("blue"), metadata(category="cat", source_label="猫猫"))
    assert stores.stickers.set_source_active("旧表情", False) == 1
    assert [item["id"] for item in stores.stickers.candidates("a")] == [new["id"]]
    assert stores.file(old["id"], "a")[0].is_file()
    restored = MediaLibraries(tmp_path)
    assert len(restored.stickers.candidates("a")) == 1
    assert restored.stickers.set_source_active("旧表情", True) == 1
    assert len(restored.stickers.candidates("a")) == 2


def test_operator_retirement_does_not_change_private_or_photo_assets(tmp_path):
    stores = MediaLibraries(tmp_path)
    public = stores.ingest("sticker", image_bytes(), metadata(category="object", source_label="old"))
    private = stores.ingest("sticker", image_bytes("blue"), metadata(category="object", owner="a" * 64, source_label="old"))
    photo = stores.ingest("image", image_bytes(), metadata(category="cat", source_label="old"))
    assert stores.stickers.set_source_active("old", False) == 1
    assert [item["id"] for item in stores.stickers.candidates("a" * 64)] == [private["id"]]
    assert stores.stickers.candidates("b" * 64) == []
    assert stores.images.candidates("b")[0]["id"] == photo["id"]
    assert stores.stickers.file(public["id"], "b")


@pytest.mark.parametrize("label,active", [("", False), ("x" * 161, False), ("a\n", False), ("old", 0), ("old", "false")])
def test_retirement_input_is_strict(tmp_path, label, active):
    with pytest.raises(ValueError):
        MediaLibraries(tmp_path).stickers.set_source_active(label, active)


def test_old_catalog_migration_keeps_assets_and_enables_them(tmp_path):
    stores = MediaLibraries(tmp_path)
    asset = stores.ingest("sticker", image_bytes(), metadata(category="cat"))
    with closing(sqlite3.connect(stores.stickers.path)) as db, db:
        db.execute("ALTER TABLE assets DROP COLUMN active")
    restored = MediaLibraries(tmp_path)
    assert restored.stickers.candidates("owner")[0]["id"] == asset["id"]
    assert restored.stickers.set_source_active(asset["source_label"], False) == 1
    assert restored.stickers.file(asset["id"], "owner")


def test_cli_can_retire_old_pack_without_reimporting(tmp_path, capsys):
    directory = tmp_path / "media"
    stores = MediaLibraries(directory)
    asset = stores.ingest("sticker", image_bytes(), metadata(category="object", source_label="Microsoft Fluent Emoji"))
    main(["--media-dir", str(directory), "--disable-sticker-source", "Microsoft Fluent Emoji"])
    assert json.loads(capsys.readouterr().out)["disabled_stickers"] == 1
    assert stores.stickers.candidates("owner") == []
    assert stores.stickers.file(asset["id"], "owner")
