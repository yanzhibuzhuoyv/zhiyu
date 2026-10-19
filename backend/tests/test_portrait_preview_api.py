"""A subject preview never replaces a user's OC until explicitly confirmed."""
import io

import pytest
from fastapi.testclient import TestClient
from PIL import Image
from test_api import FakeProvider, login
from test_media_assets import image_bytes

from app.config import Settings
from app.main import create_app
from app.media_images import normalize_image
from app.portrait_subject import PortraitExtractionError, PortraitSubject


class SubjectFixture:
    def __init__(self):
        self.crops = []
        self.fail = False

    def extract(self, raw, *, crop_bounds=None):
        self.crops.append(crop_bounds)
        if self.fail:
            raise PortraitExtractionError("没有找到可见主体，请重新选择")
        output = io.BytesIO()
        Image.new("RGBA", (12, 16), (120, 80, 50, 180)).save(output, "PNG")
        return PortraitSubject(normalize_image(raw), normalize_image(output.getvalue()), (1, 2, 13, 18), "fixture")


@pytest.fixture
def preview_app(tmp_path):
    processor = SubjectFixture()
    app = create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider(), portrait_processor=processor)
    with TestClient(app) as client:
        headers, other = login(client), login(client, "other-key")
        identifier = client.post("/api/conversations", headers=headers, json={"use_rag": False}).json()["id"]
        yield client, app, processor, headers, other, f"/api/conversations/{identifier}/visual"


def preview(client, headers, base, **kwargs):
    return client.post(base + "/subject/preview", headers=headers,
                       files={"file": ("my-oc.png", image_bytes(), "image/png")}, **kwargs)


def test_preview_keeps_current_profile_and_both_images_private(preview_app):
    client, _, _, headers, other, base = preview_app
    prior = client.get(base, headers=headers).json()
    response = preview(client, headers, base)
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["needs_confirmation"] is True
    assert result["original_id"] != result["subject_id"]
    assert client.get(base, headers=headers).json() == prior
    for key in ("original_url", "subject_url"):
        assert client.get(result[key], headers=headers).status_code == 200
        assert client.get(result[key], headers=other).status_code == 404
        assert client.get(result[key]).status_code == 401
    assert client.get(base + "/subject/preview/" + result["id"], headers=other).status_code == 404


@pytest.mark.parametrize("choice", ["subject", "original"])
def test_confirm_uses_only_selected_preview_and_is_idempotent(preview_app, choice):
    client, _, _, headers, _, base = preview_app
    result = preview(client, headers, base).json()
    body = {"preview_id": result["id"], "choice": choice}
    confirmed = client.post(base + "/subject/confirm", headers=headers, json=body)
    assert confirmed.status_code == 200, confirmed.text
    profile = confirmed.json()
    assert profile["reference_id"] == result[f"{choice}_id"]
    assert profile["visual_version"] == 1
    assert profile["generation"]["remaining"] == 2
    assert client.post(base + "/subject/confirm", headers=headers, json=body).json() == profile
    changed_choice = {**body, "choice": "original" if choice == "subject" else "subject"}
    assert client.post(base + "/subject/confirm", headers=headers, json=changed_choice).status_code == 409


def test_old_preview_cannot_overwrite_changed_oc(preview_app):
    client, _, _, headers, _, base = preview_app
    result = preview(client, headers, base).json()
    changed = client.put(base, headers=headers, json={"mode": "import", "gender": "female"}).json()
    response = client.post(base + "/subject/confirm", headers=headers,
                           json={"preview_id": result["id"], "choice": "subject"})
    assert response.status_code == 409
    assert client.get(base, headers=headers).json() == changed


def test_crop_and_failures_do_not_replace_an_oc(preview_app):
    client, _, processor, headers, _, base = preview_app
    result = preview(client, headers, base, params=[("crop_bounds", value) for value in (0, 0, 10, 10)])
    assert result.status_code == 200 and processor.crops[-1] == (0, 0, 10, 10)
    assert preview(client, headers, base, params={"crop_bounds": "wrong"}).status_code == 422
    processor.fail = True
    response = preview(client, headers, base)
    assert response.status_code == 422
    assert client.get(base, headers=headers).json()["reference_id"] is None


def test_unconfigured_model_is_explicit_and_does_not_use_fake_extraction(tmp_path):
    app = create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())
    with TestClient(app) as client:
        headers = login(client)
        identifier = client.post("/api/conversations", headers=headers, json={}).json()["id"]
        base = f"/api/conversations/{identifier}/visual"
        assert client.get(base + "/subject/capability", headers=headers).json()["configured"] is False
        result = preview(client, headers, base)
        assert result.status_code == 503
        assert client.get(base, headers=headers).json()["reference_id"] is None
