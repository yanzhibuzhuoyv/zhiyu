import copy
import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from PIL import Image

from studio.storage import Storage
from studio.tests.test_assets import encoded, wide_sparse_attachments


def source():
    stream = io.BytesIO()
    Image.new("RGBA", (48, 64), (160, 150, 140, 255)).save(stream, format="PNG")
    return stream.getvalue()


def rewritten(package, changes):
    original = zipfile.ZipFile(io.BytesIO(package))
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as output:
        for name in original.namelist():
            output.writestr(name, changes.pop(name, original.read(name)))
        for name, content in changes.items():
            output.writestr(name, content)
    return stream.getvalue()


def resigned(package, changes):
    original = zipfile.ZipFile(io.BytesIO(package))
    files = {name: original.read(name) for name in original.namelist() if name != "manifest.json"}
    files.update(changes)
    manifest = {"format": "rig2d-planar-package-1",
                "files": {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}}
    return rewritten(package, {**changes, "manifest.json": json.dumps(manifest).encode()})


class StorageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.storage = Storage(Path(self.temp.name))

    def test_import_build_export_restore_and_reopen(self):
        first = self.storage.create(source(), "Lover")
        value = copy.deepcopy(first["definition"])
        value["anchors"]["head"][0] += 1
        second = self.storage.build(first["id"], first["revision"], value)
        self.assertEqual(second["revision"], first["revision"] + 1)
        package = self.storage.export(first["id"])
        restored = self.storage.restore(package)
        self.assertEqual(restored["asset"], second["asset"])
        self.assertEqual(Storage(Path(self.temp.name)).get(restored["id"])["definition"], value)

    def test_stale_revision_and_failed_build_preserve_active(self):
        first = self.storage.create(source(), "Lover")
        updated = self.storage.build(first["id"], first["revision"], first["definition"])
        with self.assertRaises(ValueError):
            self.storage.build(first["id"], first["revision"], first["definition"])
        bad = copy.deepcopy(updated["definition"])
        bad["anchors"]["head"] = [-1, -1]
        with self.assertRaises(ValueError):
            self.storage.build(first["id"], updated["revision"], bad)
        self.assertEqual(self.storage.get(first["id"]), updated)

    def test_concurrent_compare_and_swap_has_single_winner(self):
        first = self.storage.create(source(), "Lover")
        def attempt(_):
            try:
                self.storage.build(first["id"], first["revision"], first["definition"])
                return True
            except ValueError:
                return False
        with ThreadPoolExecutor(max_workers=2) as pool:
            self.assertEqual(list(pool.map(attempt, range(2))).count(True), 1)

    def test_file_only_serves_active_manifest_and_safe_paths(self):
        first = self.storage.create(source(), "Lover")
        path = self.storage.file(first["id"], "source.png")
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), first["asset"]["sourceSha"])
        for name in ("../active.json", "C:/Windows/test", "definition.json", "textures/missing.png"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.storage.file(first["id"], name)

    def test_traversal_and_extra_package_files_are_rejected(self):
        first = self.storage.create(source(), "Lover")
        package = self.storage.export(first["id"])
        for name in ("../escape", "evil.py"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.storage.restore(rewritten(package, {name: b"bad"}))
        self.assertEqual(self.storage.get(first["id"]), first)

    def test_duplicate_names_and_crc_corruption_are_rejected(self):
        first = self.storage.create(source(), "Lover")
        package = self.storage.export(first["id"])
        stream = io.BytesIO(package)
        with zipfile.ZipFile(stream, "a") as archive:
            archive.writestr("source.png", source())
        with self.assertRaises(ValueError):
            self.storage.restore(stream.getvalue())
        corrupted = bytearray(package)
        corrupted[70] ^= 0xff
        with self.assertRaises(ValueError):
            self.storage.restore(bytes(corrupted))

    def test_bad_source_texture_hash_or_mesh_cannot_replace_good_active(self):
        first = self.storage.create(source(), "Lover")
        package = self.storage.export(first["id"])
        archive = zipfile.ZipFile(io.BytesIO(package))
        asset = json.loads(archive.read("asset.json"))
        asset["layers"][0]["vertices"][0] = float("nan")
        changes = {"asset.json": json.dumps(asset).encode()}
        with self.assertRaises(ValueError):
            self.storage.restore(rewritten(package, changes))
        with self.assertRaises(ValueError):
            self.storage.restore(rewritten(package, {"source.png": b"broken"}))
        self.assertEqual(self.storage.get(first["id"]), first)

    def test_export_contains_data_not_scripts_and_verified_sha_manifest(self):
        first = self.storage.create(source(), "Lover")
        archive = zipfile.ZipFile(io.BytesIO(self.storage.export(first["id"])))
        manifest = json.loads(archive.read("manifest.json"))
        self.assertEqual(set(manifest["files"]), set(archive.namelist()) - {"manifest.json"})
        for name, digest in manifest["files"].items():
            self.assertEqual(digest, hashlib.sha256(archive.read(name)).hexdigest())

    def test_invalid_ids_names_packages_and_revisions_are_explicit_errors(self):
        with self.assertRaises(ValueError):
            self.storage.create(source(), "x" * 200)
        for ident in ("..", "../outside", "C:\\escape"):
            with self.assertRaises(ValueError):
                self.storage.get(ident)
        first = self.storage.create(source(), "Good")
        for revision in (True, -1, "1"):
            with self.assertRaises(ValueError):
                self.storage.build(first["id"], revision, first["definition"])
        with self.assertRaises(ValueError):
            self.storage.restore(b"not zip")

    def test_failed_active_pointer_commit_is_retriable_with_old_active_intact(self):
        import os
        first = self.storage.create(source(), "Good")
        real_replace = os.replace
        def fail_pointer(old, new):
            if Path(new).name == "active.json":
                raise OSError("simulated disk failure")
            return real_replace(old, new)
        with patch("studio.storage.os.replace", side_effect=fail_pointer):
            with self.assertRaises(OSError):
                self.storage.build(first["id"], first["revision"], first["definition"])
        self.assertEqual(self.storage.get(first["id"]), first)
        after = self.storage.build(first["id"], first["revision"], first["definition"])
        self.assertEqual(after["revision"], first["revision"] + 1)

    def test_rehashed_bad_mesh_capability_and_texture_are_still_rejected(self):
        first = self.storage.create(source(), "Good")
        package = self.storage.export(first["id"])
        archive = zipfile.ZipFile(io.BytesIO(package))
        asset = json.loads(archive.read("asset.json"))
        for change in ("mesh", "capability"):
            bad = copy.deepcopy(asset)
            if change == "mesh":
                bad["layers"][0]["indices"][0] = 9999999
            else:
                bad["capabilities"]["trueSideViews"] = True
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.storage.restore(resigned(package, {"asset.json": json.dumps(bad).encode()}))
        texture = asset["layers"][0]["texture"]
        with self.assertRaises(ValueError):
            self.storage.restore(resigned(package, {texture: source()}))

    def test_symlink_and_expanded_member_budget_are_rejected_before_decode(self):
        import stat
        for mode, raw in ((stat.S_IFLNK | 0o777, b"../elsewhere"),
                          (stat.S_IFREG | 0o600, b"x" * (128 * 1024 * 1024 + 1))):
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
                member = zipfile.ZipInfo("source.png")
                member.compress_type = zipfile.ZIP_DEFLATED
                member.external_attr = mode << 16
                archive.writestr(member, raw)
            with self.assertRaises(ValueError):
                self.storage.restore(stream.getvalue())

    def test_source_tampering_cannot_be_rebuilt_or_served(self):
        first = self.storage.create(source(), "Good")
        path = self.storage.file(first["id"], "source.png")
        path.write_bytes(b"tampered")
        with self.assertRaises(ValueError):
            self.storage.build(first["id"], first["revision"], first["definition"])
        with self.assertRaises(ValueError):
            self.storage.file(first["id"], "source.png")

    def test_rejected_decoded_texture_budget_keeps_old_revision_and_all_pixels(self):
        image = Image.new("RGBA", (768, 768), (120, 80, 60, 255))
        first = self.storage.create(encoded(image), "Sparse parts")
        source_path = self.storage.file(first["id"], "source.png")
        source_before = source_path.read_bytes()
        with self.assertRaisesRegex(ValueError, "TEXTURE.*BUDGET"):
            self.storage.build(first["id"], first["revision"], wide_sparse_attachments(image))
        self.assertEqual(self.storage.get(first["id"]), first)
        self.assertEqual(self.storage.file(first["id"], "source.png").read_bytes(), source_before)


if __name__ == "__main__":
    unittest.main()
