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
SPEC = importlib.util.spec_from_file_location('qwen_checkpoint_smoke', SCRIPTS/'qwen_native_image_smoke.py')
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class NativeCheckpointSmokeTest(unittest.TestCase):
    def args(self, root, sample_only=False):
        inputs = root/'inputs'; inputs.mkdir()
        for name in ('exe', 'dit', 'vae', 'conditions', 'inspector'):
            (inputs/name).write_bytes(b'synthetic')
        return SimpleNamespace(executable=inputs/'exe', dit=inputs/'dit', vae=inputs/'vae',
            conditions=inputs/'conditions', inspector=inputs/'inspector', compiler_runtime=root,
            tokens=27, seed=42, output=root/'out.png', report=root/'report.json', log=root/'run.log',
            checkpoint=root/'checkpoint.qwenlatent', sample_only=sample_only)

    def test_sample_only_requires_checkpoint_before_spawn(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp), True); args.checkpoint = None
            with patch.object(SMOKE.subprocess, 'Popen') as spawn:
                with self.assertRaises(ValueError): SMOKE.smoke(args)
            spawn.assert_not_called()

    def test_native_zero_without_checkpoint_is_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp), True)
            with patch.object(SMOKE, 'run_image', return_value=(Mock(returncode=0), None, None)):
                with self.assertRaises(RuntimeError): SMOKE.smoke(args)
            report = json.loads(args.report.read_text())
            self.assertFalse(report['latent_complete'])
            self.assertFalse(report['image_complete'])

    def test_checkpoint_native_inspector_failure_retains_failed_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp), True)
            def run(*_):
                args.checkpoint.write_bytes(b'not-a-valid-checkpoint')
                return Mock(returncode=0), None, None
            with patch.object(SMOKE, 'run_image', side_effect=run), \
                 patch.object(SMOKE, 'inspect_checkpoint', side_effect=ValueError('invalid checkpoint CRC')):
                with self.assertRaises(ValueError): SMOKE.smoke(args)
            report = json.loads(args.report.read_text())
            self.assertFalse(report['latent_complete'])
            self.assertIn('CRC', report['monitor_error'])

    def test_valid_sample_only_never_reports_image_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp), True); captured = []
            def run(_, command, *__):
                captured.extend(command)
                args.checkpoint.write_bytes(b'synthetic-verified-by-mock')
                return Mock(returncode=0), None, None
            with patch.object(SMOKE, 'run_image', side_effect=run), \
                 patch.object(SMOKE, 'inspect_checkpoint', return_value={'latent_complete': True}), \
                 patch.object(SMOKE, 'inspect_image') as image, patch('builtins.print'):
                SMOKE.smoke(args)
            report = json.loads(args.report.read_text())
            self.assertTrue(report['latent_complete'])
            self.assertFalse(report['image_complete'])
            self.assertEqual(captured[-2], '--sample-only')
            self.assertFalse(args.output.exists())
            image.assert_not_called()

    def test_checkpoint_output_inside_input_tree_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp), True); args.checkpoint = args.dit.parent/'latent.qwenlatent'
            with patch.object(SMOKE.subprocess, 'Popen') as spawn:
                with self.assertRaises(ValueError): SMOKE.smoke(args)
            spawn.assert_not_called()

    def metadata(self):
        return {'scope': 'research_checkpoint_inspection', 'format_version': 1,
            'model': 'QwenImage2.1', 'stage': 'diffusion_normalized', 'shape': [32, 32, 64, 1],
            'value_count': 65536, 'dtype': 'F32LE', 'layout': 'width_fast', 'sampling_steps': 8,
            'seed': 42, 'bytes': 262288, 'crc_verified': True, 'finite': True, 'model_loaded': False}

    def test_inspector_rejects_other_seed_or_unverified_crc(self):
        for field, value in (('seed', 43), ('crc_verified', False), ('format_version', True)):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                args = self.args(Path(tmp), True)
                args.checkpoint.write_bytes(bytes(262288))
                metadata = {**self.metadata(), field: value}
                with patch.object(SMOKE.subprocess, 'run', return_value=Mock(stdout=json.dumps(metadata))):
                    with self.assertRaises(ValueError): SMOKE.inspect_checkpoint(args)

    def test_inspection_rejects_checkpoint_changed_between_reads(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = self.args(Path(tmp), True); args.checkpoint.write_bytes(bytes(262288))
            with patch.object(SMOKE.subprocess, 'run', return_value=Mock(stdout=json.dumps(self.metadata()))), \
                 patch.object(SMOKE, 'digest', side_effect=['before', 'after']):
                with self.assertRaisesRegex(ValueError, 'changed'): SMOKE.inspect_checkpoint(args)

    def test_all_outputs_protect_original_tree_even_with_copied_model_inputs(self):
        for field in ('log', 'report', 'output', 'checkpoint'):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); args = self.args(root, True)
                original = root/'QWEN-IMAGE-EDGE'; original.mkdir()
                destination = original/'nested'/('new-'+field)
                setattr(args, field, destination)
                with patch.object(SMOKE.subprocess, 'Popen') as spawn:
                    with self.assertRaises(ValueError): SMOKE.smoke(args)
                spawn.assert_not_called()
                self.assertFalse(destination.parent.exists())


if __name__ == '__main__': unittest.main()
