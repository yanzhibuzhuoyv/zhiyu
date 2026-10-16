import sys
import unittest
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fit_proxy_contour import Contour, contour_mask, score_mask, fit_mask

class ContourStudyTest(unittest.TestCase):
    def ellipse(self):
        y, x = np.mgrid[:61, :71]
        return (((x + .5) / 71 - .5) / .5) ** 2 + (((y + .5) / 61 - .5) / .5) ** 2 <= 1

    def test_exact_ellipse_can_fit_without_cropping_or_invented_background(self):
        target = self.ellipse(); original = target.copy()
        result = fit_mask(target)
        self.assertTrue(result['analytic_target_met'])
        self.assertGreaterEqual(result['best']['source_retention'], .95)
        self.assertLessEqual(result['best']['geometry_background'], .02)
        np.testing.assert_array_equal(target, original)

    def test_two_disconnected_small_islands_cannot_be_labeled_a_success(self):
        target = np.zeros((33, 33), dtype=bool)
        target[2:8, 2:8] = True; target[-8:-2, -8:-2] = True
        self.assertFalse(fit_mask(target)['analytic_target_met'])

    def test_smaller_proxy_is_rejected_even_if_all_its_pixels_exist_in_source(self):
        result = score_mask(self.ellipse(), Contour(2., .25, .25))
        self.assertLess(result['source_retention'], .95)
        self.assertFalse(result['feasible'])

    def test_expansion_outside_roi_is_counted_as_background_instead_of_clipped_away(self):
        target = np.ones((33, 33), dtype=bool)
        result = score_mask(target, Contour(8., .6, .6))
        self.assertGreater(result['geometry_background'], .02)
        self.assertFalse(result['feasible'])

    def test_invalid_or_unbounded_masks_are_rejected(self):
        for target in (np.zeros((33, 33), bool), np.zeros((3, 3), bool),
                       np.ones((257, 257), bool), np.full((33, 33), np.nan), np.ones((33, 33, 2), bool)):
            with self.subTest(shape=target.shape):
                with self.assertRaises(ValueError): fit_mask(target)

    def test_parameters_reject_non_finite_or_unsafe_ranges(self):
        for parameters in (Contour(float('nan')), Contour(0.), Contour(2., 0.),
                           Contour(2., .8), Contour(center_x=float('inf')),
                           Contour(10 ** 400), Contour('2.'), Contour(True)):
            with self.assertRaises(ValueError): contour_mask(33, 33, parameters)

    def test_candidate_set_and_results_are_deterministic_and_bounded(self):
        first = fit_mask(self.ellipse())
        self.assertEqual(first, fit_mask(self.ellipse()))
        self.assertLessEqual(first['candidate_count'], 700)
        self.assertEqual('ANALYTIC_STUDY_NOT_PROJECTION_NOT_IDENTITY', first['claim'])

if __name__ == '__main__': unittest.main()
