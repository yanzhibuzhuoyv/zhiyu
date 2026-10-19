"""Subject extraction uses isolated pixels/models, never the owner's media volume."""

import hashlib
import io
from dataclasses import FrozenInstanceError
from types import SimpleNamespace

import numpy as np
import pytest
from PIL import Image

from app import portrait_subject as subject_module
from app.media_images import normalize_image
from app.portrait_subject import PortraitExtractionError, PortraitProcessor


def pixels(*, size=(120, 100), alpha=False):
    image = Image.new("RGBA" if alpha else "RGB", size, (120, 90, 80, 0) if alpha else (120, 90, 80))
    if alpha:
        image.paste((200, 120, 100, 255), (30, 20, 90, 80))
    output = io.BytesIO()
    image.save(output, "PNG", comment="private metadata")
    return output.getvalue()


class FakeSession:
    def __init__(self, prediction):
        self.prediction = prediction
        self.feeds = []

    def get_inputs(self):
        return [SimpleNamespace(name="input.1")]

    def run(self, outputs, feeds):
        self.feeds.append(feeds)
        return [self.prediction]


@pytest.fixture
def processor(tmp_path, monkeypatch):
    model = tmp_path / "fixed.onnx"
    model.write_bytes(b"isolated-test-model")
    monkeypatch.setattr(subject_module, "MODEL_SHA256", hashlib.sha256(model.read_bytes()).hexdigest())
    prediction = np.zeros((1, 1, 320, 320), dtype=np.float32)
    prediction[:, :, 64:256, 80:240] = 1
    session = FakeSession(prediction)
    calls = []

    def factory(contents):
        calls.append(contents)
        return session

    return PortraitProcessor(model, session_factory=factory), session, calls


def test_original_is_preserved_cutout_has_alpha_and_confirmation_is_required(processor):
    service, session, calls = processor
    raw = pixels()
    result = service.extract(raw)
    assert result.original == normalize_image(raw)
    assert result.original.data != result.subject.data
    assert result.method == "u2netp" and result.needs_confirmation is True
    assert result.bbox[0] > 0 and result.bbox[1] > 0
    assert result.bbox[2] < result.original.width and result.bbox[3] < result.original.height
    with Image.open(io.BytesIO(result.subject.data)) as image:
        assert image.mode == "RGBA" and image.getchannel("A").getextrema()[0] < 255
    tensor = session.feeds[0]["input.1"]
    assert tensor.shape == (1, 3, 320, 320) and tensor.dtype == np.float32
    assert np.isfinite(tensor).all() and len(calls) == 1
    with pytest.raises(FrozenInstanceError):
        result.needs_confirmation = False


def test_repeated_import_reuses_one_verified_cpu_session(processor):
    service, session, calls = processor
    service.extract(pixels())
    service.extract(pixels())
    assert len(calls) == 1 and len(session.feeds) == 2


def test_crop_uses_original_pixel_coordinates_and_keeps_full_original(processor):
    service, _, _ = processor
    raw = pixels(size=(200, 180))
    result = service.extract(raw, crop_bounds=(80, 40, 180, 160))
    assert (result.original.width, result.original.height) == (200, 180)
    left, top, right, bottom = result.bbox
    assert 80 <= left < right <= 180 and 40 <= top < bottom <= 160


@pytest.mark.parametrize("bounds", [(-1, 0, 50, 50), (0, 0, 121, 50), (20, 20, 20, 50),
                                    (10, 20, 5, 50), (0.1, 0, 50, 50), (False, 0, 50, 50),
                                    (0, 0, 50), "0,0,50,50"])
def test_invalid_crop_is_rejected_before_inference(processor, bounds):
    service, session, _ = processor
    with pytest.raises(PortraitExtractionError, match="选取范围"):
        service.extract(pixels(), crop_bounds=bounds)
    assert session.feeds == []


def test_existing_alpha_is_not_segmented_again_without_model(tmp_path):
    service = PortraitProcessor(tmp_path / "not-downloaded.onnx")
    result = service.extract(pixels(alpha=True))
    assert result.method == "existing_alpha" and result.bbox == (30, 20, 90, 80)
    assert result.needs_confirmation is True
    with Image.open(io.BytesIO(result.subject.data)) as image:
        assert image.size == (60, 60) and image.getpixel((0, 0)) == (200, 120, 100, 255)


def test_existing_alpha_crop_is_offset_correctly(tmp_path):
    result = PortraitProcessor(tmp_path / "absent").extract(pixels(alpha=True), crop_bounds=(40, 30, 100, 90))
    assert result.bbox == (40, 30, 90, 80)
    assert (result.subject.width, result.subject.height) == (50, 50)


