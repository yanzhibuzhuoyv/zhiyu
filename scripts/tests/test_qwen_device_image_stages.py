"""Host contracts only: no Android, ADB, model or external API execution."""
import hashlib
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('qwen_image_stages', SCRIPTS/'qwen_device_image_stages.py')
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)
SHELL = SCRIPTS.parent/'native/qwen-edge/diffusion/device_image_stages.sh'
COMMON = SHELL.with_name('device_guard_common.sh')
BASH = Path('C:/msys64/usr/bin/bash.exe')
OWNED = '/data/local/tmp/zhiyu_qwen_research_stage_r1'
SOURCE = '/data/local/tmp/zhiyu_qwen_research_models_r1'
SAMPLE = '/data/local/tmp/zhiyu_qwen_research_sample_r1'


def receipt(kind, stage='sample', **overrides):
    fields = {'version':'2', 'kind':kind, 'directory':OWNED, 'stage':stage,
              'source_directory':SOURCE, 'checkpoint_path':OWNED+'/checkpoint.qwenlatent',
              'tokens':'32', 'seed':'5', 'budget_seconds':'3900', 'complete':'1'}
    if stage == 'decode':
        fields.update(checkpoint_path=SAMPLE+'/checkpoint.qwenlatent', tokens='from_checkpoint',
                      seed='from_checkpoint', budget_seconds='900', decode_mode='tile256')
    if kind == 'native':
        fields.update(native_pid='81', native_start='9001', native_pgid='81', native_session='70',
                      argv0=OWNED+'/image_smoke', native_wait_code='0', wait_confirmed='1', term_interrupted='0')
    else:
        fields.update(guard_pid='90', guard_start='8000', guard_pgid='90', guard_session='90',
                      timeout_wait_code='0', stop_reason='finished', cleanup='not_needed')
    fields.update(overrides)
    return ''.join(f'{key}={value}\n' for key,value in fields.items())


