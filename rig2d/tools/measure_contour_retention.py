"""Full working-image pixel audit; never certifies identity or changes a proxy."""
import argparse
import json
import math
from dataclasses import asdict
from pathlib import Path
import sys
import numpy as np

from fit_proxy_contour import Contour, contour_mask, finite_real


def coverage_masks(alpha, box, contour):
    if not (isinstance(alpha, np.ndarray) and alpha.ndim == 2 and alpha.dtype == np.bool_
            and all(8 <= side <= 1024 for side in alpha.shape) and alpha.any()):
        raise ValueError('Expected nonempty boolean working-image alpha, dimensions 8..1024')
    if not isinstance(box, (tuple, list)) or len(box) != 4:
        raise ValueError('Expected four finite box coordinates')
    try:
        x0, y0, x1, y1 = (float(value) for value in box)
    except (TypeError, ValueError, OverflowError) as error:
        raise ValueError('Invalid box coordinates') from error
    h, w = alpha.shape
    if not (all(math.isfinite(value) for value in (x0, y0, x1, y1))
            and 0 <= x0 < x1 < w and 0 <= y0 < y1 < h):
        raise ValueError('Box must be finite and inside the working image')
    contour_mask(8, 8, contour)  # Reuse the bounded contour validation.
    bw, bh = x1 - x0 + 1, y1 - y0 + 1
    cx = x0 + bw * (.5 + contour.center_x)
    cy = y0 + bh * (.5 + contour.center_y)
    rx, ry = bw * contour.radius_x, bh * contour.radius_y
    if cx - rx < 0 or cy - ry < 0 or cx + rx > w or cy + ry > h:
        raise ValueError('Geometry would be clipped by the working-image boundary')
    xs = np.arange(w, dtype=np.float64) + .5
    ys = np.arange(h, dtype=np.float64) + .5
    roi = ((ys[:, None] >= y0) & (ys[:, None] < y1 + 1)
           & (xs[None, :] >= x0) & (xs[None, :] < x1 + 1))
    geometry = (np.abs((xs[None, :] - cx) / rx) ** contour.exponent
                + np.abs((ys[:, None] - cy) / ry) ** contour.exponent <= 1)
    if not (alpha & roi).any():
        raise ValueError('The original head ROI has no foreground')
    return roi, geometry

def audit_coverage(alpha, box, contour):
    roi, geometry = coverage_masks(alpha, box, contour)
    source = int(np.count_nonzero(alpha & roi))
    missed = int(np.count_nonzero(alpha & roi & ~geometry))
    outside = int(np.count_nonzero(alpha & ~roi))
    all_missed = int(np.count_nonzero(alpha & ~geometry))
    background = int(np.count_nonzero(geometry & ~alpha))
    covered = int(np.count_nonzero(geometry))
    return {'claim': 'WORKING_PIXEL_COVERAGE_NOT_IDENTITY', 'original_head_box': list(box),
            'parameters': asdict(contour), 'head_roi_source_pixels': source,
            'head_roi_missed_pixels': missed, 'head_roi_retention': (source - missed) / source,
            'head_roi_complete': missed == 0, 'foreground_outside_head_roi': outside,
            'all_foreground_pixels': int(np.count_nonzero(alpha)),
            'all_foreground_missed_pixels': all_missed,
            'complete_character_coverage': all_missed == 0,
            'geometry_pixels': covered, 'geometry_background_pixels': background,
            'geometry_background_ratio': background / covered if covered else 1.,
            'identity_validated': False, 'source_modified': False}


def fixture_audit(study):
    from PIL import Image
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'probe'))
    from run_probe import load_input
    rgb, alpha, _, _ = load_input(None, study['fixture'])
    box = study['head_box']  # Keep the original ROI as denominator.
    candidate = Contour(**study['best']['parameters'])
    roi, geometry = coverage_masks(alpha, box, candidate)
    overlay = (rgb * 255).astype(np.uint8)
    overlay[alpha & roi & ~geometry] = (230, 30, 180)
    overlay[alpha & ~roi] = (40, 130, 240)
    overlay[geometry & ~alpha] = (245, 170, 20)
    return {'fixture': study['fixture'], 'input_claim': 'SYNTHETIC_FIXTURE_NOT_MODEL',
            'baseline': audit_coverage(alpha, box, Contour()),
            'candidate': audit_coverage(alpha, box, candidate)}, Image.fromarray(overlay)


def validated_study(data):
    if not isinstance(data, dict) or data.get('claim') != 'SYNTHETIC_ANALYTIC_STUDY_ONLY':
        raise ValueError('Only an object describing the local synthetic study is supported')
    cases = data.get('cases')
    if not isinstance(cases, list) or len(cases) != 2 or not all(isinstance(case, dict) for case in cases):
        raise ValueError('Expected two synthetic case objects')
    if [case.get('fixture') for case in cases] != ['color', 'line_art']:
        raise ValueError('Unexpected synthetic fixtures')
    expected = set(asdict(Contour()))
    for case in cases:
        box, best = case.get('head_box'), case.get('best')
        if not isinstance(box, list) or len(box) != 4 or not all(finite_real(value) for value in box):
            raise ValueError('Each head box must have four finite numeric coordinates')
        parameters = best.get('parameters') if isinstance(best, dict) else None
        if not isinstance(parameters, dict) or set(parameters) != expected or not all(
                finite_real(value) for value in parameters.values()):
            raise ValueError('Each contour must have five finite numeric parameters')
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    try:
        with args.study.open('rb') as stream:
            raw = stream.read(64 * 1024 + 1)
        if len(raw) > 64 * 1024:
            raise ValueError('Study JSON exceeds the diagnostic input limit')
        audited = [fixture_audit(case) for case in validated_study(json.loads(raw.decode('utf-8')))]
        args.out.mkdir(parents=True, exist_ok=False)  # Never replace earlier evidence.
    except (OSError, ValueError, RecursionError) as error:
        parser.error(str(error))
    result = {'claim': 'SYNTHETIC_FULL_WORKING_PIXEL_AUDIT_NOT_IDENTITY',
              'cases': [row for row, _ in audited], 'original_thresholds_changed': False,
              'legend': {'magenta': 'original-head foreground excluded by candidate',
                         'blue': 'foreground outside original head ROI, not audited as head',
                         'orange': 'background under candidate geometry'}}
    with (args.out / 'retention.json').open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    for row, overlay in audited:
        overlay.save(args.out / (row['fixture'] + '-retention-overlay.png'))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__': main()
