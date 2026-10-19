"""Camera orientation is applied before private metadata is stripped."""
import io

import pytest
from PIL import Image

from app.media_images import normalize_image


@pytest.mark.parametrize("orientation,expected", [(1, (40, 20)), (6, (20, 40)), (8, (20, 40))])
def test_camera_reference_remains_upright_without_exif(orientation, expected):
    output = io.BytesIO()
    image = Image.new("RGB", (40, 20), (190, 130, 70))
    exif = Image.Exif()
    exif[274] = orientation
    image.save(output, "JPEG", exif=exif)
    safe = normalize_image(output.getvalue())
    assert (safe.width, safe.height) == expected
    with Image.open(io.BytesIO(safe.data)) as decoded:
        assert decoded.size == expected
        assert not decoded.getexif()
