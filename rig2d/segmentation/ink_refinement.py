"""Conservative seeded visible-ink draft; never an identity or semantic verdict."""
from dataclasses import dataclass
import hashlib
import struct
import numpy as np
from scipy.ndimage import binary_propagation
from segmentation.sam2_adapter import _number


class InkRefinementError(ValueError):
    pass


@dataclass(frozen=True)
class InkDraft:
    width: int
    height: int
    source_pixel_sha256: str
    pixels: bytes
    protected_pixels: int
    unseeded_candidate_pixels: bytes
    review_required: bool = True
    identity_verified: bool = False


def extract_seeded_ink(rgb, support, foreground_points, background_points,
                       *, tolerance=32.0, separation=12.0, protected=None):
    if not (isinstance(rgb, np.ndarray) and rgb.dtype == np.uint8 and rgb.ndim == 3
            and rgb.shape[2] == 3 and all(1 <= n <= 1536 for n in rgb.shape[:2])
            and rgb.shape[0] * rgb.shape[1] <= 4_000_000):
        raise InkRefinementError('INVALID_WORKING_RGB')
    h, w = rgb.shape[:2]
    area = _mask(support, h, w)
    protection = np.zeros((h, w), bool) if protected is None else _mask(protected, h, w)
    positive = _points(foreground_points, w, h)
    negative = _points(background_points, w, h)
    for value in (tolerance, separation):
        if not _number(value) or not 0 <= value <= 442:
            raise InkRefinementError('INVALID_COLOR_THRESHOLD')
    if any(not (area[y, x] or protection[y, x]) for x, y in positive):
        raise InkRefinementError('UNSUPPORTED_FOREGROUND_SEED')
    if any(protection[y, x] for x, y in negative):
        raise InkRefinementError('PROTECTED_BACKGROUND_SEED')
    image = np.array(rgb, copy=True, order='C')
    colors = image.astype(np.float32)
    foreground = np.array([colors[y, x] for x, y in positive])
    background = np.array([colors[y, x] for x, y in negative])
    distances = np.linalg.norm(foreground[:, None] - background[None, :], axis=2)
    if np.any(distances <= separation):
        raise InkRefinementError('AMBIGUOUS_COLOR_SEEDS')
    near_foreground = _distance(colors, foreground)
    near_background = _distance(colors, background)
    allowed = area & (near_foreground <= tolerance) & (near_background - near_foreground >= separation)
    seeds = np.zeros((h, w), bool)
    for x, y in positive:
        if allowed[y, x]:
            seeds[y, x] = True
    selected = binary_propagation(seeds, structure=np.ones((3, 3), bool), mask=allowed)
    selected |= protection
    # Similar-color islands are unresolved evidence, not implicitly background or foreground.
    unseeded = allowed & ~selected
    sha = hashlib.sha256(b'RigRgb-v1\0' + struct.pack('>II', w, h) + image.tobytes()).hexdigest()
    return InkDraft(w, h, sha, selected.astype(np.uint8).tobytes(), int(protection.sum()),
                    unseeded.astype(np.uint8).tobytes())


def _mask(value, height, width):
    if not (isinstance(value, np.ndarray) and value.dtype == np.bool_
            and value.shape == (height, width)):
        raise InkRefinementError('INVALID_BINARY_SUPPORT_OR_PROTECTION')
    return value.copy()


def _points(value, width, height):
    if not isinstance(value, (list, tuple)) or not 1 <= len(value) <= 64:
        raise InkRefinementError('INVALID_COLOR_SEED_COUNT')
    snapshot = []
    for point in value:
        if len(snapshot) >= 64 or not isinstance(point, (list, tuple)) or len(point) != 2:
            raise InkRefinementError('INVALID_COLOR_SEED')
        x, y = point
        if not (type(x) is int and type(y) is int and 0 <= x < width and 0 <= y < height):
            raise InkRefinementError('INVALID_COLOR_SEED')
        snapshot.append((x, y))
    if not snapshot:
        raise InkRefinementError('INVALID_COLOR_SEED_COUNT')
    return tuple(snapshot)


def _distance(colors, palette):
    minimum = np.full(colors.shape[:2], np.inf, np.float32)
    for color in palette:
        squared = np.sum((colors - color) ** 2, axis=2)
        np.minimum(minimum, squared, out=minimum)
    return np.sqrt(minimum)
