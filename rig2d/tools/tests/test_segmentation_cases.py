"""Eight contract checks for local case preparation; no network or model."""

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from make_part_fixture import generate as make_fixture
from make_segmentation_cases import generate


class SegmentationCasesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix="rig2d-segmentation-tests-")
        cls.root = Path(cls.workspace.name)
        cls.fixture = cls.root / "fixture"
        make_fixture(cls.fixture)
        spec = importlib.util.find_spec("skimage")
        cls.astronaut = Path(spec.origin).parent / "data" / "astronaut.png"

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def case_set(self):
        directory = tempfile.TemporaryDirectory(dir=self.root, prefix="out-")
        self.addCleanup(directory.cleanup)
        out = Path(directory.name) / "cases"
        metadata = generate(out, self.fixture, self.astronaut)
        self.assertEqual(metadata, json.loads((out / "metadata.json").read_text("utf-8")))
        return out, metadata

    def read_mask(self, case, relative):
        raw = (case / relative).read_bytes()
        self.assertEqual(512 * 512, len(raw))
        self.assertLessEqual(set(raw), {0, 1})
        return np.frombuffer(raw, dtype=np.uint8).reshape(512, 512)

    def read_image(self, path, mode=None):
        with Image.open(path) as image:
            return np.array(image.convert(mode) if mode else image)

    def test_colored_nearest_scale_alpha_composite_and_original_preservation(self):
        out, metadata = self.case_set()
        case = out / "colored_oc"
        self.assertEqual((512, 512, 3), self.read_image(case / "image.png").shape)
        for original, saved in (("source.argb", "source.argb"), ("preview.png", "source.png")):
            self.assertEqual((self.fixture / original).read_bytes(), (case / saved).read_bytes())
        source = self.read_image(self.fixture / "preview.png", "RGBA")
        expected = np.repeat(np.repeat(source, 2, axis=0), 2, axis=1)
        subject = np.frombuffer((case / "subject.rgba").read_bytes(), np.uint8).reshape(512, 512, 4)
        np.testing.assert_array_equal(expected, subject)
        np.testing.assert_array_equal(expected[:, :, 3] > 0, self.read_mask(case, "foreground.mask"))
        self.assertEqual(18304 * 4, int(self.read_mask(case, "foreground.mask").sum()))
        self.assertEqual(163 * 4, int(((subject[:, :, 3] > 0) & (subject[:, :, 3] < 255)).sum()))
        background = self.read_image(case / "background.png").astype(np.uint32)
        alpha = subject[:, :, 3:4].astype(np.uint32)
        blended = (subject[:, :, :3].astype(np.uint32) * alpha + background * (255 - alpha) + 127) // 255
        np.testing.assert_array_equal(blended.astype(np.uint8), self.read_image(case / "image.png"))
        self.assertEqual([2, 2], metadata["cases"]["colored_oc"]["transform"]["scale_xy"])
        self.assertEqual([0, 0], metadata["cases"]["colored_oc"]["transform"]["offset_xy"])

    def test_line_truth_is_visible_grayscale_ink_not_filled_subject(self):
        out, metadata = self.case_set()
        case = out / "line_oc"
        subject = np.frombuffer((case / "subject.rgba").read_bytes(), np.uint8).reshape(512, 512, 4)
        np.testing.assert_array_equal(subject[:, :, 0], subject[:, :, 1])
        np.testing.assert_array_equal(subject[:, :, 1], subject[:, :, 2])
        ink = self.read_mask(case, "foreground.mask")
        colored = self.read_mask(out / "colored_oc", "foreground.mask")
        np.testing.assert_array_equal(ink, subject[:, :, 3] > 0)
        self.assertTrue(np.all(ink <= colored))
        self.assertGreater(int(ink.sum()), 1000)
        self.assertLess(int(ink.sum()), int(colored.sum()) // 2)
        # The original alpha=1 unclassified ornament survives the line transform.
        self.assertEqual(1, int(subject[448, 168, 3]))
        truth = metadata["cases"]["line_oc"]["ground_truth"]
        self.assertIn("visible ink", truth["definition"])
        union = np.zeros((512, 512), np.uint8)
        for item in truth["instances"]:
            mask = self.read_mask(case, item["mask"])
            self.assertGreater(int(mask.sum()), 0)
            self.assertFalse(np.any(union & mask))
            union |= mask
        self.assertEqual(4, int((ink - union).sum()))

    def test_xy_prompts_are_bounded_oracle_anchors_and_never_include_masks(self):
        out, metadata = self.case_set()
        for name in ("colored_oc", "line_oc"):
            case = out / name
            entry = metadata["cases"][name]
            masks = {item["id"]: self.read_mask(case, item["mask"])
                     for item in entry["ground_truth"]["instances"]}
            masks["subject"] = self.read_mask(case, "foreground.mask")
            quick = json.loads((case / "prompts.json").read_text("utf-8"))
            full = json.loads((case / "prompts.full.json").read_text("utf-8"))
            self.assertEqual(1, full["schema"])
            self.assertEqual((512, 512), (full["width"], full["height"]))
            self.assertLessEqual(len(quick["regions"]), 4)
            self.assertLess(len(full["regions"]), 63)
            self.assertEqual(12, sum(r["id"].startswith("wing_") for r in full["regions"]))
            self.assertIn("oracle", entry["prompt_provenance"])
            for region in full["regions"]:
                mask = masks[region["id"]]
                self.assertNotIn("mask", region)
                for x, y, label in region["points"]:
                    self.assertTrue(0 <= x < 512 and 0 <= y < 512)
                    self.assertEqual(label, int(mask[y, x]))
                ys, xs = np.where(mask)
                self.assertEqual([int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())], region["box"])
                self.assertTrue(any(point[2] == 1 for point in region["points"]))

    def test_astronaut_is_local_original_public_domain_qualitative_without_gt(self):
        out, metadata = self.case_set()
        case = out / "astronaut"
        entry = metadata["cases"]["astronaut"]
        self.assertEqual(self.astronaut.read_bytes(), (case / "image.png").read_bytes())
        self.assertEqual("qualitative_only_no_ground_truth", entry["evaluation"])
        self.assertIsNone(entry["ground_truth"])
        self.assertIn("public domain", entry["sample_license"]["name"])
        self.assertIn("skimage.data.astronaut", entry["sample_license"]["source_url"])
        self.assertFalse(list(case.rglob("*.mask")))
        self.assertFalse(entry["claims"]["model_inference"])
        self.assertFalse(entry["claims"]["user_media"])
        prompts = json.loads((case / "prompts.json").read_text("utf-8"))
        self.assertEqual(2, len(prompts["regions"]))

    def test_payload_hashes_and_repeated_generation_are_exact_and_inputs_unchanged(self):
        before = {p.relative_to(self.fixture).as_posix(): p.read_bytes()
                  for p in self.fixture.rglob("*") if p.is_file()}
        first, metadata = self.case_set()
        second, _ = self.case_set()
        paths = {p.relative_to(first).as_posix() for p in first.rglob("*") if p.is_file()}
        self.assertEqual(paths - {"metadata.json"}, set(metadata["files"]))
        for relative in paths:
            raw = (first / relative).read_bytes()
            self.assertEqual(raw, (second / relative).read_bytes())
            if relative != "metadata.json":
                self.assertEqual({"size": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}, metadata["files"][relative])
        self.assertEqual(before, {p.relative_to(self.fixture).as_posix(): p.read_bytes()
                                  for p in self.fixture.rglob("*") if p.is_file()})

    def test_existing_output_is_rejected_before_any_file_changes(self):
        existing = self.root / "exclusive"
        existing.mkdir(exist_ok=True)
        sentinel = existing / "sentinel.txt"
        sentinel.write_text("preserve", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            generate(existing, self.fixture, self.astronaut)
        self.assertEqual([sentinel], list(existing.iterdir()))
        self.assertEqual("preserve", sentinel.read_text("utf-8"))

    def test_invalid_fixture_payload_or_path_is_rejected_without_partial_output(self):
        original = json.loads((self.fixture / "metadata.json").read_text("utf-8"))
        mutations = ({"width": 1025}, {"source": "../outside.argb"}, {"height": 0},
                     {"ground_truth_instances": [42]}, {"files": []},
                     {"ground_truth_instances": [
                         {**item, "id": "subject" if item["id"] == "body" else item["id"]}
                         for item in original["ground_truth_instances"]]})
        for index, changes in enumerate(mutations):
            broken = self.root / f"bad-{index}"
            make_fixture(broken)
            (broken / "metadata.json").write_text(json.dumps({**original, **changes}), encoding="utf-8")
            out = self.root / f"rejected-{index}"
            with self.assertRaises(ValueError):
                generate(out, broken, self.astronaut)
            self.assertFalse(out.exists())
        invalid_meta = self.root / "invalid-meta"
        invalid_meta.mkdir()
        (invalid_meta / "metadata.json").write_text("[]", encoding="utf-8")
        out = self.root / "invalid-meta-rejected"
        with self.assertRaises(ValueError):
            generate(out, invalid_meta, self.astronaut)
        self.assertFalse(out.exists())
        budget = self.root / "budget"
        make_fixture(budget)
        too_many = {**original, "ground_truth_instances": [original["ground_truth_instances"][0]] * 62}
        (budget / "metadata.json").write_text(json.dumps(too_many), encoding="utf-8")
        out = self.root / "budget-rejected"
        with self.assertRaisesRegex(ValueError, "1..61"):
            generate(out, budget, self.astronaut)
        self.assertFalse(out.exists())
        corrupt = self.root / "corrupt"
        make_fixture(corrupt)
        invalid_mask = bytes([2]) * 65536
        (corrupt / "ground_truth" / "wing_12.mask").write_bytes(invalid_mask)
        corrupt_meta = json.loads((corrupt / "metadata.json").read_text("utf-8"))
        corrupt_meta["files"]["ground_truth/wing_12.mask"] = {
            "size": len(invalid_mask), "sha256": hashlib.sha256(invalid_mask).hexdigest()}
        (corrupt / "metadata.json").write_text(json.dumps(corrupt_meta), encoding="utf-8")
        out = self.root / "corrupt-rejected"
        with self.assertRaises(ValueError):
            generate(out, corrupt, self.astronaut)
        self.assertFalse(out.exists())
        # An arbitrary local photograph cannot inherit NASA's public-domain claim.
        foreign = self.root / "foreign.png"
        Image.fromarray(np.zeros((512, 512, 3), np.uint8)).save(foreign)
        out = self.root / "foreign-rejected"
        with self.assertRaises(ValueError):
            generate(out, self.fixture, foreign)
        self.assertFalse(out.exists())

    def test_cli_skips_absent_local_photo_and_rejects_reuse_cleanly(self):
        out = self.root / "cli"
        command = [sys.executable, str(Path(__file__).resolve().parents[1] / "make_segmentation_cases.py"),
                   "--fixture", str(self.fixture), "--out", str(out), "--astronaut", str(self.root / "absent.png")]
        first = subprocess.run(command, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, first.returncode, first.stderr)
        metadata = json.loads((out / "metadata.json").read_text("utf-8"))
        self.assertEqual(["colored_oc", "line_oc"], list(metadata["cases"]))
        self.assertEqual("local_astronaut_unavailable_no_download", metadata["skipped"]["astronaut"])
        self.assertFalse((out / "astronaut").exists())
        saved = (out / "metadata.json").read_bytes()
        second = subprocess.run(command, capture_output=True, text=True, timeout=30)
        self.assertEqual(2, second.returncode)
        self.assertNotIn("Traceback", second.stderr)
        self.assertEqual(saved, (out / "metadata.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
