"""Validate prompt/model boundaries without importing or downloading a model."""
from dataclasses import dataclass
import hashlib
import math
import re
import struct
import threading
from numbers import Real
from collections.abc import Iterable
import numpy as np


class SegmentationError(ValueError):
    pass


class SegmentationCancelled(Exception):
    pass


@dataclass(frozen=True)
class PromptRegion:
    id: str
    kind: str
    points: tuple = ()
    box: tuple | None = None


@dataclass(frozen=True)
class PromptMask:
    id: str
    declared_kind: str
    pixels: bytes
    mask_quality: float
    selected_index: int
    accepted: bool
    reasons: tuple
    candidate_scores: tuple


@dataclass(frozen=True)
class SegmentedFrame:
    width: int
    height: int
    source_pixel_sha256: str
    masks: tuple
    kind_inferred_by_model: bool = False
    identity_verified: bool = False


def _number(value):
    if not isinstance(value, Real) or isinstance(value, (bool, np.bool_)):
        return False
    try:
        return math.isfinite(float(value))
    except (ValueError, OverflowError):
        return False


def _checked_region(region, width, height):
    if not isinstance(region, PromptRegion):
        raise SegmentationError('INVALID_PROMPT_REGION')
    if not (isinstance(region.id, str) and re.fullmatch(r'[A-Za-z0-9_-]{1,80}', region.id)
            and isinstance(region.kind, str) and re.fullmatch(r'[a-z][a-z0-9_]{0,31}', region.kind)):
        raise SegmentationError('INVALID_PROMPT_IDENTIFIER')
    if not isinstance(region.points, (list, tuple)) or len(region.points) > 64:
        raise SegmentationError('INVALID_POINT_COUNT')
    points = []
    for point in region.points:
        if len(points) >= 64 or not isinstance(point, (list, tuple)) or len(point) != 3:
            raise SegmentationError('INVALID_POINT')
        x, y, label = point
        if not (_number(x) and _number(y) and 0 <= x < width and 0 <= y < height
                and type(label) is int and label in (0, 1)):
            raise SegmentationError('INVALID_POINT')
        points.append((float(x), float(y), label))
    box = region.box
    if box is not None:
        if not isinstance(box, (list, tuple)) or len(box) != 4 or not all(_number(v) for v in box):
            raise SegmentationError('INVALID_BOX')
        x0, y0, x1, y1 = box
        if not (0 <= x0 < x1 < width and 0 <= y0 < y1 < height):
            raise SegmentationError('INVALID_BOX')
        box = tuple(float(value) for value in box)
    if box is None and not any(point[2] == 1 for point in points):
        raise SegmentationError('MISSING_FOREGROUND_PROMPT')
    return PromptRegion(region.id, region.kind, tuple(points), box)


def _checked_output(output, width, height):
    if not isinstance(output, (tuple, list)) or len(output) != 3:
        raise SegmentationError('INVALID_MODEL_OUTPUT')
    masks, scores, low_res = output
    if not all(isinstance(value, np.ndarray) and value.dtype.kind in 'bfiu'
               for value in (masks, scores, low_res)):
        raise SegmentationError('INVALID_MODEL_ARRAYS')
    if not (masks.ndim == 3 and 1 <= masks.shape[0] <= 3 and masks.shape[1:] == (height, width)
            and scores.shape == (masks.shape[0],) and low_res.shape == (masks.shape[0], 256, 256)):
        raise SegmentationError('INVALID_MODEL_SHAPES')
    if not (np.isfinite(masks).all() and np.isfinite(scores).all() and np.isfinite(low_res).all()
            and np.all((masks == 0) | (masks == 1)) and np.all((scores >= 0) & (scores <= 1))):
        raise SegmentationError('INVALID_MODEL_VALUES')
    return masks.astype(bool, copy=True), scores.astype(np.float64, copy=True)


def _choose_mask(region, masks, scores, minimum_quality):
    counts = np.count_nonzero(masks, axis=(1, 2))
    consistent = [all(bool(mask[int(y), int(x)]) == bool(label) for x, y, label in region.points)
                  for mask in masks]
    useful = [index for index, count in enumerate(counts)
              if consistent[index] and 0 < count < .98 * masks.shape[1] * masks.shape[2]]
    selected = max(useful or range(len(scores)), key=lambda index: (scores[index], -index))
    reasons = []
    if counts[selected] == 0: reasons.append('empty_mask')
    if counts[selected] >= .98 * masks.shape[1] * masks.shape[2]: reasons.append('full_frame_mask')
    if not consistent[selected]: reasons.append('prompt_violation')
    if scores[selected] < minimum_quality: reasons.append('low_mask_quality')
    return PromptMask(region.id, region.kind, masks[selected].astype(np.uint8).tobytes(),
                      float(scores[selected]), int(selected), not reasons, tuple(reasons),
                      tuple(float(value) for value in scores))


class PromptedSegmenter:
    def __init__(self, predictor, minimum_quality=.8):
        if not _number(minimum_quality) or not 0 <= minimum_quality <= 1:
            raise SegmentationError('INVALID_QUALITY_THRESHOLD')
        self.predictor = predictor
        self.minimum_quality = float(minimum_quality)
        self._lock = threading.Lock()

    def segment(self, rgb, regions, cancelled=lambda: False):
        if not (isinstance(rgb, np.ndarray) and rgb.dtype == np.uint8 and rgb.ndim == 3
                and rgb.shape[2] == 3 and all(1 <= size <= 1536 for size in rgb.shape[:2])
                and rgb.shape[0] * rgb.shape[1] <= 4_000_000):
            raise SegmentationError('INVALID_WORKING_RGB')
        height, width = rgb.shape[:2]
        if not isinstance(regions, Iterable) or isinstance(regions, (str, bytes, dict)):
            raise SegmentationError('INVALID_REGIONS')
        requests = []
        for region in regions:
            if len(requests) >= 63: raise SegmentationError('TOO_MANY_REGIONS')
            requests.append(_checked_region(region, width, height))
        if not requests or len({region.id for region in requests}) != len(requests):
            raise SegmentationError('EMPTY_OR_DUPLICATE_REGIONS')
        if cancelled(): raise SegmentationCancelled()
        with self._lock:
            image = np.array(rgb, copy=True, order='C')
            sha = hashlib.sha256(b'RigRgb-v1\0' + struct.pack('>II', width, height) + image.tobytes()).hexdigest()
            masks = []
            try:
                if cancelled(): raise SegmentationCancelled()
                self.predictor.set_image(image.copy())
                for region in requests:
                    if cancelled(): raise SegmentationCancelled()
                    points = np.array([point[:2] for point in region.points], np.float32) if region.points else None
                    labels = np.array([point[2] for point in region.points], np.int32) if region.points else None
                    box = np.array(region.box, np.float32) if region.box is not None else None
                    output = self.predictor.predict(point_coords=points, point_labels=labels, box=box,
                                                    multimask_output=True, return_logits=False, normalize_coords=True)
                    if cancelled(): raise SegmentationCancelled()
                    selected, scores = _checked_output(output, width, height)
                    masks.append(_choose_mask(region, selected, scores, self.minimum_quality))
            except (SegmentationError, SegmentationCancelled):
                raise
            except Exception as error:
                raise SegmentationError('MODEL_EXECUTION_FAILED') from error
            finally:
                try:
                    self.predictor.reset_predictor()
                except Exception as error:
                    raise SegmentationError('MODEL_RESET_FAILED') from error
            if cancelled(): raise SegmentationCancelled()
            return SegmentedFrame(width, height, sha, tuple(masks))
