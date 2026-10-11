import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location('qwen_image_smoke', SCRIPTS/'qwen_native_image_smoke.py')
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class NativeImageCleanupTest(unittest.TestCase):
    def args(self, root):
        inputs = root/'inputs'; inputs.mkdir()
        for name in ('exe', 'dit', 'vae', 'conditions'):
            (inputs/name).write_bytes(b'synthetic')
        return SimpleNamespace(executable=inputs/'exe', dit=inputs/'dit', vae=inputs/'vae',
            conditions=inputs/'conditions', compiler_runtime=root, tokens=27, seed=42,
            output=root/'out.png', report=root/'report.json', log=root/'run.log')

    def test_monitor_exception_cleans_up_and_records_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp)); process = Mock(pid=42, returncode=-15)
            with patch.object(SMOKE.subprocess,'Popen',return_value=process), \
                 patch.object(SMOKE.psutil,'Process',side_effect=RuntimeError('synthetic monitor failure')), \
                 patch.object(SMOKE,'stop_owned_process',return_value=[],autospec=True) as stop:
                with self.assertRaises(RuntimeError): SMOKE.smoke(args)
            result=json.loads(args.report.read_text())
            self.assertFalse(result['image_complete'])
            self.assertIn('synthetic monitor failure', result['monitor_error'])
            stop.assert_called_once_with(process, {})

    def test_interrupt_preserves_report_and_original_exception(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp)); process = Mock(pid=42, returncode=-15)
            with patch.object(SMOKE.subprocess,'Popen',return_value=process), \
                 patch.object(SMOKE.psutil,'Process',side_effect=KeyboardInterrupt), \
                 patch.object(SMOKE,'stop_owned_process',return_value=[],autospec=True) as stop:
                with self.assertRaises(KeyboardInterrupt): SMOKE.smoke(args)
            self.assertIn('KeyboardInterrupt',json.loads(args.report.read_text())['monitor_error'])
            stop.assert_called_once_with(process, {})

    def test_real_cleanup_reaps_owned_process_on_monitor_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp))
            with patch.object(SMOKE, 'measure', side_effect=RuntimeError('synthetic failure')):
                process, failure, _ = SMOKE.run_image(args,
                    [sys._base_executable, '-c', 'import time; time.sleep(60)'], 0, {})
            try:
                self.assertIsInstance(failure, RuntimeError)
                self.assertIsNotNone(process.poll(), 'Real cleanup must reap the owned process')
                self.assertFalse(getattr(failure, '__notes__', []))
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=10)

    def test_failed_spawn_skips_process_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp))
            with patch.object(SMOKE.subprocess, 'Popen', side_effect=OSError('spawn failure')), \
                 patch.object(SMOKE, 'stop_owned_process', autospec=True) as stop:
                process, failure, _ = SMOKE.run_image(args, ['absent'], 0, {})
            self.assertIsNone(process)
            self.assertIsInstance(failure, OSError)
            stop.assert_not_called()


if __name__ == '__main__': unittest.main()
