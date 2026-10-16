import unittest
import numpy as np
from segmentation.ink_refinement import extract_seeded_ink, InkRefinementError


class InkRefinementTest(unittest.TestCase):
    def fixture(self):
        rgb = np.full((12, 14, 3), [180, 120, 80], dtype=np.uint8)
        mask = np.zeros((12, 14), bool)
        mask[3, 2:11] = True
        mask[3:9, 10] = True
        rgb[mask] = [30, 60, 100]
        return rgb, np.ones((12, 14), bool), mask

    def run_case(self, rgb=None, support=None, **kwargs):
        base, area, _ = self.fixture()
        return extract_seeded_ink(base if rgb is None else rgb, area if support is None else support,
                                  kwargs.pop('foreground', [(3, 3)]), kwargs.pop('background', [(0, 0)]), **kwargs)

    def decode(self, draft):
        return np.frombuffer(draft.pixels, np.uint8).reshape(draft.height, draft.width).astype(bool)

    def test_adapts_to_seed_colors_and_preserves_one_pixel_l_shape(self):
        rgb, support, expected = self.fixture()
        draft = self.run_case(rgb, support)
        np.testing.assert_array_equal(expected, self.decode(draft))
        self.assertTrue(draft.review_required)
        self.assertFalse(draft.identity_verified)
        self.assertIsInstance(draft.pixels, bytes)

    def test_light_and_colored_ink_are_supported_without_fixed_gray_threshold(self):
        rgb, support, expected = self.fixture()
        for ink, background in (([230, 240, 250], [30, 20, 10]), ([220, 40, 130], [40, 140, 210])):
            with self.subTest(ink=ink):
                rgb[:] = background
                rgb[expected] = ink
                np.testing.assert_array_equal(expected, self.decode(self.run_case(rgb, support)))

    def test_all_seeded_components_and_diagonal_single_pixel_connection_survive(self):
        rgb, support, expected = self.fixture()
        expected[9, 11] = True
        expected[10, 12] = True
        expected[1, 12] = True
        rgb[expected] = [30, 60, 100]
        draft = self.run_case(rgb, support, foreground=[(3, 3), (12, 1)])
        np.testing.assert_array_equal(expected, self.decode(draft))

    def test_unseeded_equal_color_island_is_not_implicitly_foreground(self):
        rgb, support, expected = self.fixture()
        rgb[10, 2:5] = [30, 60, 100]
        np.testing.assert_array_equal(expected, self.decode(self.run_case(rgb, support)))

    def test_unseeded_candidates_are_retained_separately_for_review(self):
        rgb, support, expected = self.fixture()
        rgb[10, 2:5] = [30, 60, 100]
        draft = self.run_case(rgb, support)
        possible = np.frombuffer(draft.unseeded_candidate_pixels, np.uint8).reshape(12, 14).astype(bool)
        self.assertEqual(3, int(possible.sum()))
        self.assertTrue(possible[10, 2:5].all())
        self.assertFalse((possible & self.decode(draft)).any())
        self.assertTrue(draft.review_required)

    def test_protection_and_hard_support_are_respected_in_unseeded_candidates(self):
        rgb, support, expected = self.fixture()
        rgb[10, 2:5] = [30, 60, 100]
        support[10, 4] = False
        protection = np.zeros_like(support)
        protection[10, 2] = True
        draft = self.run_case(rgb, support, protected=protection)
        possible = np.frombuffer(draft.unseeded_candidate_pixels, np.uint8).reshape(12, 14).astype(bool)
        self.assertTrue(possible[10, 3])
        self.assertFalse(possible[10, 2] or possible[10, 4])

    def test_support_is_a_hard_boundary_without_dilating_over_a_gap(self):
        rgb, support, expected = self.fixture()
        support[3, 7] = False
        result = self.decode(self.run_case(rgb, support))
        self.assertTrue(result[3, 6])
        self.assertFalse(result[3, 7])
        self.assertFalse(result[8, 10])
        self.assertFalse((result & ~support).any())

    def test_nearby_antialias_color_can_be_retained_when_separated_from_background(self):
        rgb, support, expected = self.fixture()
        expected[4, 3] = True
        rgb[4, 3] = [40, 70, 110]
        np.testing.assert_array_equal(expected, self.decode(self.run_case(rgb, support, tolerance=20)))
        self.assertFalse(self.decode(self.run_case(rgb, support, tolerance=0))[4, 3])

    def test_manual_protection_keeps_a_tiny_remote_region_outside_support(self):
        rgb, support, expected = self.fixture()
        support[10, 13] = False
        protection = np.zeros_like(support)
        protection[10, 13] = True
        draft = self.run_case(rgb, support, protected=protection)
        expected[10, 13] = True
        np.testing.assert_array_equal(expected, self.decode(draft))
        self.assertEqual(1, draft.protected_pixels)

    def test_ambiguous_identical_foreground_and_background_colors_are_rejected(self):
        rgb = np.full((12, 14, 3), 123, np.uint8)
        with self.assertRaisesRegex(InkRefinementError, 'AMBIGUOUS'):
            self.run_case(rgb)

    def test_negative_seed_cannot_be_protected_and_positive_must_be_supported(self):
        rgb, support, _ = self.fixture()
        protected = np.zeros_like(support)
        protected[0, 0] = True
        with self.assertRaises(InkRefinementError): self.run_case(rgb, support, protected=protected)
        support[3, 3] = False
        with self.assertRaises(InkRefinementError): self.run_case(rgb, support)

    def test_input_arrays_are_unchanged_and_output_has_no_mutable_alias(self):
        rgb, support, expected = self.fixture()
        before = rgb.copy()
        draft = self.run_case(rgb, support)
        np.testing.assert_array_equal(before, rgb)
        rgb[:] = 0
        support[:] = False
        np.testing.assert_array_equal(expected, self.decode(draft))
        next_rgb, next_support, _ = self.fixture()
        self.assertEqual(draft.source_pixel_sha256, self.run_case(next_rgb, next_support).source_pixel_sha256)
        next_rgb[0, 1] = 0
        self.assertNotEqual(draft.source_pixel_sha256, self.run_case(next_rgb, next_support).source_pixel_sha256)

    def test_invalid_frames_and_resource_dimensions_produce_typed_errors(self):
        for rgb in (None, np.zeros((0, 14, 3), np.uint8), np.zeros((12, 14, 4), np.uint8),
                    np.zeros((12, 14, 3), np.float32), np.zeros((1537, 2, 3), np.uint8)):
            with self.subTest(shape=getattr(rgb, 'shape', None)):
                with self.assertRaises(InkRefinementError):
                    extract_seeded_ink(rgb, np.ones((12, 14), bool), [(3, 3)], [(0, 0)])

    def test_support_and_protection_require_exact_bool_shapes(self):
        for support in (None, np.ones((12, 14), np.uint8), np.ones((11, 14), bool)):
            with self.assertRaises(InkRefinementError):
                extract_seeded_ink(self.fixture()[0], support, [(3, 3)], [(0, 0)])
        with self.assertRaises(InkRefinementError): self.run_case(protected=np.ones((1, 2), bool))

    def test_seed_types_bounds_counts_and_missing_evidence_are_rejected(self):
        invalid = (None, [], 'seed', [(14, 3)], [(2, -1)], [(3.1, 3)], [(True, 3)],
                   [(3, 3, 1)], [(3, 3)] * 65)
        for points in invalid:
            for key in ('foreground', 'background'):
                with self.subTest(key=key, points=points):
                    with self.assertRaises(InkRefinementError): self.run_case(**{key: points})

    def test_thresholds_are_finite_bounded_numbers(self):
        for number in (None, True, float('nan'), float('inf'), -1, 443, '32'):
            for key in ('tolerance', 'separation'):
                with self.subTest(key=key, number=number):
                    with self.assertRaises(InkRefinementError): self.run_case(**{key: number})


if __name__ == '__main__': unittest.main()
