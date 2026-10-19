"""Freeform OC parts survive manual masks without anatomy or component rules."""

import io

import pytest
from PIL import Image

from app.media_images import normalize_image
from app.portrait_refinement import refine_subject
from app.portrait_subject import PortraitExtractionError


def encoded(image):
    stream = io.BytesIO()
    image.save(stream, "PNG")
    return stream.getvalue()


@pytest.fixture
def portrait():
    original = Image.new("RGBA", (120, 100), (40, 90, 130, 255))
    original.paste((220, 150, 110, 255), (40, 20, 80, 90))
    # The body is only the old preview; appendages/weapons lie elsewhere in the original.
    subject = original.crop((40, 20, 80, 90))
    return encoded(original), encoded(subject), (40, 20, 80, 90)


def stroke(action="keep", points=None, radius=0.01):
    return {"action": action, "points": points or [[0.1, 0.3]], "radius": radius}


def alpha_at(result, x, y):
    left, top, right, bottom = result.bbox
    if not left <= x < right or not top <= y < bottom:
        return 0
    with Image.open(io.BytesIO(result.subject.data)) as subject:
        return subject.getpixel((x - left, y - top))[3]


def test_single_wing_is_preserved_with_original_rgb_and_original_unchanged(portrait):
    original_raw, subject_raw, bbox = portrait
    result = refine_subject(original_raw, subject_raw, bbox,
                            [stroke(points=[[0.1, 0.3], [0.32, 0.4]])])
    assert result.original == normalize_image(original_raw)
    assert result.original.sha256 == normalize_image(original_raw).sha256
    assert result.method == "manual_refinement" and result.needs_confirmation is True
    assert alpha_at(result, 12, 30) == 255
    assert alpha_at(result, 60, 50) == 255
    with Image.open(io.BytesIO(result.subject.data)) as subject:
        assert subject.getpixel((12 - result.bbox[0], 30 - result.bbox[1]))[:3] == (40, 90, 130)


def test_six_pairs_of_wings_and_disconnected_weapon_all_survive_union(portrait):
    edits = [stroke(points=[[side, height]]) for side in (0.1, 0.9)
             for height in (0.1, 0.2, 0.3, 0.4, 0.5, 0.6)]
    edits.append(stroke(points=[[0.94, 0.92]], radius=0.015))
    result = refine_subject(*portrait, edits)
    for side in (0.1, 0.9):
        for height in (0.1, 0.2, 0.3, 0.4, 0.5, 0.6):
            assert alpha_at(result, round(side * 119), round(height * 99)) == 255
    assert alpha_at(result, round(0.94 * 119), round(0.92 * 99)) == 255
    assert result.bbox[0] < 12 and result.bbox[2] > 112
    # Empty pixels between the weapon and body remain empty; no filled union rectangle.
    assert alpha_at(result, 100, 80) == 0


def test_erase_removes_background_and_ordered_keep_can_restore_it(portrait):
    edits = [stroke(points=[[0.1, 0.3]], radius=0.04),
             stroke("remove", points=[[0.1, 0.3]], radius=0.015)]
    removed = refine_subject(*portrait, edits)
    assert alpha_at(removed, 12, 30) == 0
    restored = refine_subject(*portrait, edits + [stroke(points=[[0.1, 0.3]], radius=0.002)])
    assert alpha_at(restored, 12, 30) == 255


def test_continuous_stroke_fills_between_points_and_round_endpoints(portrait):
    result = refine_subject(*portrait, [stroke(points=[[0.0, 0.1], [0.99, 0.1]], radius=0.04)])
    assert alpha_at(result, 60, 10) == 255
    assert alpha_at(result, 0, 7) == 255
    assert alpha_at(result, 118, 7) == 255


def test_keep_never_restores_pixels_transparent_in_original(portrait):
    original = Image.open(io.BytesIO(portrait[0])).convert("RGBA")
    original.paste((40, 90, 130, 0), (0, 0, 30, 40))
    result = refine_subject(encoded(original), portrait[1], portrait[2], [stroke(radius=0.08)])
    assert alpha_at(result, 12, 30) == 0
    assert alpha_at(result, 60, 50) == 255


def test_repeated_edits_do_not_multiply_and_fade_source_alpha(portrait):
    original = Image.open(io.BytesIO(portrait[0])).convert("RGBA")
    original.putalpha(128)
    source = encoded(original)
    first = refine_subject(source, portrait[1], portrait[2], [stroke(points=[[0.5, 0.5]])])
    assert alpha_at(first, 60, 50) == 128
    second = refine_subject(source, first.subject.data, first.bbox, [])
    assert alpha_at(second, 60, 50) == 128


