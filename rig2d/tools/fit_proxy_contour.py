"""Bounded analytic contour study. Does not change any production proxy or gate."""
from dataclasses import dataclass, asdict
from itertools import product
from pathlib import Path
import argparse
import json
import math
import sys
import time
from numbers import Real
import numpy as np

@dataclass(frozen=True)
class Contour:
    exponent: float = 4.
    radius_x: float = .5
    radius_y: float = .5
    center_x: float = 0.
    center_y: float = 0.

def finite_real(value):
    if not isinstance(value, Real) or isinstance(value, bool):
        return False
    try:
        return math.isfinite(float(value))
    except (OverflowError, TypeError, ValueError):
        return False

def contour_mask(height, width, contour):
    if not (isinstance(height, int) and isinstance(width, int) and 8 <= height <= 256 and 8 <= width <= 256):
        raise ValueError('Study dimensions must be 8..256')
    if not isinstance(contour, Contour) or not all(finite_real(value) for value in asdict(contour).values()):
        raise ValueError('Contour parameters must be finite real numbers')
    if not (1 <= contour.exponent <= 8 and .1 <= contour.radius_x <= .65 and .1 <= contour.radius_y <= .65
            and abs(contour.center_x) <= .1 and abs(contour.center_y) <= .1):
        raise ValueError('Unsafe contour bounds')
    # Account for the entire shape, including expansion outside the original ROI.
    py, px = math.ceil(height * .3), math.ceil(width * .3)
    x = (np.arange(-px, width + px, dtype=np.float64) + .5) / width - .5
    y = (np.arange(-py, height + py, dtype=np.float64) + .5) / height - .5
    return (np.abs((x[None, :] - contour.center_x) / contour.radius_x) ** contour.exponent
            + np.abs((y[:, None] - contour.center_y) / contour.radius_y) ** contour.exponent <= 1)

def checked_mask(target):
    if not (isinstance(target, np.ndarray) and target.ndim == 2 and target.dtype == np.bool_
            and all(8 <= side <= 256 for side in target.shape) and target.any()):
        raise ValueError('Study needs a nonempty bounded boolean head ROI')
    return target

def score_mask(target, contour):
    checked_mask(target)
    h, w = target.shape
    geometry = contour_mask(h, w, contour)
    py, px = math.ceil(h * .3), math.ceil(w * .3)
    expected = np.pad(target, ((py, py), (px, px)), constant_values=False)
    intersection = int(np.count_nonzero(geometry & expected))
    source = int(np.count_nonzero(expected))
    covered = int(np.count_nonzero(geometry))
    retention = intersection / source
    background = (covered - intersection) / covered if covered else 1.
    union = source + covered - intersection
    return {'parameters': asdict(contour), 'source_retention': retention,
            'geometry_background': background, 'iou': intersection / union,
            'feasible': retention >= .95 and background <= .02,
            'source_pixels': source, 'geometry_pixels': covered}

def fit_mask(target):
    checked_mask(target)
    candidates = [Contour(*values) for values in product((1.5, 2., 2.5, 3., 3.5, 4., 5., 6.),
                  (.48, .5, .52), (.48, .5, .52), (-.04, 0., .04), (-.04, 0., .04))]
    rows = [score_mask(target, candidate) for candidate in candidates]
    def order(row):
        violation = max(0., .95 - row['source_retention']) + max(0., row['geometry_background'] - .02)
        return (not row['feasible'], violation, -row['iou'], tuple(row['parameters'].values()))
    best = min(rows, key=order)
    return {'claim': 'ANALYTIC_STUDY_NOT_PROJECTION_NOT_IDENTITY', 'candidate_count': len(rows),
            'criteria': {'source_retention_min': .95, 'geometry_background_max': .02},
            'baseline': score_mask(target, Contour()), 'best': best,
            'analytic_target_met': best['feasible']}

def study_fixture(name):
    # Only known synthetic inputs. No private media or model weights are consulted.
    from PIL import Image
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'probe'))
    from run_probe import load_input
    from inputprofile import fit_head_box, profile_input, validate_head_box
    rgb, alpha, _, metadata = load_input(None, name)
    h, w = alpha.shape
    box = fit_head_box(alpha, rgb, profile_input(rgb, alpha))
    validate_head_box(box, (w, h))
    x0, y0, x1, y1 = [int(round(value)) for value in box]
    cropped = alpha[y0:y1 + 1, x0:x1 + 1]
    image = Image.fromarray(cropped.astype(np.uint8) * 255)
    image.thumbnail((128, 128), Image.Resampling.NEAREST)
    target = np.asarray(image) > 0
    if min(target.shape) < 8:
        raise ValueError('Head ROI too thin for this diagnostic')
    result = fit_mask(target)
    return {'fixture': name, 'input_claim': metadata.get('not_model'), 'head_box': list(box),
            'study_size': list(target.shape), **result,
            'localization_not_validated': True,
            'full_projection_not_validated': True}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists() or args.out.is_symlink():
        parser.error('Study output already exists; cannot replace earlier evidence')
    started = time.monotonic()
    cases = [study_fixture(name) for name in ('color', 'line_art')]
    result = {'claim': 'SYNTHETIC_ANALYTIC_STUDY_ONLY', 'cases': cases,
              'elapsed_seconds': time.monotonic() - started,
              'production_changed': False, 'original_thresholds_changed': False}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'cases': [{'fixture': row['fixture'], 'analytic_target_met': row['analytic_target_met'],
                       'best': row['best']} for row in cases], 'elapsed_seconds': result['elapsed_seconds']}))

if __name__ == '__main__': main()
