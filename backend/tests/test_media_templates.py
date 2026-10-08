import json

import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider, login
from test_media_assets import image_bytes, metadata

from app.config import Settings
from app.main import create_app
from app.media_assets import MediaLibraries
from app.media_cli import import_manifest
from app.media_selection import select_media
from app.media_templates import adopt, catalogue
from app.visual_profiles import VisualProfiles


def prepare(libraries):
    libraries.ingest("image", image_bytes(), metadata(category="portrait", tags=["portrait", "reference"],
                     contains_person=True, oc_template_id="sample-oc", gender="female", hair="long"))
    libraries.ingest("image", image_bytes("blue"), metadata(category="selfie", tags=["selfie"],
                     contains_person=True, oc_template_id="sample-oc", gender="female", hair="long"))


def test_template_requires_explicit_adoption_and_copies_identity_without_copying_bytes(tmp_path):
    libraries, profiles = MediaLibraries(tmp_path), VisualProfiles(tmp_path)
    prepare(libraries)
    assert catalogue(libraries)[0]["image_count"] == 2
    before = list(libraries.images.assets_dir.iterdir())
    owner = "a" * 64
    selected = adopt(libraries, profiles, owner, "chat", "sample-oc")
    assert selected["gender"] == "female" and selected["hair"] == "long"
    assert selected["generation"]["used"] == 0
    assert len(list(libraries.images.assets_dir.iterdir())) == len(before)
    attachments = select_media(libraries, owner, "chat", "发一张自拍", {"style": "gentle"}, selected,
                               day_id="2026-10-08", recent=[], turn_number=1)
    assert len(attachments) == 1
    assert libraries.file(attachments[0]["id"], "b" * 64) is None


def test_template_routes_do_not_export_private_user_oc(tmp_path):
    app = create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())
    prepare(app.state.media)
    with TestClient(app) as client:
        first, second = login(client), login(client, "another-key")
        created = client.post("/api/conversations", headers=first, json={"use_rag": False}).json()
        assert client.get("/api/media/templates").status_code == 401
        template = client.get("/api/media/templates", headers=first).json()[0]
        assert client.get(template["reference_url"], headers=first).status_code == 200
        result = client.post(f"/api/conversations/{created['id']}/visual/template", headers=first,
                             json={"template_id": template["template_id"]})
        assert result.status_code == 200, result.text
        assert client.get(result.json()["reference_url"], headers=second).status_code == 404
        client.post(f"/api/conversations/{created['id']}/visual/reference", headers=first,
                    files={"file": ("user.png", image_bytes("green"), "image/png")})
        assert len(client.get("/api/media/templates", headers=second).json()) == 1
        assert client.post(f"/api/conversations/{created['id']}/visual/template", headers=second,
                           json={"template_id": template["template_id"]}).status_code == 404


def test_operator_cli_import_has_no_arbitrary_path_or_unknown_metadata(tmp_path):
    assets = tmp_path / "inputs"
    assets.mkdir()
    (assets / "cat.png").write_bytes(image_bytes())
    manifest = assets / "manifest.json"
    manifest.write_text(json.dumps([{"kind": "image", "file": "cat.png",
                                   "metadata": metadata(category="cat", tags=["cat"]).model_dump()}]), encoding="utf-8")
    result = import_manifest(tmp_path / "libraries", manifest)
    assert len(result) == 1 and "owner" not in result[0]
    (tmp_path / "private.png").write_bytes(image_bytes("green"))
    manifest.write_text(json.dumps([{"kind": "image", "file": "../private.png",
                                   "metadata": metadata(category="cat").model_dump()}]), encoding="utf-8")
    with pytest.raises(ValueError, match="清单目录"):
        import_manifest(tmp_path / "libraries", manifest)


def test_same_reference_description_edit_keeps_identity_materials(tmp_path):
    from app.media_models import VisualUpdate

    libraries, profiles = MediaLibraries(tmp_path), VisualProfiles(tmp_path)
    prepare(libraries)
    selected = adopt(libraries, profiles, "a" * 64, "chat", "sample-oc")
    updated = profiles.update("a" * 64, "chat", VisualUpdate(mode="import", description="增加参考描述",
                                                           gender="female", hair="long"))
    assert updated["visual_version"] == selected["visual_version"]
    assert updated["reference_id"] == selected["reference_id"]
    changed = profiles.update("a" * 64, "chat", VisualUpdate(mode="import", gender="female", hair="short"))
    assert changed["visual_version"] == selected["visual_version"] + 1
    assert changed["reference_id"] is None
