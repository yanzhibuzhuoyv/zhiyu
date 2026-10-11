from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "qwen_diffusion_prepare.py"
SPEC = importlib.util.spec_from_file_location("qwen_diffusion_prepare", SCRIPT)
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class RuntimePinTest(unittest.TestCase):
    def test_patch_matches_lock(self):
        lock = json.loads((PREPARE.FORK / "upstream-lock.json").read_text(encoding="utf-8"))
        digest = hashlib.sha256((PREPARE.FORK / "patches/external-condition.patch").read_bytes()).hexdigest()
        self.assertEqual(lock["patch_sha256"], digest)

    def test_wrong_commit_stops_before_mutation(self):
        with patch.object(PREPARE, "git", return_value=subprocess.CompletedProcess([], 0, "wrong\n", "")) as git:
            with self.assertRaisesRegex(ValueError, "checkout is wrong"):
                PREPARE.verify_pin(Path("checkout"), {"commit": "expected", "submodules": {}})
            self.assertEqual(git.call_count, 1)

    def test_wrong_ggml_commit_is_rejected(self):
        outputs = [subprocess.CompletedProcess([], 0, "sd\n", ""),
                   subprocess.CompletedProcess([], 0, "wrong-ggml\n", "")]
        with patch.object(PREPARE, "git", side_effect=outputs):
            with self.assertRaisesRegex(ValueError, "submodule ggml"):
                PREPARE.verify_pin(Path("checkout"), {"commit": "sd", "submodules": {"ggml": "expected"}})

    def test_original_model_directory_is_never_written(self):
        with tempfile.TemporaryDirectory() as temporary:
            model = Path(temporary) / "qwen-image-edge" / "runtime"
            model.mkdir(parents=True)
            with patch.object(PREPARE, "git") as git:
                with self.assertRaisesRegex(ValueError, "original model directory"):
                    PREPARE.prepare(model)
                git.assert_not_called()

    def test_source_paths_must_exist(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(FileNotFoundError):
                PREPARE.prepare(Path(temporary) / "missing")

    def test_verify_only_rejects_missing_fork_header(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(PREPARE, 'verify_pin'):
                with self.assertRaisesRegex(ValueError, 'fork header'):
                    PREPARE.verify_prepared(Path(temporary))

    def test_verify_only_rejects_different_fork_header_without_writing(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            path = source / 'include/qwen_edge_external_condition.h'
            path.parent.mkdir()
            path.write_bytes(b'different')
            with patch.object(PREPARE, 'verify_pin'):
                with self.assertRaisesRegex(ValueError, 'fork header'):
                    PREPARE.verify_prepared(source)
            self.assertEqual(path.read_bytes(), b'different')

    def test_verify_only_rejects_unapplied_patch(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            for name, target in (('qwen_edge_external_condition.h', 'include'),
                                 ('qwen_edge_condition.hpp', 'src/conditioning')):
                destination = source / target / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes((PREPARE.FORK / 'include' / name).read_bytes())
            with patch.object(PREPARE, 'verify_pin'), patch.object(PREPARE, 'git',
                    return_value=subprocess.CompletedProcess([], 1, '', 'not applied')):
                with self.assertRaisesRegex(ValueError, 'not applied'):
                    PREPARE.verify_prepared(source)


if __name__ == "__main__":
    unittest.main()
