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
SPEC = importlib.util.spec_from_file_location('qwen_phone_smoke', SCRIPTS / 'qwen_phone_image_smoke.py')
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class PhoneCleanupTest(unittest.TestCase):
    def args(self, root):
        return SimpleNamespace(adb=Path('adb'), serial='test-device',
            directory='/data/local/tmp/zhiyu_qwen_research_test',
            log=root/'run.log', report=root/'run.json', output=root/'image.png')

    def test_monitor_error_preserves_report_and_stops_only_owned_job(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp))
            process = Mock(returncode=255)
            process.poll.return_value = None
            with patch.object(SMOKE.subprocess, 'run', return_value=SimpleNamespace(returncode=1)), \
                 patch.object(SMOKE.subprocess, 'Popen', return_value=process), \
                 patch.object(SMOKE, 'own_pids', return_value=['42']), \
                 patch.object(SMOKE, 'status', side_effect=TimeoutError('synthetic monitor timeout')), \
                 patch.object(SMOKE, 'stop_own_job') as stop:
                with self.assertRaises(RuntimeError):
                    SMOKE.smoke(args)
            result = json.loads(args.report.read_text())
            self.assertFalse(result['image_complete'])
            self.assertIn('synthetic monitor timeout', result['monitor_error'])
            self.assertEqual(result['remote_cleanup']['status'], 'stopped_owned_job')
            stop.assert_called_once_with(args, '42')

    def test_adb_255_is_not_recorded_as_native_exit(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp))
            process = Mock(returncode=255)
            process.poll.return_value = 255
            with patch.object(SMOKE.subprocess, 'run', return_value=SimpleNamespace(returncode=1)), \
                 patch.object(SMOKE.subprocess, 'Popen', return_value=process), \
                 patch.object(SMOKE, 'own_pids', return_value=['42']), \
                 patch.object(SMOKE, 'stop_own_job') as stop:
                with self.assertRaises(RuntimeError):
                    SMOKE.smoke(args)
            result = json.loads(args.report.read_text())
            self.assertEqual(result['adb_exit_code'], 255)
            self.assertIsNone(result['native_exit_code'])
            stop.assert_called_once_with(args, '42')

    def test_failed_remote_cleanup_is_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp))
            process = Mock(returncode=255)
            process.poll.return_value = 255
            with patch.object(SMOKE.subprocess, 'run', return_value=SimpleNamespace(returncode=1)), \
                 patch.object(SMOKE.subprocess, 'Popen', return_value=process), \
                 patch.object(SMOKE, 'own_pids', side_effect=TimeoutError('USB unavailable')):
                with self.assertRaises(RuntimeError):
                    SMOKE.smoke(args)
            result = json.loads(args.report.read_text())
            self.assertEqual(result['remote_cleanup']['status'], 'unconfirmed')
            self.assertIn('USB unavailable', result['remote_cleanup']['error'])

    def test_observer_keyboard_interrupt_cleans_up_and_keeps_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp))
            with patch.object(SMOKE, 'own_pids', return_value=['42']), \
                 patch.object(SMOKE, 'status', side_effect=KeyboardInterrupt), \
                 patch.object(SMOKE, 'stop_own_job') as stop:
                with self.assertRaises(KeyboardInterrupt):
                    SMOKE.observe_existing(args)
            result = json.loads(args.report.read_text())
            self.assertEqual(result['remote_cleanup']['status'], 'stopped_owned_job')
            stop.assert_called_once_with(args, '42')

    def test_actual_transport_error_is_not_treated_as_no_process(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp))
            failed = SimpleNamespace(returncode=1, stdout='', stderr='device offline')
            with patch.object(SMOKE.subprocess, 'run', return_value=failed):
                result = SMOKE.cleanup_remote(args)
            self.assertEqual(result['status'], 'unconfirmed')
            self.assertIn('device offline', result['error'])

    def test_only_complete_remote_absence_receipt_means_no_owned_job(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp))
            with patch.object(SMOKE, 'adb', return_value='\nZHIYU_PIDOF_STATUS:1\n'):
                self.assertEqual(SMOKE.own_pids(args), [])
            with patch.object(SMOKE, 'adb', return_value=''):
                with self.assertRaises(RuntimeError): SMOKE.own_pids(args)

    def test_cmdline_read_error_is_unknown_ownership(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp))
            with patch.object(SMOKE, 'adb', side_effect=['42\nZHIYU_PIDOF_STATUS:0\n', RuntimeError('unreadable cmdline')]):
                result = SMOKE.cleanup_remote(args)
            self.assertEqual(result['status'], 'unconfirmed')


if __name__ == '__main__':
    unittest.main()
