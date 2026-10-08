import io
from datetime import UTC, datetime

import pytest
from PIL import Image

from app.media_assets import MediaLibraries
from app.media_models import AssetMetadata
from app.media_selection import select_media


def image_bytes(color="red", kind="PNG"):
    output = io.BytesIO()
    Image.new("RGB", (24, 32), color).save(output, kind)
    return output.getvalue()


def metadata(**values):
    return AssetMetadata(source_label="测试作者授权", license="test-only", **values)


def test_independent_libraries_content_validation_and_filename_boundary(tmp_path):
    stores = MediaLibraries(tmp_path)
    photo = stores.ingest("image", image_bytes(), metadata(category="cat", tags=["cat"]))
    sticker = stores.ingest("sticker", image_bytes(), metadata(category="object", tags=["happy"]))
    assert stores.images.path != stores.stickers.path
    assert stores.images.path.name == "images.sqlite3"
    assert stores.stickers.path.name == "stickers.sqlite3"
    assert photo["sha256"] == sticker["sha256"]
    assert stores.file(photo["id"], "owner")[0].is_file()
    assert stores.file("../../identity.secret", "owner") is None
    with pytest.raises(ValueError, match="图片"):
        stores.ingest("image", b"<svg onload=alert(1)>", metadata(category="cat"))


def test_private_assets_are_not_exported_to_another_owner(tmp_path):
    stores = MediaLibraries(tmp_path)
    asset = stores.ingest("image", image_bytes(), metadata(category="portrait", owner="a" * 64,
                         contains_person=True, visual_id="1" * 32, visual_version=1,
                         reference_sha256="2" * 64))
    assert stores.file(asset["id"], "b" * 64) is None
    assert stores.file(asset["id"], "a" * 64) is not None


def test_person_asset_requires_exact_oc_identity_and_scene_matches(tmp_path):
    stores = MediaLibraries(tmp_path)
    owner = "a" * 64
    visual = {"visual_id": "1" * 32, "visual_version": 1, "reference_sha256": "2" * 64,
              "gender": "female", "hair": "long"}
    wrong = stores.ingest("image", image_bytes(), metadata(category="selfie", owner=owner,
                          contains_person=True, visual_id="3" * 32, visual_version=1,
                          reference_sha256="4" * 64, tags=["selfie"]))
    right = stores.ingest("image", image_bytes("blue"), metadata(category="selfie", owner=owner,
                          contains_person=True, visual_id=visual["visual_id"], visual_version=1,
                          reference_sha256=visual["reference_sha256"], tags=["selfie"],
                          angle="rear-camera", lighting="daylight", location="park"))
    chosen = select_media(stores, owner, "chat", "发一张自拍照片", {"style": "gentle"}, visual,
                          day_id="2026-10-08", recent=[], turn_number=2)
    assert [item["id"] for item in chosen] == [right["id"]]
    assert wrong["id"] not in str(chosen)
    visual = {**visual, "visual_version": 2}
    assert select_media(stores, owner, "chat", "发一张自拍", {"style": "gentle"}, visual,
                        day_id="2026-10-08", recent=[], turn_number=2) == []


def test_oc_template_with_no_owner_is_not_a_random_default_face(tmp_path):
    stores = MediaLibraries(tmp_path)
    stores.ingest("image", image_bytes(), metadata(category="selfie", contains_person=True,
                  oc_template_id="sample-oc", tags=["selfie"]))
    assert select_media(stores, "a" * 64, "chat", "来张自拍", {"style": "playful"}, {},
                        day_id="2026-10-08", recent=[], turn_number=3) == []


def test_no_media_on_plain_daily_share_and_no_repeated_hash(tmp_path):
    stores = MediaLibraries(tmp_path)
    one = stores.ingest("image", image_bytes(), metadata(category="cat", tags=["cat"]))
    two = stores.ingest("image", image_bytes("blue"), metadata(category="cat", tags=["cat"]))
    assert select_media(stores, "a", "c", "今天下班有点累", {"style": "gentle"}, {},
                        day_id="2026-10-08", recent=[], turn_number=1) == []
    first = select_media(stores, "a", "c", "发一张猫照片", {"style": "gentle"}, {},
                         day_id="2026-10-08", recent=[], turn_number=2)
    second = select_media(stores, "a", "c", "再发一张猫照片", {"style": "gentle"}, {},
                          day_id="2026-10-08", recent=first, turn_number=3)
    assert first and second and first[0]["sha256"] != second[0]["sha256"]
    assert {first[0]["id"], second[0]["id"]} == {one["id"], two["id"]}
    assert select_media(stores, "a", "c", "再发一张猫照片", {"style": "gentle"}, {},
                        day_id="2026-10-08", recent=first + second, turn_number=4) == []