def test_fully_transparent_import_is_not_a_success(tmp_path):
    stream = io.BytesIO()
    Image.new("RGBA", (40, 40), (0, 0, 0, 0)).save(stream, "PNG")
    with pytest.raises(PortraitExtractionError, match="主体"):
        PortraitProcessor(tmp_path / "absent").extract(stream.getvalue())


def test_one_transparent_corner_does_not_count_as_an_existing_cutout(tmp_path):
    image = Image.new("RGBA", (80, 60), (120, 90, 80, 255))
    image.putpixel((0, 0), (120, 90, 80, 0))
    stream = io.BytesIO()
    image.save(stream, "PNG")
    with pytest.raises(PortraitExtractionError, match="模型"):
        PortraitProcessor(tmp_path / "absent").extract(stream.getvalue())


def test_uniform_semitransparency_runs_segmentation_and_keeps_original_alpha(processor):
    service, session, _ = processor
    image = Image.new("RGBA", (120, 100), (120, 90, 80, 128))
    stream = io.BytesIO()
    image.save(stream, "PNG")
    result = service.extract(stream.getvalue())
    assert result.method == "u2netp" and len(session.feeds) == 1
    with Image.open(io.BytesIO(result.subject.data)) as subject:
        assert subject.getchannel("A").getextrema()[1] <= 128


def test_transparent_hole_inside_an_opaque_border_is_not_a_person_cutout(processor):
    service, session, _ = processor
    image = Image.new("RGBA", (120, 100), (120, 90, 80, 255))
    image.paste((120, 90, 80, 0), (35, 30, 85, 70))
    stream = io.BytesIO()
    image.save(stream, "PNG")
    assert service.extract(stream.getvalue()).method == "u2netp"
    assert len(session.feeds) == 1


@pytest.mark.parametrize("value", [0.0, 1.0, float("nan"), float("inf")])
def test_constant_or_nonfinite_model_mask_is_a_clear_failure(processor, value):
    service, session, _ = processor
    session.prediction.fill(value)
    with pytest.raises(PortraitExtractionError, match="分离"):
        service.extract(pixels())


def test_mask_covering_entire_image_is_not_accepted(processor):
    service, session, _ = processor
    session.prediction.fill(1.0)
    session.prediction[0, 0, 0, 0] = 0.0
    with pytest.raises(PortraitExtractionError, match="分离"):
        service.extract(pixels())


def test_tiny_mask_does_not_pass_as_a_subject(processor):
    service, session, _ = processor
    session.prediction.fill(0.0)
    session.prediction[0, 0, 150, 150] = 1.0
    with pytest.raises(PortraitExtractionError, match="分离"):
        service.extract(pixels())


@pytest.mark.parametrize("shape", [(320, 320), (2, 1, 320, 320), (1, 2, 320, 320)])
def test_invalid_model_output_shape_is_rejected(processor, shape):
    service, session, _ = processor
    session.prediction = np.zeros(shape, dtype=np.float32)
    with pytest.raises(PortraitExtractionError, match="分离"):
        service.extract(pixels())


def test_missing_or_unverified_model_never_calls_factory(tmp_path):
    calls = []
    missing = PortraitProcessor(tmp_path / "absent", session_factory=calls.append)
    with pytest.raises(PortraitExtractionError, match="模型"):
        missing.extract(pixels())
    wrong = tmp_path / "wrong.onnx"
    wrong.write_bytes(b"wrong-checksum")
    with pytest.raises(PortraitExtractionError, match="校验"):
        PortraitProcessor(wrong, session_factory=calls.append).extract(pixels())
    assert calls == []


def test_decoder_limits_apply_before_subject_processing(processor):
    service, session, _ = processor
    with pytest.raises(ValueError, match="图片"):
        service.extract(b"not an image")
    assert session.feeds == []


def test_animation_is_rejected_instead_of_binding_a_random_frame(processor):
    service, _, _ = processor
    stream = io.BytesIO()
    frames = [Image.new("RGB", (20, 20), color) for color in ("red", "blue")]
    frames[0].save(stream, "GIF", save_all=True, append_images=frames[1:], duration=100)
    with pytest.raises(PortraitExtractionError, match="静态"):
        service.extract(stream.getvalue())


def test_model_failure_does_not_echo_runtime_or_path_details(processor):
    service, session, _ = processor

    def unavailable(*_):
        raise RuntimeError("private server/model path")

    session.run = unavailable
    with pytest.raises(PortraitExtractionError) as raised:
        service.extract(pixels())
    assert "private" not in str(raised.value)
