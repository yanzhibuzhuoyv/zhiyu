"""Local numerical/receipt regressions; synthetic data, no models or private media."""
from __future__ import annotations

import contextlib
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import inputprofile as ip
import run_probe as runner
from fill import PatchMatchResult, provenance_is_from_image
from geom import ProxyShape, grid_directions, implicit, surface_normal, surface_point
from headproxy import build_proxy
from project import TextureGrid, dilate_texture, render_view, symmetry_mirror, uv_index


def stages():
    return {"B_patchmatch": [{"view": name, "hole_ratio": 0.01}
                            for name in runner.VIEW_NAMES.values()]}


class HeadBoxBoundaryTest(unittest.TestCase):
    def test_profile_rejects_nonfinite_rgb_and_bad_mask_before_statistics(self):
        rgb = np.full((20, 20, 3), 0.5)
        alpha = np.ones((20, 20), dtype=bool)
        cases = [(np.full_like(rgb, np.nan), alpha), (rgb, np.zeros((20, 20), bool)),
                 (rgb, np.ones((20, 19), bool)), (rgb, np.ones((20, 20), float)),
                 (np.full_like(rgb, 1.5), alpha)]
        for image, mask in cases:
            with self.subTest(shape=mask.shape), self.assertRaises(ValueError):
                ip.profile_input(image, mask)

    def test_landscape_width_height_are_not_swapped(self):
        ip.validate_head_box((850, 100, 950, 300), (1000, 800))

    def test_landscape_bottom_outside_image_is_rejected(self):
        with self.assertRaises(ValueError):
            ip.validate_head_box((100, 750, 300, 900), (1000, 800))

    def test_inclusive_endpoint_cannot_equal_image_width(self):
        with self.assertRaises(ValueError):
            ip.validate_head_box((800, 100, 1000, 300), (1000, 1000))

    def test_parser_rejects_nan_and_infinity_in_any_coordinate(self):
        for text in ("nan,0,100,100", "0,0,inf,100", "0,nan,100,100", "0,0,100,inf"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                ip.parse_head_box(text)

    def test_shape_rejection_reports_manual_fallback_not_key_error(self):
        rgb = np.full((100, 100, 3), 0.2)
        alpha = np.ones((100, 100), dtype=bool)
        profile = ip.profile_input(rgb, alpha)
        with patch.object(ip, "_fit_head_box_line_art", return_value=(0., 0., 99., 99.)):
            with self.assertRaisesRegex(ValueError, "--head-box"):
                ip.fit_head_box(alpha, rgb, profile)

    def test_overlay_converts_world_center_using_source_pixel_scale(self):
        rgb = np.ones((100, 200, 3))
        box = (120., 10., 180., 70.)
        scale = 0.01
        center = ip.box_center_world(box, 200, 100, scale)
        overlay = ip.draw_proxy_overlay(rgb, box, center, (20., 20.), pixel_scale=scale)
        magenta = np.all(overlay == [1., 0., 0.35], axis=-1)
        yy, xx = np.nonzero(magenta)
        self.assertAlmostEqual(float(xx.mean()), 150.5, delta=1.)
        self.assertAlmostEqual(float(yy.mean()), 40.5, delta=1.)


class SourcePixelTransportTest(unittest.TestCase):
    def test_render_keeps_world_up_at_top_of_image_not_upside_down(self):
        rows, cols = 32, 32
        directions = grid_directions(cols, rows)
        color = np.zeros((rows, cols, 3))
        color[directions[..., 2] > 0] = [1., 0., 0.]
        color[directions[..., 2] < 0] = [0., 0., 1.]
        proxy = build_proxy(16, 16, exponent=2.)
        rendered = render_view(proxy, TextureGrid(color, np.ones((rows, cols), bool), 0, 0),
                               0., 64, 0.01)
        np.testing.assert_array_equal(rendered.color[16, 32], [1., 0., 0.])
        np.testing.assert_array_equal(rendered.color[48, 32], [0., 0., 1.])

    def test_mirror_uses_same_uv_coordinate_system_as_projection(self):
        rows, cols = 16, 20
        proxy = build_proxy(8, 8, radii=np.array([2., 0.5, 1.]), exponent=4.)
        rgb = np.zeros((rows, cols, 3))
        valid = np.zeros((rows, cols), dtype=bool)
        r, c = 3, 7
        valid[r, c] = True
        rgb[r, c] = [0.9, 0.2, 0.1]
        mirrored = symmetry_mirror(TextureGrid(rgb, valid, 1, 0), proxy)
        directions = grid_directions(cols, rows)
        directions[..., 0] *= -1.
        mr, mc = uv_index(directions.reshape(-1, 3), cols, rows)
        expected = valid[mr, mc].reshape(rows, cols) | valid
        np.testing.assert_array_equal(mirrored.valid, expected)
        np.testing.assert_array_equal(mirrored.color[mirrored.valid],
                                      np.tile([0.9, 0.2, 0.1], (int(expected.sum()), 1)))

    def test_uv_seam_dilation_copies_valid_source_color_not_invalid_edge(self):
        color = np.zeros((3, 8, 3))
        valid = np.zeros((3, 8), dtype=bool)
        valid[1, 0] = True
        color[1, 0] = [0.8, 0.3, 0.1]
        out = dilate_texture(TextureGrid(color, valid, 1, 0), build_proxy(8, 8), iterations=1)
        self.assertTrue(out.valid[1, -1])
        np.testing.assert_array_equal(out.color[1, -1], color[1, 0])
        np.testing.assert_array_equal(out.color[out.valid],
                                      np.tile(color[1, 0], (int(out.valid.sum()), 1)))

    def test_pole_dilation_does_not_wrap_north_to_south(self):
        color = np.zeros((6, 8, 3))
        valid = np.zeros((6, 8), dtype=bool)
        valid[0, 4] = True
        color[0, 4] = [0.8, 0.3, 0.1]
        out = dilate_texture(TextureGrid(color, valid, 1, 0), build_proxy(8, 8), iterations=1)
        self.assertFalse(out.valid[-1].any())

    def test_unattributed_filled_pixel_fails_provenance(self):
        result = PatchMatchResult(np.ones((1, 2, 3)), np.ones((1, 2), bool), 1,
                                  np.full((1, 2, 2), -1), 1, "done", 0, 1, 1)
        self.assertFalse(provenance_is_from_image(result, {(0, 0)}))

    def test_wrong_color_despite_valid_source_pointer_fails_provenance(self):
        color = np.zeros((1, 2, 3)); color[0, 0] = [1., 0., 0.]
        used = np.full((1, 2, 2), -1); used[0, 1] = [0, 0]
        result = PatchMatchResult(color, np.ones((1, 2), bool), 1, used, 1, "done", 0, 1, 1)
        self.assertFalse(provenance_is_from_image(result, {(0, 0)}))

    def test_valid_chain_from_own_source_pixel_is_accepted(self):
        color = np.tile([0.1, 0.2, 0.3], (1, 3, 1))
        used = np.full((1, 3, 2), -1); used[0, 1] = [0, 0]; used[0, 2] = [0, 1]
        result = PatchMatchResult(color, np.ones((1, 3), bool), 2, used, 2, "done", 0, 1, 2)
        self.assertTrue(provenance_is_from_image(result, {(0, 0)}))


class GeometryConsistencyTest(unittest.TestCase):
    def test_surface_normal_matches_numeric_gradient_of_actual_surface(self):
        for exponent in (1., 2., 4., 8.):
            with self.subTest(exponent=exponent):
                shape = ProxyShape(np.array([0.4, 0.8, 1.2]), exponent)
                points = surface_point(np.array([[0.4, -0.7, 0.2]]), shape)
                derivative = []
                for axis in range(3):
                    offset = np.zeros((1, 3)); offset[0, axis] = 1e-6
                    derivative.append(float((implicit(points + offset, shape) -
                                             implicit(points - offset, shape))[0] / 2e-6))
                expected = np.array(derivative); expected /= np.linalg.norm(expected)
                np.testing.assert_allclose(surface_normal(points, shape)[0], expected, atol=1e-6)

    def test_actual_ellipsoid_is_lp_two_not_lp_one(self):
        self.assertTrue(ProxyShape(np.ones(3), 2.).is_ellipsoid)
        self.assertFalse(ProxyShape(np.ones(3), 1.).is_ellipsoid)

    def test_surface_points_satisfy_implicit_for_each_allowed_shape(self):
        for exponent in (1., 2., 4., 8.):
            shape = ProxyShape(np.array([0.4, 0.8, 1.2]), exponent)
            points = surface_point(grid_directions(12, 12), shape)
            np.testing.assert_allclose(implicit(points, shape), np.zeros((12, 12)), atol=1e-12)

    def test_proxy_grid_rejects_negative_zero_and_excessive_dimensions(self):
        for count in (-1, 0, 1, 100000):
            with self.subTest(count=count), self.assertRaises(ValueError):
                build_proxy(count, 8)


class ReceiptSafetyTest(unittest.TestCase):
    def test_valid_color_measurement_preserves_locked_area_thresholds(self):
        receipt = runner.judge(stages(), "color", self_checks={"all_passed": True}, provenance_ok=True)
        self.assertEqual(receipt["conclusion"], "GATE_PASS_C_VIABLE")
        self.assertEqual(receipt["thresholds"]["pass"], 0.10)
        self.assertEqual(receipt["thresholds"]["conditionally_pass"], 0.25)

    def test_missing_self_checks_cannot_emit_pass(self):
        self.assertEqual(runner.judge(stages(), "color")["conclusion"], "GATE_INVALID_MEASUREMENT")

    def test_animated_input_is_not_silently_reduced_to_first_frame(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "animated.gif"
            first = Image.new("RGB", (30, 30), "red")
            first.save(path, save_all=True, append_images=[Image.new("RGB", (30, 30), "blue")])
            with self.assertRaisesRegex(ValueError, "(单帧|动画)"):
                runner.load_input(path)

    def test_input_file_limit_is_checked_before_read_or_decode(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "large.png"
            path.write_bytes(b"test")
            with patch.object(Path, "stat") as stat, patch.object(Path, "read_bytes", side_effect=AssertionError("read")):
                stat.return_value.st_size = 32 * 1024 * 1024 + 1
                with self.assertRaisesRegex(ValueError, "32 MiB"):
                    runner.load_input(path)

    def test_failed_hard_check_cannot_emit_pass_or_progress_action(self):
        receipt = runner.judge(stages(), "color", self_checks={"all_passed": False}, provenance_ok=True)
        self.assertEqual(receipt["conclusion"], "GATE_INVALID_MEASUREMENT")
        self.assertNotIn("进阶段", receipt["action"])

    def test_failed_provenance_invalidates_measurement(self):
        receipt = runner.judge(stages(), "color", self_checks={"all_passed": True}, provenance_ok=False)
        self.assertEqual(receipt["conclusion"], "GATE_INVALID_MEASUREMENT")

    def test_line_art_area_score_never_claims_completed_topology_gate(self):
        receipt = runner.judge(stages(), "line_art", self_checks={"all_passed": True}, provenance_ok=True)
        self.assertEqual(receipt["conclusion"], "GATE_INCOMPLETE_LINE_ART_METRICS")
        self.assertEqual(receipt["area_only_conclusion"], "GATE_PASS_C_VIABLE")

    def test_nan_measurement_cannot_emit_success(self):
        broken = stages(); broken["B_patchmatch"][0]["hole_ratio"] = float("nan")
        with self.assertRaises(ValueError):
            runner.judge(broken, "color")

    def test_unknown_profile_is_rejected(self):
        with self.assertRaises(ValueError):
            runner.judge(stages(), "other")

    def test_existing_output_is_not_overwritten_on_validation_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "receipt"; out.mkdir()
            original = b"old-probe-evidence"
            (out / "input.png").write_bytes(original)
            with patch.object(sys, "argv", ["run_probe.py", "--out", str(out), "--head-box", "0,0,1,1"]):
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises((SystemExit, ValueError, FileExistsError)):
                    runner.main()
            self.assertEqual((out / "input.png").read_bytes(), original)

    def test_cli_rejects_resource_parameter_before_loading_or_writing(self):
        invalid = [("--resolution", "0"), ("--resolution", "100000"), ("--grid", "0"),
                   ("--texture", "-1"), ("--supersample", "0"), ("--supersample", "1000"),
                   ("--dilate", "-1"), ("--proxy-exponent", "nan"), ("--proxy-cover", "inf")]
        with tempfile.TemporaryDirectory() as directory:
            for index, (flag, value) in enumerate(invalid):
                with self.subTest(flag=flag, value=value):
                    out = Path(directory) / f"run-{index}"
                    with patch.object(sys, "argv", ["run_probe.py", "--out", str(out), flag, value]), \
                         patch.object(runner, "load_input", side_effect=AssertionError("loaded invalid input")), \
                         contextlib.redirect_stderr(io.StringIO()):
                        with self.assertRaises(SystemExit):
                            runner.main()
                    self.assertFalse(out.exists())

    def test_fully_transparent_image_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "empty.png"
            Image.new("RGBA", (40, 30), (255, 255, 255, 0)).save(path)
            with self.assertRaisesRegex(ValueError, "(透明|角色|foreground)"):
                runner.load_input(path)


if __name__ == "__main__":
    unittest.main()
