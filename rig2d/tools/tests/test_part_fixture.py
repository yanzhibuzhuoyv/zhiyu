"""Contract tests for the local synthetic fixture; no model or network is used."""

import hashlib
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from make_part_fixture import generate


CLAIM = "SYNTHETIC_NOT_MODEL_NOT_REAL_OC"
KINDS = {
    "back_hair", "body", "arm_l", "arm_r", "neck", "face", "ear_l", "ear_r",
    "front_hair", "brow_l", "brow_r", "eye_white_l", "eye_white_r", "iris_l",
    "iris_r", "lid_l", "lid_r", "mouth", "accessory",
}
PIXELS = 256 * 256


class PartFixtureTest(unittest.TestCase):
    def make_fixture(self):
        directory = tempfile.TemporaryDirectory(prefix="rig2d-part-fixture-test-")
        self.addCleanup(directory.cleanup)
        out = Path(directory.name) / "fixture"
        returned = generate(out)
        metadata = json.loads((out / "metadata.json").read_text(encoding="utf-8"))
        self.assertEqual(metadata, returned)
        return out, metadata

    def mask(self, out, path):
        data = (out / path).read_bytes()
        self.assertEqual(PIXELS, len(data))
        self.assertLessEqual(set(data), {0, 1})
        return data

    def test_raster_is_full_straight_argb_and_preview_matches_exact_pixels(self):
        out, metadata = self.make_fixture()
        self.assertEqual((256, 256), (metadata["width"], metadata["height"]))
        self.assertEqual("little-endian-uint32-0xAARRGGBB", metadata["source_encoding"])
        self.assertEqual("straight", metadata["alpha_encoding"])
        raw = (out / "source.argb").read_bytes()
        self.assertEqual(PIXELS * 4, len(raw))
        argb = struct.unpack("<" + "I" * PIXELS, raw)
        foreground = self.mask(out, "foreground.mask")
        self.assertEqual(bytes(int(pixel >> 24 != 0) for pixel in argb), foreground)
        self.assertTrue(any(0 < pixel >> 24 < 255 for pixel in argb))
        self.assertIn(0, argb)
        rgba = self.read_png_rgba(out / "preview.png")
        expected = bytes(channel for pixel in argb for channel in
                         ((pixel >> 16) & 255, (pixel >> 8) & 255, pixel & 255, pixel >> 24))
        self.assertEqual(expected, rgba)

    def read_png_rgba(self, path):
        png = path.read_bytes()
        self.assertEqual(b"\x89PNG\r\n\x1a\n", png[:8])
        cursor, compressed = 8, bytearray()
        while cursor < len(png):
            length = struct.unpack(">I", png[cursor:cursor + 4])[0]
            kind, data = png[cursor + 4:cursor + 8], png[cursor + 8:cursor + 8 + length]
            if kind == b"IHDR":
                self.assertEqual((256, 256, 8, 6, 0, 0, 0), struct.unpack(">IIBBBBB", data))
            if kind == b"IDAT":
                compressed.extend(data)
            cursor += length + 12
        scanlines = zlib.decompress(compressed)
        self.assertEqual(256 * (1 + 256 * 4), len(scanlines))
        self.assertTrue(all(scanlines[row * 1025] == 0 for row in range(256)))
        return b"".join(scanlines[row * 1025 + 1:(row + 1) * 1025] for row in range(256))

    def test_every_payload_has_a_verified_size_and_sha256(self):
        out, metadata = self.make_fixture()
        self.assertEqual(CLAIM, metadata["claim"])
        actual = {file.relative_to(out).as_posix() for file in out.rglob("*") if file.is_file()}
        self.assertEqual(actual - {"metadata.json"}, set(metadata["files"]))
        for relative, expected in metadata["files"].items():
            with self.subTest(path=relative):
                self.assertFalse(Path(relative).is_absolute())
                self.assertNotIn("..", Path(relative).parts)
                payload = (out / relative).read_bytes()
                self.assertEqual(len(payload), expected["size"])
                self.assertEqual(hashlib.sha256(payload).hexdigest(), expected["sha256"])

    def test_twelve_stable_wings_have_visible_independent_ground_truth(self):
        out, metadata = self.make_fixture()
        truth = metadata["ground_truth_instances"]
        wings = [item for item in truth if item["id"].startswith("wing_")]
        self.assertEqual([f"wing_{number:02d}" for number in range(1, 13)],
                         [item["id"] for item in wings])
        argb = struct.unpack("<" + "I" * PIXELS, (out / "source.argb").read_bytes())
        seen = bytearray(PIXELS)
        colors = set()
        for item in wings:
            mask = self.mask(out, item["mask"])
            self.assertEqual("accessory", item["kind"])
            self.assertGreater(sum(mask), 20)
            self.assertTrue(any(mask[p] and not seen[p] for p in range(PIXELS)))
            self.assertTrue(all(argb[p] >> 24 for p in range(PIXELS) if mask[p]))
            colors.add(argb[mask.index(1)] & 0xFFFFFF)
            seen = bytes(int(a or b) for a, b in zip(seen, mask))
        self.assertEqual(12, len(colors))
        self.assertEqual(1, sum(item["id"] == "weapon_01" for item in truth))

    def test_subject_omissions_are_fully_restored_by_protected_foreground(self):
        out, metadata = self.make_fixture()
        subject = self.mask(out, "subject.mask")
        protected = self.mask(out, "protected.mask")
        foreground = self.mask(out, "foreground.mask")
        self.assertNotEqual(subject, foreground)
        self.assertEqual(foreground, bytes(int(a or b) for a, b in zip(subject, protected)))
        self.assertEqual(["wing_12"], metadata["omitted_candidate_ids"])
        self.assertNotIn("wing_12", {item["id"] for item in metadata["instances"]})
        truth = {item["id"]: self.mask(out, item["mask"])
                 for item in metadata["ground_truth_instances"]}
        self.assertTrue(all(not subject[p] and protected[p] for p in range(PIXELS)
                            if truth["wing_12"][p]))
        self.assertTrue(any(truth["weapon_01"][p] and not subject[p] and protected[p]
                            for p in range(PIXELS)))

    def test_candidates_use_existing_kinds_and_exercise_face_body_conflicts(self):
        out, metadata = self.make_fixture()
        instances = metadata["instances"]
        self.assertEqual(len(instances), len({item["id"] for item in instances}))
        self.assertLessEqual(sum(item["kind"] == "accessory" for item in instances), 64)
        masks = {item["id"]: self.mask(out, item["mask"]) for item in instances}
        foreground = self.mask(out, "foreground.mask")
        for item in instances:
            self.assertIn(item["kind"], KINDS)
            self.assertGreaterEqual(item["confidence"], 0)
            self.assertLessEqual(item["confidence"], 1)
            self.assertGreater(sum(masks[item["id"]]), 0)
        wings = [mask for name, mask in masks.items() if name.startswith("wing_")]
        self.assertEqual(11, len(wings))
        for role in ("face", "body"):
            self.assertTrue(any(any(a and b and fg for a, b, fg in
                                    zip(wing, masks[role], foreground)) for wing in wings))

    def test_generation_is_byte_for_byte_deterministic(self):
        first, _ = self.make_fixture()
        second, _ = self.make_fixture()
        for path in first.rglob("*"):
            if path.is_file():
                self.assertEqual(path.read_bytes(), (second / path.relative_to(first)).read_bytes())

    def test_existing_output_directory_is_rejected_without_overwriting(self):
        directory = tempfile.TemporaryDirectory(prefix="rig2d-part-fixture-exclusive-")
        self.addCleanup(directory.cleanup)
        out = Path(directory.name) / "existing"
        out.mkdir()
        sentinel = out / "sentinel.txt"
        sentinel.write_text("preserve me", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            generate(out)
        self.assertEqual("preserve me", sentinel.read_text(encoding="utf-8"))
        self.assertEqual([sentinel], list(out.iterdir()))

    def test_cli_prints_claim_and_rejects_reuse_without_a_traceback(self):
        directory = tempfile.TemporaryDirectory(prefix="rig2d-part-fixture-cli-")
        self.addCleanup(directory.cleanup)
        out = Path(directory.name) / "new"
        command = [sys.executable, str(Path(__file__).resolve().parents[1] /
                                      "make_part_fixture.py"), "--out", str(out)]
        first = subprocess.run(command, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, first.returncode, first.stderr)
        self.assertIn(CLAIM, first.stdout)
        original = (out / "metadata.json").read_bytes()
        second = subprocess.run(command, capture_output=True, text=True, timeout=20)
        self.assertEqual(2, second.returncode)
        self.assertNotIn("Traceback", second.stderr)
        self.assertEqual(original, (out / "metadata.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
