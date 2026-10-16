import sys
import unittest
import subprocess
import json
import tempfile
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fit_proxy_contour import Contour
from measure_contour_retention import audit_coverage


class ContourRetentionTest(unittest.TestCase):
    def ellipse(self):
        y, x = np.mgrid[:64, :64]
        return ((x + .5 - 32) / 24) ** 2 + ((y + .5 - 32) / 24) ** 2 <= 1

    def test_inclusive_box_matches_pixel_centers_and_preserves_input(self):
        alpha = self.ellipse(); original = alpha.copy()
        result = audit_coverage(alpha, (8., 8., 55., 55.), Contour(2.))
        self.assertEqual(1., result['head_roi_retention'])
        self.assertEqual(0, result['head_roi_missed_pixels'])
        self.assertTrue(result['head_roi_complete'])
        self.assertFalse(result['identity_validated'])
        np.testing.assert_array_equal(alpha, original)

    def test_tiny_oc_attachment_is_reported_even_above_95_percent_retention(self):
        alpha = self.ellipse(); alpha[8, 8] = True
        result = audit_coverage(alpha, (8., 8., 55., 55.), Contour(2.))
        self.assertGreater(result['head_roi_retention'], .95)
        self.assertEqual(1, result['head_roi_missed_pixels'])
        self.assertFalse(result['head_roi_complete'])
        self.assertFalse(result['complete_character_coverage'])

    def test_foreground_outside_head_is_not_silently_called_preserved(self):
        alpha = self.ellipse(); alpha[1, 1] = True
        result = audit_coverage(alpha, (8., 8., 55., 55.), Contour(2.))
        self.assertTrue(result['head_roi_complete'])
        self.assertEqual(1, result['foreground_outside_head_roi'])
        self.assertFalse(result['complete_character_coverage'])

    def test_background_under_geometry_is_counted_separately(self):
        alpha = self.ellipse(); alpha[32, 32] = False
        result = audit_coverage(alpha, (8., 8., 55., 55.), Contour(2.))
        self.assertEqual(1, result['geometry_background_pixels'])
        self.assertGreater(result['geometry_background_ratio'], 0)

    def test_geometry_extending_beyond_frame_is_rejected(self):
        with self.assertRaises(ValueError):
            audit_coverage(np.ones((64, 64), bool), (0., 0., 63., 63.), Contour(8., .6, .6))

    def test_invalid_masks_boxes_and_parameters_are_rejected(self):
        cases = [(np.ones((1025, 8), bool), (0, 0, 7, 7), Contour()),
                 (np.ones((8, 8, 3), bool), (0, 0, 7, 7), Contour()),
                 (np.ones((8, 8)), (0, 0, 7, 7), Contour()),
                 (np.zeros((8, 8), bool), (0, 0, 7, 7), Contour()),
                 (np.ones((64, 64), bool), (8, float('nan'), 55, 55), Contour()),
                 (np.ones((64, 64), bool), (8, 8, 64, 55), Contour()),
                 (np.ones((64, 64), bool), (55, 8, 8, 55), Contour()),
                 (np.ones((64, 64), bool), (8, 8, 55, 55), Contour(0.)),
                 (np.ones((64, 64), bool), (8, 8, 55, 55), Contour('2.')),
                 (np.ones((64, 64), bool), (8, 8, 55, 55), Contour(10 ** 400))]
        for alpha, box, contour in cases:
            with self.subTest(shape=alpha.shape, box=box, contour=contour):
                with self.assertRaises(ValueError): audit_coverage(alpha, box, contour)

    def malformed_study(self, data, raw_text=None):
        with tempfile.TemporaryDirectory() as directory:
            study = Path(directory) / 'study.json'
            out = Path(directory) / 'out'
            study.write_text(json.dumps(data) if raw_text is None else raw_text, encoding='utf-8')
            run = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1]
                                  / 'measure_contour_retention.py'), '--study', str(study),
                                  '--out', str(out)], capture_output=True, text=True, timeout=10)
            self.assertEqual(2, run.returncode, run.stderr)
            self.assertNotIn('Traceback', run.stderr)
            self.assertFalse(out.exists())

    def test_cli_rejects_non_object_top_level_without_traceback_or_output(self):
        self.malformed_study([])

    def test_cli_rejects_non_object_cases_without_traceback_or_output(self):
        self.malformed_study({'claim': 'SYNTHETIC_ANALYTIC_STUDY_ONLY', 'cases': [[], []]})

    def test_cli_rejects_non_numeric_contour_without_traceback_or_output(self):
        cases = [{'fixture': name, 'head_box': [8, 8, 55, 55],
                  'best': {'parameters': {'exponent': '2.'}}} for name in ('color', 'line_art')]
        self.malformed_study({'claim': 'SYNTHETIC_ANALYTIC_STUDY_ONLY', 'cases': cases})

    def test_cli_rejects_overflowing_numeric_parameters_without_traceback_or_output(self):
        parameters = {'exponent': 10 ** 400, 'radius_x': .5, 'radius_y': .5,
                      'center_x': 0, 'center_y': 0}
        cases = [{'fixture': name, 'head_box': [8, 8, 55, 55],
                  'best': {'parameters': parameters}} for name in ('color', 'line_art')]
        self.malformed_study({'claim': 'SYNTHETIC_ANALYTIC_STUDY_ONLY', 'cases': cases})

    def test_cli_rejects_deeply_nested_json_without_traceback_or_output(self):
        self.malformed_study(None, '[' * 5000 + '0' + ']' * 5000)


if __name__ == '__main__': unittest.main()
