"""Safety regressions use synthetic paths and mocked processes, never model inference."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / 'qwen_export_teacher.py'
SPEC = importlib.util.spec_from_file_location('qwen_teacher_safety', SCRIPT)
SAFETY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SAFETY)
sys.path.insert(0, str(SCRIPT.parent))
import qwen_extract_teacher_reference as REFERENCE
import qwen_teacher_probe as PROBE


class SafetyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='zhiyu_teacher_safety_')
        self.root = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)
        self.original = self.root / 'original'
        self.packed = self.original / 'models' / 'te_packed' / 'L36'
        self.processor = self.original / 'models' / 'image' / 'processor'
        self.packed.mkdir(parents=True)
        self.processor.mkdir(parents=True)
        self.output = self.root / 'artifacts'
        self.output.mkdir()

    def test_every_destination_rejects_original_model_tree(self):
        for relative in ('new.gguf', 'run.log', 'report.json', 'stage/offload'):
            with self.subTest(relative=relative), self.assertRaisesRegex(ValueError, 'original|source'):
                SAFETY.validate_outputs([self.packed, self.processor], [self.original / relative])

    def test_reference_launcher_rejects_source_write_before_spawning(self):
        args = argparse.Namespace(packed=self.packed, processor=self.processor, layers=36,
            output=self.original / 'new-reference', log=self.output / 'job.log',
            report=self.output / 'report.json')
        with mock.patch.object(REFERENCE.subprocess, 'Popen', side_effect=AssertionError('spawned before path check')):
            with self.assertRaisesRegex(ValueError, 'original|source'):
                REFERENCE.launch(args)
        self.assertFalse(args.output.exists())

    def test_path_traversal_is_resolved_before_validation(self):
        with self.assertRaisesRegex(ValueError, 'original|source'):
            SAFETY.validate_outputs([self.packed], [self.output / '..' / 'original' / 'data.bin'])

    def test_existing_and_duplicate_output_rejected(self):
        file = self.output / 'existing'
        file.write_text('preserve', encoding='utf-8')
        with self.assertRaises(FileExistsError):
            SAFETY.validate_outputs([self.packed], [file], must_be_new=[file])
        with self.assertRaisesRegex(ValueError, 'distinct'):
            SAFETY.validate_outputs([self.packed], [self.output / 'same', self.output / 'same'])
        self.assertEqual(file.read_text(encoding='utf-8'), 'preserve')

    def test_worker_without_owned_parent_nonce_is_rejected(self):
        args = argparse.Namespace(worker_token='x' * 64)
        with mock.patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, 'worker'):
            SAFETY.require_worker(args)

    def test_worker_requires_matching_nonce(self):
        args = argparse.Namespace(worker_token='a' * 64)
        with mock.patch.dict(os.environ, {'QWEN_TE_WORKER_TOKEN': 'b' * 64}), self.assertRaises(ValueError):
            SAFETY.require_worker(args)

    def test_worker_cannot_overwrite_original_even_with_valid_nonce(self):
        original = self.packed / 'source.gguf'
        original.write_bytes(b'preserve original model')
        runtime = self.root / 'llama-src'
        runtime.mkdir()
        args = argparse.Namespace(worker_token='a' * 64, llama=runtime, packed=self.packed,
            processor=self.processor, output=original, log=self.output/'worker.log',
            report=self.output/'worker.json', layers=36)
        with mock.patch.dict(os.environ, {'QWEN_TE_WORKER_TOKEN': 'a' * 64}), \
                mock.patch.object(SAFETY, 'verify_llama', return_value=SAFETY.LLAMA_COMMIT):
            with self.assertRaisesRegex(ValueError, 'original|source'):
                SAFETY.worker(args)
        self.assertEqual(original.read_bytes(), b'preserve original model')
        self.assertFalse((self.packed / 'tokenizer-input').exists())

    def test_reference_cancel_keeps_primary_error_and_report(self):
        args = argparse.Namespace(packed=self.packed, processor=self.processor, layers=36,
            output=self.output/'reference', log=self.output/'reference.log',
            report=self.output/'reference-report.json')
        process = mock.Mock(pid=123, returncode=None)
        process.poll.return_value = None
        def cleanup(*unused):
            process.poll.return_value = -15
            return []
        with mock.patch.object(REFERENCE.subprocess, 'Popen', return_value=process), \
                mock.patch.object(REFERENCE, 'watch_job', side_effect=KeyboardInterrupt('cancel reference')), \
                mock.patch.object(REFERENCE, 'stop_owned_process', side_effect=cleanup) as stop:
            with self.assertRaisesRegex(KeyboardInterrupt, 'cancel reference'):
                REFERENCE.launch(args)
            stop.assert_called_once()
        result = json.loads(args.report.read_text(encoding='utf-8'))
        self.assertFalse(result['complete'])
        self.assertEqual(result['error_type'], 'KeyboardInterrupt')

    def test_export_cancel_keeps_primary_error_and_report(self):
        config = {'architectures':['Qwen3VLForConditionalGeneration'], 'model_type':'qwen3_vl',
            'text_config':{'num_hidden_layers':36, 'hidden_size':4096,
                           'num_attention_heads':32, 'num_key_value_heads':8}}
        (self.packed/'config.json').write_text(json.dumps(config), encoding='utf-8')
        runtime = self.root/'runtime'
        runtime.mkdir()
        args = argparse.Namespace(packed=self.packed, processor=self.processor, llama=runtime, layers=36,
            output=self.output/'encoder.gguf', log=self.output/'export.log', report=self.output/'export.json')
        process = mock.Mock(pid=123, returncode=None)
        process.poll.return_value = None
        def cleanup(*unused):
            process.poll.return_value = -15
            return []
        with mock.patch.object(SAFETY, 'verify_llama', return_value=SAFETY.LLAMA_COMMIT), \
                mock.patch.object(SAFETY.subprocess, 'Popen', return_value=process), \
                mock.patch.object(SAFETY, 'watch_job', side_effect=KeyboardInterrupt('cancel export')), \
                mock.patch.object(SAFETY, 'stop_owned_process', side_effect=cleanup) as stop:
            with self.assertRaisesRegex(KeyboardInterrupt, 'cancel export'):
                SAFETY.export(args)
            stop.assert_called_once()
        result = json.loads(args.report.read_text(encoding='utf-8'))
        self.assertFalse(result['complete'])
        self.assertFalse(args.output.exists())
        self.assertEqual(result['error_type'], 'KeyboardInterrupt')

    def test_probe_stdin_failure_cleans_process_and_writes_report(self):
        model = self.packed/'source.gguf'
        model.write_bytes(b'non-model test placeholder')
        runtime = self.root/'runtime'
        runtime.mkdir()
        executable = runtime/'fake.exe'
        executable.write_bytes(b'never executed')
        args = argparse.Namespace(model=model, executable=executable, layers=36,
            output=self.output/'probe', log=self.output/'probe.log', report=self.output/'probe.json',
            compiler_runtime=None, reference_cache=None)
        process = mock.Mock(pid=123, returncode=None)
        process.poll.return_value = None
        process.stdin.write.side_effect = BrokenPipeError('pipe interrupted')
        def cleanup(*unused):
            process.poll.return_value = -15
            return []
        with mock.patch.object(PROBE.subprocess, 'Popen', return_value=process), \
                mock.patch.object(PROBE, 'stop_owned_process', side_effect=cleanup) as stop:
            with self.assertRaisesRegex(BrokenPipeError, 'pipe interrupted'):
                PROBE.probe(args)
            stop.assert_called_once()
        result = json.loads(args.report.read_text(encoding='utf-8'))
        self.assertFalse(result['complete'])
        self.assertEqual(result['error_type'], 'BrokenPipeError')

    def test_report_failure_does_not_replace_primary_error(self):
        primary = KeyboardInterrupt('owned job interrupted')
        report = self.output / 'exists.json'
        report.write_text('original', encoding='utf-8')
        SAFETY.write_report(report, {'complete': False}, primary)
        self.assertEqual(report.read_text(encoding='utf-8'), 'original')
        self.assertTrue(any('report' in note for note in primary.__notes__))

    def test_watch_keyboard_interrupt_cleans_owned_family(self):
        process = mock.Mock(pid=123, returncode=None)
        process.poll.return_value = None
        process.wait.side_effect = KeyboardInterrupt('test cancellation')
        observed = mock.Mock(pid=123)
        observed.children.return_value = []
        observed.memory_info.return_value = types.SimpleNamespace(rss=1, peak_wset=2)
        observed.create_time.return_value = 1.0
        fake_psutil = types.SimpleNamespace(Process=lambda pid: observed,
            NoSuchProcess=type('NoSuchProcess', (Exception,), {}),
            AccessDenied=type('AccessDenied', (Exception,), {}),
            virtual_memory=lambda: types.SimpleNamespace(available=16 * 1024**3))
        with mock.patch.dict(sys.modules, {'psutil': fake_psutil}), mock.patch.object(SAFETY, 'stop_owned_process', return_value=[]) as cleanup:
            with self.assertRaises(KeyboardInterrupt):
                SAFETY.watch(process)
            cleanup.assert_called_once()

    def test_watch_timeout_cleans_owned_family(self):
        process = mock.Mock(pid=123, returncode=None)
        process.poll.return_value = None
        observed = mock.Mock(pid=123)
        observed.children.return_value = []
        observed.memory_info.return_value = types.SimpleNamespace(rss=1, peak_wset=2)
        observed.create_time.return_value = 1.0
        fake_psutil = types.SimpleNamespace(Process=lambda pid: observed,
            NoSuchProcess=type('NoSuchProcess', (Exception,), {}),
            AccessDenied=type('AccessDenied', (Exception,), {}),
            virtual_memory=lambda: types.SimpleNamespace(available=16 * 1024**3))
        with mock.patch.dict(sys.modules, {'psutil': fake_psutil}), mock.patch.object(SAFETY, 'stop_owned_process', return_value=[]) as cleanup:
            result = SAFETY.watch_job(process, max_seconds=0)
            self.assertIn('timeout', result['stop_reason'].lower())
            cleanup.assert_called_once()

    def test_cleanup_only_terminates_owned_parent_and_children(self):
        process = mock.Mock(pid=123, returncode=None)
        process.poll.return_value = None
        parent = mock.Mock(pid=123)
        parent.create_time.return_value = 1.0
        parent.is_running.return_value = True
        child = mock.Mock(pid=124)
        child.create_time.return_value = 2.0
        child.is_running.return_value = True
        unrelated = mock.Mock(pid=999)
        parent.children.return_value = [child]
        parent.terminate.side_effect = lambda: setattr(process.poll, 'return_value', -15)
        fake_psutil = types.SimpleNamespace(Process=lambda pid: parent,
            NoSuchProcess=type('NoSuchProcess', (Exception,), {}),
            AccessDenied=type('AccessDenied', (Exception,), {}),
            wait_procs=lambda members, timeout: (members, []))
        with mock.patch.dict(sys.modules, {'psutil': fake_psutil}):
            self.assertEqual(SAFETY.stop_owned_process(process, {}), [])
        parent.terminate.assert_called_once()
        child.terminate.assert_called_once()
        unrelated.terminate.assert_not_called()
        process.kill.assert_not_called()

    def test_temporary_tensors_are_forced_into_owned_staging(self):
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(SAFETY.tempfile, 'tempdir', None):
            SAFETY.constrain_worker_environment(self.output)
            self.assertEqual(SAFETY.tempfile.tempdir, str(self.output.resolve()))
            self.assertEqual(os.environ['TMPDIR'], str(self.output.resolve()))
            self.assertEqual(os.environ['HF_HUB_OFFLINE'], '1')


if __name__ == '__main__':
    unittest.main()
