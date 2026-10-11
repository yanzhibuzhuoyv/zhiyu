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
SPEC = importlib.util.spec_from_file_location('qwen_quantize_safety', SCRIPTS/'qwen_diffusion_quantize.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class QuantizeSafetyTest(unittest.TestCase):
    def args(self, root):
        source = root/'source'; source.mkdir()
        index = source/'models/Qwen-Image-2.1-Turbo/transformer/diffusion_pytorch_model.safetensors.index.json'
        index.parent.mkdir(parents=True); index.write_text('{}')
        runtime = root/'runtime'; runtime.mkdir()
        executable = runtime/'synthetic.exe'; executable.write_bytes(b'not executed')
        return SimpleNamespace(model_root=source, executable=executable,
            output=root/'out.gguf', report=root/'report.json', log=root/'run.log')

    def test_all_destinations_protect_original_model_tree(self):
        for field in ('output', 'log', 'report'):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                args = self.args(Path(tmp)); target=args.model_root/('forbidden-'+field)
                setattr(args, field, target)
                with patch.object(MODULE.subprocess, 'Popen', side_effect=AssertionError('Must not spawn')):
                    with self.assertRaisesRegex(ValueError, 'source/model tree'):
                        MODULE.quantize(args)
                self.assertFalse(target.exists())

    def test_duplicate_destinations_rejected_before_spawn(self):
        with tempfile.TemporaryDirectory() as tmp:
            args=self.args(Path(tmp)); args.report=args.log
            with patch.object(MODULE.subprocess, 'Popen', side_effect=AssertionError('Must not spawn')):
                with self.assertRaisesRegex(ValueError, 'distinct'): MODULE.quantize(args)

    def test_interrupt_preserves_failure_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            args=self.args(Path(tmp)); process=Mock(returncode=-15)
            with patch.object(MODULE.subprocess, 'Popen', return_value=process), \
                 patch.object(MODULE, 'watch_job', side_effect=KeyboardInterrupt), \
                 patch.object(MODULE, 'stop_owned_process', return_value=[], autospec=True) as stop:
                with self.assertRaises(KeyboardInterrupt): MODULE.quantize(args)
                stop.assert_called_once_with(process, {})
            report=json.loads(args.report.read_text())
            self.assertFalse(report['complete'])
            self.assertEqual(report['error_type'], 'KeyboardInterrupt')

    def test_guard_never_publishes_zero_exit_partial(self):
        with tempfile.TemporaryDirectory() as tmp:
            args=self.args(Path(tmp)); process=Mock(returncode=0)
            with patch.object(MODULE.subprocess, 'Popen', return_value=process), \
                 patch.object(MODULE, 'watch_job', return_value={'stop_reason':'owned resource guard'}), \
                 patch.object(MODULE, 'stop_owned_process', return_value=[], autospec=True):
                with self.assertRaisesRegex(RuntimeError, 'resource guard'): MODULE.quantize(args)
            self.assertFalse(args.output.exists())
            self.assertFalse(json.loads(args.report.read_text())['complete'])


if __name__ == '__main__': unittest.main()