class DeviceImageStagesTest(unittest.TestCase):
    def shell(self, body):
        source = '/'+SHELL.drive[0].lower()+SHELL.as_posix()[2:]
        return subprocess.run([str(BASH), '--noprofile', '--norc', '-c',
            'set -- __library; . "$QWEN_TEST_SHELL"; '+body],
            env={**os.environ, 'QWEN_TEST_SHELL':source, 'MSYS2_ARG_CONV_EXCL':'*',
                 'PATH':'C:/msys64/usr/bin;'+os.environ.get('PATH','')},
            capture_output=True, text=True, timeout=10)

    def test_launch_sample_detached_fixed_sources_and_budget(self):
        cmd = RUNNER.launch_sample(OWNED, SOURCE, 498, 2147483647, 3900)
        self.assertIn('nohup setsid -w /system/bin/sh', cmd)
        self.assertIn('supervise sample '+OWNED+' '+SOURCE+' 498 2147483647 3900', cmd)
        self.assertIn('set -C', cmd)
        self.assertIn('</dev/null', cmd)
        self.assertNotIn('--steps', cmd)

    def test_launch_decode_uses_separate_checkpoint_directory(self):
        cmd = RUNNER.launch_decode(OWNED, SOURCE, SAMPLE, 'tile256', 900)
        self.assertIn('supervise decode '+OWNED+' '+SOURCE+' '+SAMPLE+' tile256 900', cmd)
        self.assertNotIn('fullbody', cmd)

    def test_launch_rejects_escaping_paths_and_unknown_options(self):
        for path in ('/tmp/x', OWNED+'/..', OWNED+';echo x', OWNED+'-bad', OWNED+'/sub'):
            with self.assertRaises(ValueError): RUNNER.launch_sample(path, SOURCE, 32, 5)
            with self.assertRaises(ValueError): RUNNER.launch_decode(OWNED, SOURCE, path, 'full')
        with self.assertRaises(ValueError): RUNNER.launch_decode(OWNED, SOURCE, SAMPLE, 'full --evil')

    def test_inputs_do_not_alias_output_job(self):
        with self.assertRaises(ValueError): RUNNER.launch_sample(OWNED, OWNED, 32, 5)
        with self.assertRaises(ValueError): RUNNER.launch_decode(OWNED, OWNED, SAMPLE, 'full')
        with self.assertRaises(ValueError): RUNNER.launch_decode(OWNED, SOURCE, OWNED, 'full')

    def test_typed_sample_token_seed_and_budget_bounds(self):
        for tokens in (0, 499, 512, -1, True, 1.2, '32'):
            with self.assertRaises(ValueError): RUNNER.launch_sample(OWNED, SOURCE, tokens, 5)
        for seed in (-1, 2147483648, True, 1.2, '5'):
            with self.assertRaises(ValueError): RUNNER.launch_sample(OWNED, SOURCE, 32, seed)
        for budget in (0, 3901, True, 1.2):
            with self.assertRaises(ValueError): RUNNER.launch_sample(OWNED, SOURCE, 32, 5, budget)
        for budget in (0, 901, True, 1.2):
            with self.assertRaises(ValueError): RUNNER.launch_decode(OWNED, SOURCE, SAMPLE, 'full', budget)

    def test_sampling_receipts_do_not_claim_checkpoint_or_png(self):
        result = RUNNER.parse_run(receipt('guardian'), receipt('native'))
        self.assertTrue(result['execution_success'])
        self.assertFalse(result['latent_verified'])
        self.assertFalse(result['png_verified'])
        self.assertEqual(result['stage'], 'sample')
        self.assertEqual(result['tokens'], 32)
        self.assertEqual(result['seed'], 5)

    def test_decode_receipts_do_not_fabricate_checkpoint_header(self):
        result = RUNNER.parse_run(receipt('guardian','decode'), receipt('native','decode'))
        self.assertTrue(result['execution_success'])
        self.assertIsNone(result['tokens'])
        self.assertIsNone(result['seed'])
        self.assertFalse(result['latent_verified'])
        self.assertFalse(result['png_verified'])

    def test_timeout_keeps_direct_native_wait_separate(self):
        result = RUNNER.parse_run(receipt('guardian',timeout_wait_code='124'),
                                  receipt('native',native_wait_code='143'))
        self.assertFalse(result['execution_success'])
        self.assertEqual(result['state'],'timeout')
        self.assertEqual(result['native_exit_code'],143)
        self.assertEqual(result['timeout_wait_code'],124)

    def test_interrupted_and_missing_native_wait_stays_unconfirmed(self):
        for native in (None, receipt('native',wait_confirmed='0',term_interrupted='1')):
            result = RUNNER.parse_run(receipt('guardian',cleanup='unconfirmed'), native)
            self.assertFalse(result['execution_success'])
            self.assertIsNone(result['native_exit_code'])

    def test_guard_reason_not_overwritten_by_zero_exit(self):
        result = RUNNER.parse_run(receipt('guardian',stop_reason='guardian_signal',cleanup='confirmed'),
                                  receipt('native'))
        self.assertFalse(result['execution_success'])
        self.assertEqual(result['state'],'guardian_signal')

    def test_receipt_kind_version_unknown_keys_and_duplicates_rejected(self):
        for text in (receipt('native',version='1'), receipt('native')+'surprise=x\n',
                     receipt('native')+'native_wait_code=0\n', receipt('native').replace('complete=1\n','')):
            with self.assertRaises(ValueError): RUNNER.parse_receipt(text,'native')

    def test_receipt_stage_path_and_numeric_fences(self):
        for fields in ({'stage':'vae'}, {'checkpoint_path':SOURCE+'/elsewhere'}, {'tokens':'499'},
                       {'seed':'2147483648'}, {'budget_seconds':'3901'}, {'source_directory':OWNED},
                       {'argv0':SOURCE+'/image_smoke'}, {'native_pid':'1'}):
            with self.assertRaises(ValueError): RUNNER.parse_receipt(receipt('native',**fields),'native')
        with self.assertRaises(ValueError): RUNNER.parse_receipt(receipt('native','decode',seed='5'),'native')
        with self.assertRaises(ValueError): RUNNER.parse_receipt(receipt('native','decode',budget_seconds='901'),'native')

    def test_mixed_receipt_stage_source_checkpoint_tokens_or_seed_rejected(self):
        for fields in ({'directory':OWNED+'2'}, {'source_directory':SOURCE+'2'},
                       {'checkpoint_path':OWNED+'2/checkpoint.qwenlatent'}, {'tokens':'33'}, {'seed':'6'}):
            with self.assertRaises(ValueError): RUNNER.parse_run(receipt('guardian'),receipt('native',**fields))
        with self.assertRaises(ValueError): RUNNER.parse_run(receipt('guardian'),receipt('native','decode'))

    def test_both_wrappers_pin_same_common_digest(self):
        digest = hashlib.sha256(COMMON.read_bytes()).hexdigest()
        for wrapper in (SHELL, SHELL.with_name('device_vae_runner.sh')):
            self.assertIn("qwen_common_sha='"+digest+"'", wrapper.read_text())
        self.assertNotIn('QWEN_COMMON_PATH', SHELL.read_text())

    def test_stage_boundary_is_3900_sample_and_900_decode(self):
        result = self.shell("qwen_stage_number 3900 1 3900; echo $?; qwen_stage_number 3901 1 3900; echo $?; qwen_stage_number 900 1 900; echo $?; qwen_stage_number 901 1 900; echo $?; qwen_stage_number 0 0 2147483647; echo $?")
        self.assertEqual(result.stdout.splitlines(),['0','1','0','1','0'],result.stderr)

    def test_native_sample_argv_is_fixed_and_does_not_select_pose_or_steps(self):
        result = self.shell("directory='"+OWNED+"'; source_directory='"+SOURCE+"'; stage=sample; tokens=32; seed=5; qwen_native_start() { :; }; qwen_invoke_native() { printf '%s\\n' \"$@\"; }; qwen_stage_native")
        self.assertEqual(result.stdout.splitlines(),[OWNED+'/image_smoke',SOURCE+'/dit.gguf',SOURCE+'/vae.safetensors',SOURCE+'/conditions.f32','32','5',OWNED+'/output.png','--sample-only',OWNED+'/checkpoint.qwenlatent'],result.stderr)

    def test_native_decode_argv_never_contains_synthetic_pattern(self):
        result = self.shell("directory='"+OWNED+"'; source_directory='"+SOURCE+"'; checkpoint_directory='"+SAMPLE+"'; stage=decode; decode_mode=tile256; qwen_invoke_native() { printf '%s\\n' \"$@\"; }; qwen_stage_native")
        self.assertEqual(result.stdout.splitlines(),[OWNED+'/image_smoke',SOURCE+'/vae.safetensors','tile256',SAMPLE+'/checkpoint.qwenlatent',OWNED+'/output.png'],result.stderr)

    def test_unknown_stage_never_invokes_native(self):
        result = self.shell("stage=anything; qwen_invoke_native() { echo UNSAFE; }; qwen_stage_native")
        self.assertNotIn('UNSAFE',result.stdout)
        self.assertNotEqual(result.returncode,0)

    def test_negative_pid_and_foreign_executable_never_signalled(self):
        for identity in ('-81 9001 81 70 '+OWNED+'/image_smoke', '81 9001 81 70 '+SOURCE+'/other'):
            result = self.shell("qwen_identity() { printf '%s\\n' '"+identity+"'; }; kill() { echo UNSAFE; }; qwen_stop_native '"+identity+"'")
            self.assertNotIn('UNSAFE',result.stdout)
            self.assertNotEqual(result.returncode,0)

    def test_shared_common_preserves_cancel_before_monitor(self):
        result = self.shell("stop_reason=guardian_signal; timeout_pid=81; cleanup=not_needed; kill() { return 1; }; qwen_request_stop() { echo CANCELLED; }; qwen_monitor; echo \"$stop_reason\"")
        self.assertEqual(result.stdout.splitlines(),['CANCELLED','guardian_signal'],result.stderr)

    def test_unknown_hal_and_thresholds_remain_fail_closed(self):
        result = self.shell("qwen_stop_reason '' 0 999999; qwen_stop_reason 44 0 393216; qwen_stop_reason 43.9 0 393215; qwen_stop_reason 43.9 3 999999")
        self.assertEqual(result.stdout.splitlines(),['readings_unknown','battery_44c','memory_low','thermal_severe'],result.stderr)

    def test_sample_reservation_refuses_existing_checkpoint_without_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary); old=folder/'checkpoint.qwenlatent'; old.write_bytes(b'OLD')
            posix='/'+folder.drive[0].lower()+folder.as_posix()[2:]
            result=self.shell("stage=sample; qwen_reserve_files '"+posix+"'")
            self.assertEqual(old.read_bytes(),b'OLD')
            self.assertFalse((folder/'run.lock').exists())
        self.assertNotEqual(result.returncode,0)

    def test_stop_request_synthetic_link_refused_before_cleanup_or_write(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary); (folder/'external').write_bytes(b'OLD')
            posix='/'+folder.drive[0].lower()+folder.as_posix()[2:]
            result=self.shell("directory='"+posix+"'; stop_reason=guardian_signal; qwen_is_link() { test \"$1\" = \"$directory/stop.request\"; }; qwen_cleanup() { echo UNSAFE; }; qwen_request_stop")
            self.assertEqual((folder/'external').read_bytes(),b'OLD')
        self.assertNotIn('UNSAFE',result.stdout)
        self.assertNotEqual(result.returncode,0)

    def test_worker_rejects_synthetic_link_guardian_identity_before_read(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary); (folder/'external').write_text('90 8000 90 90 sh\n')
            posix='/'+folder.drive[0].lower()+folder.as_posix()[2:]
            (folder/'guardian.identity').write_text('fixture link type')
            result=self.shell("directory='"+posix+"'; qwen_is_link() { test \"$1\" = \"$directory/guardian.identity\"; }; qwen_identity() { echo '81 9001 81 81 sh'; }; cat() { echo UNSAFE >&2; echo UNSAFE; }; qwen_worker_isolated")
        self.assertNotIn('UNSAFE',result.stdout)
        self.assertNotIn('UNSAFE',result.stderr)
        self.assertNotEqual(result.returncode,0)

    def test_after_wait_does_not_read_synthetic_link_native_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary); (folder/'external').write_text('81 9001 81 70 sh\n')
            posix='/'+folder.drive[0].lower()+folder.as_posix()[2:]
            (folder/'native.identity').write_text('fixture link type')
            result=self.shell("directory='"+posix+"'; qwen_is_link() { test \"$1\" = \"$directory/native.identity\"; }; timeout_code=0; stop_reason=finished; cleanup=not_needed; cat() { echo UNSAFE >&2; echo UNSAFE; }; qwen_guardian_receipt() { echo \"$stop_reason $cleanup\"; }; qwen_after_wait")
        self.assertNotIn('UNSAFE',result.stdout)
        self.assertNotIn('UNSAFE',result.stderr)
        self.assertIn('identity_unknown unconfirmed',result.stdout)

    def test_source_and_job_trees_never_overlap(self):
        result=self.shell("qwen_stage_distinct /owned/a /owned/a; echo $?; qwen_stage_distinct /owned/a /owned/a/b; echo $?; qwen_stage_distinct /owned/a/b /owned/a; echo $?; qwen_stage_distinct /owned/a /owned/ab; echo $?")
        self.assertEqual(result.stdout.splitlines(),['1','1','1','0'],result.stderr)

    def test_sample_validation_reads_exact_conditions_only_from_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            base=Path(temporary); job=base/'job'; models=base/'models'; job.mkdir(); models.mkdir()
            for folder,names in ((job,('image_smoke','image_guardian.sh')), (models,('dit.gguf','vae.safetensors'))):
                for name in names: (folder/name).write_bytes(b'#!/bin/sh\nexit 99\n' if name=='image_smoke' else b'fixture')
            (models/'conditions.f32').write_bytes(b'\0'*(27*16384))
            job_posix='/'+job.drive[0].lower()+job.as_posix()[2:]; source_posix='/'+models.drive[0].lower()+models.as_posix()[2:]
            body="qwen_physical_directory() { test -d \"$1\"; }; chmod 700 '"+job_posix+"/image_smoke'; qwen_validate_inputs sample '"+job_posix+"' '"+source_posix+"' 27 5 3900"
            result=self.shell(body)
            self.assertEqual(result.returncode,0,result.stderr)
            (models/'conditions.f32').write_bytes(b'\0'*(27*16384-1))
            result=self.shell(body)
            self.assertNotEqual(result.returncode,0)
            (models/'conditions.f32').unlink(); (job/'conditions.f32').write_bytes(b'\0'*(27*16384))
            result=self.shell(body)
            self.assertNotEqual(result.returncode,0)

    def test_decode_size_check_does_not_claim_header_or_crc_verified(self):
        with tempfile.TemporaryDirectory() as temporary:
            base=Path(temporary); job=base/'job'; models=base/'models'; sample=base/'sample'
            for folder in (job,models,sample): folder.mkdir()
            for folder,names in ((job,('image_smoke','image_guardian.sh')), (models,('vae.safetensors',))):
                for name in names: (folder/name).write_bytes(b'#!/bin/sh\nexit 99\n' if name=='image_smoke' else b'fixture')
            checkpoint=sample/'checkpoint.qwenlatent'; checkpoint.write_bytes(b'\0'*262288)
            paths=['/'+folder.drive[0].lower()+folder.as_posix()[2:] for folder in (job,models,sample)]
            body="qwen_physical_directory() { test -d \"$1\"; }; chmod 700 '"+paths[0]+"/image_smoke'; qwen_validate_inputs decode '"+paths[0]+"' '"+paths[1]+"' '"+paths[2]+"' tile256 900"
            result=self.shell(body)
            self.assertEqual(result.returncode,0,result.stderr)
            checkpoint.write_bytes(b'\0'*262287)
            self.assertNotEqual(self.shell(body).returncode,0)

    def test_condition_synthetic_link_type_rejected_before_stat(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'conditions.f32'; path.write_bytes(b'fixture')
            posix='/'+path.drive[0].lower()+path.as_posix()[2:]
            result=self.shell("qwen_is_link() { return 0; }; stat() { echo UNSAFE >&2; echo 7; }; qwen_file_bytes '"+posix+"' 7")
        self.assertNotIn('UNSAFE',result.stderr)
        self.assertNotEqual(result.returncode,0)

    def test_existing_native_receipt_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary); (folder/'native.receipt').write_bytes(b'OLD_RECEIPT')
            posix='/'+folder.drive[0].lower()+folder.as_posix()[2:]
            result=self.shell("directory='"+posix+"'; native_identity='81 9001 81 70 sh'; native_code=0; wait_confirmed=1; term_interrupted=0; qwen_native_receipt")
            self.assertEqual((folder/'native.receipt').read_bytes(),b'OLD_RECEIPT')
        self.assertNotEqual(result.returncode,0)

    def test_native_and_guardian_stage_receipts_include_same_snapshot(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary); posix='/'+folder.drive[0].lower()+folder.as_posix()[2:]
            body="directory='"+posix+"'; stage=sample; source_directory='"+SOURCE+"'; checkpoint_path='"+posix+"/checkpoint.qwenlatent'; tokens=32; seed=5; budget=3900; native_identity='81 9001 81 70 "+posix+"/image_smoke'; guardian_identity='90 8000 90 90 sh'; native_code=0; wait_confirmed=1; term_interrupted=0; timeout_code=0; stop_reason=finished; cleanup=not_needed; qwen_native_receipt; qwen_guardian_receipt"
            result=self.shell(body)
            self.assertEqual(result.returncode,0,result.stderr)
            for name in ('native.receipt','guardian.receipt'):
                text=(folder/name).read_text()
                self.assertIn('stage=sample\n',text)
                self.assertIn('version=2\n',text)
                self.assertIn('budget_seconds=3900\n',text)


if __name__ == '__main__': unittest.main()