def test_mature_profile_sends_fewer_stickers_and_context_must_match(tmp_path):
    stores = MediaLibraries(tmp_path)
    stores.ingest("sticker", image_bytes(), metadata(category="object", tags=["happy"]))
    playful = select_media(stores, "a", "c", "哈哈真开心", {"style": "playful"}, {},
                           day_id="2026-10-08", recent=[], turn_number=3)
    mature = select_media(stores, "a", "c", "哈哈真开心", {"style": "steady"}, {},
                          day_id="2026-10-08", recent=[], turn_number=3)
    assert len(playful) == 1
    assert mature == []
    assert select_media(stores, "a", "c", "我现在很难过", {"style": "playful"}, {},
                        day_id="2026-10-08", recent=[], turn_number=3) == []


def test_neutral_landscape_with_gendered_hair_is_not_unbound_generic(tmp_path):
    stores = MediaLibraries(tmp_path)
    stores.ingest("image", image_bytes(), metadata(category="landscape", gender="female",
                  hair="long", oc_template_id="sample-oc", tags=["landscape"]))
    assert select_media(stores, "a", "c", "发一张风景", {"style": "gentle"}, {},
                        day_id="2026-10-08", recent=[], turn_number=2) == []


def test_asset_sizes_pixel_bombs_and_unsafe_sources_rejected(tmp_path):
    stores = MediaLibraries(tmp_path)
    with pytest.raises(ValueError, match="大小"):
        stores.ingest("image", b"x" * (5 * 1024 * 1024 + 1), metadata(category="cat"))
    with pytest.raises(ValueError):
        metadata(category="cat", source_url="file:///C:/Users/private")
    with pytest.raises(ValueError):
        metadata(category="cat", source_url="https://name:secret@example.test/x")
    output = io.BytesIO()
    Image.new("L", (6000, 4000)).save(output, "PNG")
    with pytest.raises(ValueError, match="像素"):
        stores.ingest("image", output.getvalue(), metadata(category="cat"))


def test_daily_scene_changes_at_beijing_midnight_and_survives_restart(tmp_path):
    from app.life_scenes import DailyScenes

    scenes = DailyScenes(tmp_path)
    before = scenes.get("a", "c", 1, datetime(2026, 10, 8, 15, 59, 59, tzinfo=UTC))
    after = scenes.get("a", "c", 1, datetime(2026, 10, 8, 16, 0, 0, tzinfo=UTC))
    assert before["day_id"] == "2026-10-08"
    assert after["day_id"] == "2026-10-09"
    assert before["scene_id"] != after["scene_id"]
    assert scenes.get("a", "c", 2, datetime(2026, 10, 8, 16, 0, 0, tzinfo=UTC))["scene_id"] == after["scene_id"]
    restored = DailyScenes(tmp_path).get("a", "c", 1, datetime(2026, 10, 8, 16, 0, 0, tzinfo=UTC))
    assert restored["scene_id"] == after["scene_id"]
    assert restored["fictional"] is True
    assert scenes.get("b", "c", 1, datetime(2026, 10, 8, 16, 0, 0, tzinfo=UTC))["scene_id"] != after["scene_id"]


def test_animated_sticker_remains_animated_and_removes_embedded_metadata(tmp_path):
    frames = [Image.new("RGB", (24, 32), color) for color in ("red", "blue", "green")]
    stream = io.BytesIO()
    frames[0].save(stream, "GIF", save_all=True, append_images=frames[1:], duration=100,
                   comment=b"private-location-marker", loop=0)
    libraries = MediaLibraries(tmp_path)
    asset = libraries.ingest("sticker", stream.getvalue(), metadata(category="object", tags=["happy"]))
    output = libraries.file(asset["id"], "owner")[0].read_bytes()
    assert asset["mime_type"] == "image/gif"
    assert b"private-location-marker" not in output
    with Image.open(io.BytesIO(output)) as decoded:
        assert decoded.n_frames == 3