def test_low_opacity_glow_is_included_in_visible_union(portrait):
    original = Image.open(io.BytesIO(portrait[0])).convert("RGBA")
    original.putpixel((12, 30), (250, 240, 120, 1))
    result = refine_subject(encoded(original), portrait[1], portrait[2], [stroke()])
    assert alpha_at(result, 12, 30) == 1


def test_empty_edits_retain_mask_and_full_original(portrait):
    result = refine_subject(*portrait, [])
    assert result.bbox == portrait[2]
    assert result.original == normalize_image(portrait[0])
    assert alpha_at(result, 60, 50) == 255


@pytest.mark.parametrize("bbox", [(40, 20, 81, 90), (-1, 0, 39, 70), (40, 20, 40, 90),
                                  (80, 40, 120, 110), (True, 20, 80, 90),
                                  (40.0, 20, 80, 90), (40, 20, 80), None])
def test_invalid_bbox_or_subject_size_is_rejected(portrait, bbox):
    with pytest.raises(PortraitExtractionError, match="范围|尺寸"):
        refine_subject(portrait[0], portrait[1], bbox, [])


@pytest.mark.parametrize("edits", [None, "keep", {}, [None], ["keep"],
                                  [stroke(action="unknown")], [stroke(radius=True)],
                                  [stroke(radius=0.001)], [stroke(radius=0.151)],
                                  [stroke(radius=float("nan"))], [stroke(radius=float("inf"))],
                                  [stroke(points=[[True, 0.5]])], [stroke(points=[[0.5, -0.1]])],
                                  [stroke(points=[[1.01, 0.5]])], [stroke(points=[[0.5, float("nan")]])],
                                  [stroke(points=[[0.5, float("inf")]])], [stroke(points=[[0.5]])],
                                  [stroke(points=[[0.5, 0.5, 0.5]])], [stroke(points="0.5,0.5")],
                                  [{"action": "keep", "points": [], "radius": 0.01}],
                                  [{"action": "keep", "points": [[0.5, 0.5]]}],
                                  [{**stroke(), "script": "run"}],
                                  [stroke()] * 129, [stroke(points=[[0.5, 0.5]] * 1025)]])
def test_external_strokes_are_strictly_bounded_and_finite(portrait, edits):
    with pytest.raises(PortraitExtractionError, match="笔刷"):
        refine_subject(*portrait, edits)


def test_maximum_stroke_and_point_counts_are_accepted(portrait):
    edits = [stroke(points=[[0.5, 0.5]] * 1024)] * 128
    assert refine_subject(*portrait, edits).needs_confirmation is True


def test_erasing_all_visible_pixels_is_a_clear_failure(portrait):
    edits = [stroke("remove", [[0.33, y], [0.67, y]], radius=0.15)
             for y in (0.2, 0.4, 0.6, 0.8, 0.9)]
    with pytest.raises(PortraitExtractionError, match="主体"):
        refine_subject(*portrait, edits)


def test_fully_transparent_mask_without_keep_is_not_accepted(portrait):
    subject = Image.new("RGBA", (40, 70), (0, 0, 0, 0))
    with pytest.raises(PortraitExtractionError, match="主体"):
        refine_subject(portrait[0], encoded(subject), portrait[2], [])


def test_animated_reference_and_subject_are_rejected(portrait):
    frames = [Image.new("RGBA", (120, 100), color) for color in ("red", "blue")]
    stream = io.BytesIO()
    frames[0].save(stream, "GIF", save_all=True, append_images=frames[1:], duration=100)
    with pytest.raises(PortraitExtractionError, match="静态"):
        refine_subject(stream.getvalue(), portrait[1], portrait[2], [])
    with pytest.raises(PortraitExtractionError, match="静态"):
        refine_subject(portrait[0], stream.getvalue(), portrait[2], [])


def test_corrupt_pixels_and_oversized_files_follow_existing_limits(portrait):
    with pytest.raises(ValueError, match="图片"):
        refine_subject(b"corrupt", portrait[1], portrait[2], [])
    with pytest.raises(ValueError, match="图片"):
        refine_subject(portrait[0], b"x" * (5 * 1024 * 1024 + 1), portrait[2], [])
