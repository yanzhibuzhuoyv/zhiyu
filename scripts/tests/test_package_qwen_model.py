"""Small byte-exact fixtures; no original weights or model execution."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import package_qwen_model as pack


def sha(data):
    return hashlib.sha256(data).hexdigest()


class OfflineQwenPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.output = self.root / "student.zyqwen"
        template = Path(__file__).resolve().parents[2] / "artifacts/model-packs/qwen-edge/manifest.json"
        self.manifest = json.loads(template.read_bytes())
        self.payloads = [b"S" * (2 * 65536 + 37), b"adapter" * 19, bytes(range(256)), b"vae" * 83]
        for spec, data in zip(self.manifest["files"], self.payloads):
            spec["bytes"], spec["sha256"] = len(data), sha(data)
            (self.source / spec["path"]).write_bytes(data)
        self.write_manifest()

    def write_manifest(self, manifest=None, raw=None):
        if manifest is not None:
            self.manifest = manifest
        self.raw = raw if raw is not None else json.dumps(self.manifest, indent=2).encode() + b"\n"
        (self.source / "manifest.json").write_bytes(self.raw)
        self.trusted = sha(self.raw)  # Test-only injected anchor, never accepted by the production CLI.

    def expected(self):
        return b"ZYQPACK1" + struct.pack(">I", len(self.raw)) + self.raw + b"".join(self.payloads)

    def create(self, **kwargs):
        return pack.package_model(self.source, self.output, trusted_sha=self.trusted, **kwargs)

    def verify(self, **kwargs):
        return pack.verify_package(self.output, trusted_sha=self.trusted, **kwargs)

    def assert_no_temps(self):
        self.assertEqual([], list(self.root.glob(".student.zyqwen.*.tmp")))

    def test_exact_header_manifest_payload_order_and_receipt(self):
        before = [(path, path.read_bytes()) for path in self.source.iterdir()]
        receipt = self.create()
        self.assertEqual(self.expected(), self.output.read_bytes())
        self.assertEqual(self.trusted, receipt["manifest_sha256"])
        self.assertEqual(sha(self.expected()), receipt["package_sha256"])
        self.assertEqual(len(self.expected()), receipt["package_bytes"])
        self.assertEqual(sum(map(len, self.payloads)), receipt["payload_bytes"])
        self.assertEqual(self.manifest["files"], receipt["files"])
        self.assertTrue(receipt["verified"])
        self.assertEqual(receipt, self.verify())
        self.assertEqual(before, [(path, path.read_bytes()) for path in self.source.iterdir()])
        self.assert_no_temps()

    def test_default_anchor_is_fixed_and_rejects_self_signed_source(self):
        self.assertEqual("9232afcbed6d188a9bdc58c0b8841b35bb12773aaee9fc745dc8027641c656e6", pack.MANIFEST_SHA)
        with self.assertRaises(ValueError):
            pack.package_model(self.source, self.output)
        self.assertFalse(self.output.exists())
        self.assert_no_temps()

    def test_production_anchor_matches_release_manifest_and_kotlin(self):
        repo = Path(__file__).resolve().parents[2]
        self.assertEqual(pack.MANIFEST_SHA, sha((repo / "artifacts/model-packs/qwen-edge/manifest.json").read_bytes()))
        self.assertIn(pack.MANIFEST_SHA, (repo / "android/app/src/main/java/com/zhiyu/app/data/local/qwen/QwenPackStore.kt").read_text(encoding="utf-8"))

    def test_missing_truncated_extra_or_corrupt_payload_never_publishes(self):
        path = self.source / self.manifest["files"][0]["path"]
        for content in (None, self.payloads[0][:-1], self.payloads[0] + b"x", b"X" + self.payloads[0][1:]):
            with self.subTest(content=None if content is None else len(content)):
                path.unlink(missing_ok=True)
                if content is not None:
                    path.write_bytes(content)
                with self.assertRaises((ValueError, FileNotFoundError)):
                    self.create()
                self.assertFalse(self.output.exists())
                self.assert_no_temps()

    def test_bad_manifest_digest_utf8_json_or_duplicate_fields_rejected(self):
        fixtures = [b"\xff", b"{", b'{"files":[],"files":[]}', b'{}', b'{"bad":NaN}']
        for raw in fixtures:
            with self.subTest(raw=raw):
                self.write_manifest(raw=raw)
                with self.assertRaises(ValueError):
                    self.create()
                self.assertFalse(self.output.exists())
        with self.assertRaises(ValueError):
            pack.package_model(self.source, self.output, trusted_sha="0" * 64)

    def test_manifest_size_zero_and_over_limit_rejected_before_output(self):
        for raw in (b"", b" " * 65537):
            with self.subTest(length=len(raw)):
                self.write_manifest(raw=raw)
                with self.assertRaises(ValueError):
                    self.create()
                self.assertFalse(self.output.exists())

    def test_path_traversal_absolute_windows_and_empty_segments_rejected(self):
        original = copy.deepcopy(self.manifest)
        for path in ("../outside", "/tmp/file", "C:/private", "a\\b", "a//b", "a/./b", "a/../b", ""):
            with self.subTest(path=path):
                malformed = copy.deepcopy(original)
                malformed["files"][0]["path"] = path
                self.write_manifest(malformed)
                with self.assertRaises(ValueError):
                    self.create()
                self.assertFalse(self.output.exists())

    def test_duplicate_roles_and_casefold_paths_invalid_sizes_digests_rejected(self):
        changes = [(0, "bytes", True), (0, "bytes", 0), (0, "bytes", -1), (0, "bytes", 1.5),
                   (0, "bytes", 256 * 1024**3 + 1), (0, "sha256", "A" * 64),
                   (1, "role", "student"), (1, "path", self.manifest["files"][0]["path"].upper())]
        original = copy.deepcopy(self.manifest)
        for index, key, value in changes:
            with self.subTest(key=key, value=value):
                malformed = copy.deepcopy(original)
                malformed["files"][index][key] = value
                self.write_manifest(malformed)
                with self.assertRaises(ValueError):
                    self.create()
                self.assertFalse(self.output.exists())

    def test_four_roles_required_and_unknown_fields_rejected(self):
        original = copy.deepcopy(self.manifest)
        for change in (lambda m: m["files"].pop(), lambda m: m.update(extra=True),
                       lambda m: m["files"][0].update(extra=True)):
            malformed = copy.deepcopy(original)
            change(malformed)
            self.write_manifest(malformed)
            with self.assertRaises(ValueError):
                self.create()

    def test_nested_safe_paths_are_supported_without_reordering(self):
        spec = self.manifest["files"][0]
        path = self.source / spec["path"]
        spec["path"] = "models/student.bin"
        (self.source / "models").mkdir()
        path.rename(self.source / spec["path"])
        self.write_manifest()
        self.create()
        self.assertEqual(self.expected(), self.output.read_bytes())

    def test_output_inside_source_and_wrong_extension_rejected(self):
        for target in (self.source / "new.zyqwen", self.root / "bad.zip", self.source / "manifest.json"):
            with self.subTest(target=target):
                with self.assertRaises(ValueError):
                    pack.package_model(self.source, target, trusted_sha=self.trusted)
                if target.name != "manifest.json":
                    self.assertFalse(target.exists())

    def test_existing_identical_output_is_verified_and_not_replaced(self):
        self.output.write_bytes(self.expected())
        before = self.output.stat()
        receipt = self.create()
        after = self.output.stat()
        self.assertEqual((before.st_ino, before.st_mtime_ns), (after.st_ino, after.st_mtime_ns))
        self.assertEqual(sha(self.expected()), receipt["package_sha256"])
        self.assert_no_temps()

    def test_existing_different_file_is_never_deleted_or_overwritten(self):
        self.output.write_bytes(b"user data")
        with self.assertRaises(FileExistsError):
            self.create()
        self.assertEqual(b"user data", self.output.read_bytes())
        self.assert_no_temps()

    def test_cancel_during_copy_removes_only_owned_temp(self):
        other = self.root / ".student.zyqwen.user.tmp"
        other.write_bytes(b"keep")
        calls = 0
        def cancel():
            nonlocal calls
            calls += 1
            if calls == 5:
                raise KeyboardInterrupt("cancel fixture")
        with self.assertRaises(KeyboardInterrupt):
            self.create(check_cancelled=cancel)
        self.assertFalse(self.output.exists())
        self.assertEqual([other], list(self.root.glob(".student.zyqwen.*.tmp")))
        self.assertEqual(b"keep", other.read_bytes())

    def test_fsync_failure_preserves_existing_user_file_and_cleans_temp(self):
        other = self.root / "keep.txt"
        other.write_bytes(b"keep")
        with patch.object(pack.os, "fsync", side_effect=OSError("disk error")):
            with self.assertRaises(OSError):
                self.create()
        self.assertFalse(self.output.exists())
        self.assertEqual(b"keep", other.read_bytes())
        self.assert_no_temps()

    def test_output_created_during_copy_cannot_be_overwritten(self):
        def competing_writer():
            temps = list(self.root.glob(".student.zyqwen.*.tmp"))
            if temps and temps[0].stat().st_size == len(self.expected()) and not self.output.exists():
                self.output.write_bytes(b"concurrent user file")
        with self.assertRaises(FileExistsError):
            self.create(check_cancelled=competing_writer)
        self.assertEqual(b"concurrent user file", self.output.read_bytes())
        self.assert_no_temps()

    def test_verify_rejects_bad_header_length_truncation_tamper_and_trailing_data(self):
        correct = self.expected()
        fixtures = [b"", b"WRONG___" + correct[8:], correct[:9],
                    correct[:8] + struct.pack(">I", 0) + correct[12:],
                    correct[:8] + struct.pack(">I", 65537) + correct[12:], correct[:-1],
                    correct[:-1] + bytes([correct[-1] ^ 1]), correct + b"extra",
                    correct[:12] + bytes([correct[12] ^ 1]) + correct[13:]]
        for raw in fixtures:
            with self.subTest(length=len(raw)):
                self.output.write_bytes(raw)
                with self.assertRaises(ValueError):
                    self.verify()
                self.assertEqual(raw, self.output.read_bytes())

    def test_verify_cancellation_does_not_change_package(self):
        self.output.write_bytes(self.expected())
        def cancel():
            raise KeyboardInterrupt("stop verify")
        with self.assertRaises(KeyboardInterrupt):
            self.verify(check_cancelled=cancel)
        self.assertEqual(self.expected(), self.output.read_bytes())

    def test_stream_reads_are_bounded_to_64k_and_fsync_called(self):
        original = pack.os.read
        sizes = []
        def bounded_read(fd, count):
            sizes.append(count)
            self.assertGreater(count, 0)
            self.assertLessEqual(count, 65536)
            return original(fd, count)
        with patch.object(pack.os, "read", side_effect=bounded_read), patch.object(pack.os, "fsync", wraps=os.fsync) as fsync:
            self.create()
            self.verify()
            self.assertTrue(fsync.called)
        self.assertTrue(sizes)

    def test_symlink_sources_manifest_and_output_rejected(self):
        target = self.root / "outside.bin"
        target.write_bytes(self.payloads[0])
        probe = self.root / "link-probe"
        try:
            probe.symlink_to(target)
        except (OSError, NotImplementedError):
            # Windows may deny creating file symlinks. Exercise the same no-follow
            # metadata boundary with injected lstat rather than skip a protection.
            original_lstat = os.lstat
            paths = [self.source / self.manifest["files"][0]["path"],
                     self.source / "manifest.json", self.output]
            for linked in paths:
                def linked_lstat(path, *args, **kwargs):
                    if Path(path).absolute() == linked.absolute():
                        fields = list(original_lstat(target))
                        fields[0] = stat.S_IFLNK | 0o777
                        return os.stat_result(fields)
                    return original_lstat(path, *args, **kwargs)
                with self.subTest(linked=linked), patch.object(pack.os, "lstat", side_effect=linked_lstat):
                    with self.assertRaises(ValueError):
                        self.create()
                self.assertFalse(self.output.exists())
            self.assertEqual(self.payloads[0], target.read_bytes())
            return
        probe.unlink()
        source_file = self.source / self.manifest["files"][0]["path"]
        source_file.unlink()
        source_file.symlink_to(target)
        with self.assertRaises(ValueError):
            self.create()
        source_file.unlink()
        source_file.write_bytes(self.payloads[0])
        manifest = self.source / "manifest.json"
        manifest_target = self.root / "outside-manifest.json"
        manifest_target.write_bytes(self.raw)
        manifest.unlink()
        manifest.symlink_to(manifest_target)
        with self.assertRaises(ValueError):
            self.create()
        manifest.unlink()
        manifest.write_bytes(self.raw)
        self.output.symlink_to(target)
        with self.assertRaises(ValueError):
            self.create()
        self.assertEqual(self.payloads[0], target.read_bytes())

    def test_cli_never_accepts_test_trust_anchor_override(self):
        with patch("sys.stderr", new_callable=io.StringIO):
            with self.assertRaises(SystemExit) as failure:
                pack.main(["--source", str(self.source), "--output", str(self.output), "--trusted-sha", self.trusted])
        self.assertEqual(2, failure.exception.code)
        self.assertFalse(self.output.exists())

    def test_cli_verify_and_inspect_share_full_stream_verification(self):
        with patch.object(pack, "verify_package", return_value={"verified": True}) as verify:
            with patch("sys.stdout", new_callable=io.StringIO) as stdout:
                self.assertEqual(0, pack.main(["--inspect", str(self.output)]))
                self.assertEqual({"verified": True}, json.loads(stdout.getvalue()))
            verify.assert_called_once_with(self.output)
        with patch.object(pack, "verify_package", return_value={"verified": True}) as verify:
            with patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(0, pack.main(["--verify", str(self.output)]))
            verify.assert_called_once_with(self.output)


if __name__ == "__main__":
    unittest.main()
