"""Synthetic contracts only; never ADB, Android process groups or a real model."""
import importlib.util
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('qwen_device_runner', SCRIPTS/'qwen_device_vae_runner.py')
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)
SHELL = SCRIPTS.parent/'native/qwen-edge/diffusion/device_vae_runner.sh'
BASH = Path('C:/msys64/usr/bin/bash.exe')
OWNED = '/data/local/tmp/zhiyu_qwen_research_test_r3'
NATIVE = '81 9001 81 70 '+OWNED+'/image_smoke'


def receipt(kind, **overrides):
    fields = {'version':'1', 'kind':kind, 'directory':OWNED, 'complete':'1'}
    if kind == 'native':
        fields.update(native_pid='81', native_start='9001', native_pgid='81', native_session='70',
            argv0=OWNED+'/image_smoke', native_wait_code='0', wait_confirmed='1', term_interrupted='0')
    else:
        fields.update(guard_pid='90', guard_start='8000', guard_pgid='90', guard_session='90',
            timeout_wait_code='0', budget_seconds='900', stop_reason='finished', cleanup='not_needed')
    fields.update(overrides)
    return ''.join(f'{key}={value}\n' for key,value in fields.items())


class DeviceRunnerContractTest(unittest.TestCase):
    def shell(self, body):
        source = '/'+SHELL.drive[0].lower()+SHELL.as_posix()[2:]
        return subprocess.run([str(BASH), '--noprofile', '--norc', '-c',
            'set -- __library; . "$QWEN_TEST_SHELL"; '+body],
            env={**os.environ, 'QWEN_TEST_SHELL':source, 'MSYS2_ARG_CONV_EXCL':'*',
                 'PATH':'C:/msys64/usr/bin;'+os.environ.get('PATH','')},
            capture_output=True, text=True, timeout=10)

    def test_launch_is_detached_bounded_and_quotes_fixed_paths(self):
        command = RUNNER.launch_command(OWNED, OWNED+'/vae.safetensors', 'tile256', 'sine', 900)
        self.assertIn('nohup setsid -w /system/bin/sh', command)
        self.assertIn('supervise', command)
        self.assertIn('900', command)
        self.assertIn('</dev/null', command)
        self.assertIn('set -C', command)

    def test_invalid_paths_options_and_timeout_are_rejected(self):
        for bad in ('/tmp/test', OWNED+'/../elsewhere', OWNED+';touch /tmp/x', OWNED+'-x'):
            with self.assertRaises(ValueError): RUNNER.launch_command(bad, OWNED+'/vae.safetensors', 'full', 'zero', 1)
        for budget in (0, 901, -1, 1.5, True):
            with self.assertRaises(ValueError): RUNNER.launch_command(OWNED, OWNED+'/vae.safetensors','full','zero',budget)
        with self.assertRaises(ValueError): RUNNER.launch_command(OWNED,'/data/local/tmp/vae.safetensors','full','zero',1)

    def test_success_receipts_do_not_claim_png_or_android_guard_validation(self):
        result = RUNNER.parse_run(receipt('guardian'), receipt('native'))
        self.assertTrue(result['execution_success'])
        self.assertFalse(result['png_verified'])
        self.assertEqual(result['native_exit_code'],0)

    def test_timeout_preserves_separate_native_wait(self):
        result = RUNNER.parse_run(receipt('guardian',timeout_wait_code='124'),receipt('native',native_wait_code='143'))
        self.assertFalse(result['execution_success'])
        self.assertEqual(result['native_exit_code'],143)
        self.assertEqual(result['state'],'timeout')

    def test_interrupted_wait_does_not_become_confirmed_native_exit(self):
        result = RUNNER.parse_run(receipt('guardian',timeout_wait_code='124'),
            receipt('native',native_wait_code='143',wait_confirmed='0',term_interrupted='1'))
        self.assertIsNone(result['native_exit_code'])
        self.assertFalse(result['execution_success'])

    def test_kill_without_native_receipt_remains_unknown(self):
        result = RUNNER.parse_run(receipt('guardian',timeout_wait_code='137',cleanup='unconfirmed'),None)
        self.assertIsNone(result['native_exit_code'])
        self.assertEqual(result['state'],'timeout')

    def test_receipt_truncation_duplicate_or_mixed_directory_is_rejected(self):
        for bad in (receipt('native').replace('complete=1\n',''),receipt('native')+'native_wait_code=0\n'):
            with self.assertRaises(ValueError): RUNNER.parse_receipt(bad,'native')
        with self.assertRaises(ValueError): RUNNER.parse_run(receipt('guardian'),receipt('native',directory=OWNED+'2'))

    def test_unknown_or_changed_identity_never_signals(self):
        for body in ("qwen_identity() { return 1; };", "qwen_identity() { printf '%s\\n' '82 9001 81 70 "+OWNED+"/image_smoke'; };"):
            result = self.shell(body+"kill() { echo UNSAFE; }; qwen_stop_native '"+NATIVE+"';")
            self.assertNotIn('UNSAFE',result.stdout)
            self.assertNotEqual(result.returncode,0)

    def test_all_identity_fields_are_fenced(self):
        fields = NATIVE.split()
        for index in range(5):
            changed = fields.copy(); changed[index] = ('999' if index<4 else OWNED+'/foreign')
            result = self.shell("qwen_match_identity '"+NATIVE+"' '"+' '.join(changed)+"'")
            self.assertNotEqual(result.returncode,0)

    def test_exact_term_then_kill_and_no_group_signal(self):
        body = "qwen_identity() { printf '%s\\n' '"+NATIVE+"'; }; sleep() { :; }; kill() { printf 'SIGNAL %s %s\\n' \"$1\" \"$2\"; }; qwen_stop_native '"+NATIVE+"';"
        result = self.shell(body)
        self.assertIn('SIGNAL -TERM 81',result.stdout)
        self.assertIn('SIGNAL -KILL 81',result.stdout)
        self.assertNotIn('SIGNAL -TERM -',result.stdout)

    def test_unknown_after_term_refuses_kill_escalation(self):
        body = "qwen_identity() { printf '%s\\n' '"+NATIVE+"'; }; qwen_wait_gone() { return 1; }; kill() { printf 'SIGNAL %s %s\\n' \"$1\" \"$2\"; }; qwen_stop_native '"+NATIVE+"';"
        result = self.shell(body)
        self.assertIn('SIGNAL -TERM 81',result.stdout)
        self.assertNotIn('-KILL',result.stdout)
        self.assertNotEqual(result.returncode,0)

    def test_missing_or_invalid_guard_readings_fail_closed(self):
        result = self.shell("qwen_stop_reason '' 0 999999; qwen_stop_reason 43.9 '' 999999; qwen_stop_reason 43.9 0 ''; qwen_stop_reason NaN 0 999999")
        self.assertEqual(result.stdout.splitlines(),['readings_unknown']*4)

    def test_owned_directory_shell_rejects_traversal_and_metacharacters(self):
        for path in ('/tmp/foo',OWNED+'/..',OWNED+'-x',OWNED+';bad'):
            result = self.shell("qwen_owned_directory '"+path+"'")
            self.assertNotEqual(result.returncode,0)

    def test_stop_requested_before_start_prevents_native_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary); (path/'stop.request').write_text('readings_unknown\n')
            posix = '/'+path.drive[0].lower()+path.as_posix()[2:]
            result = self.shell("qwen_validate_inputs() { :; }; qwen_worker_isolated() { :; }; qwen_metrics() { echo safe; }; qwen_worker '"+posix+"' fake full zero")
        self.assertEqual(result.returncode,76,result.stderr)
        self.assertNotIn('WORKER_IDENTITY',result.stdout)

    def test_guard_stop_request_precedes_native_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary); (path/'native.identity').write_text(NATIVE+'\n')
            posix = '/'+path.drive[0].lower()+path.as_posix()[2:]
            result = self.shell("directory='"+posix+"'; stop_reason=readings_unknown; qwen_cleanup() { cat \"$directory/stop.request\"; }; qwen_request_stop")
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout.strip(),'readings_unknown')

    def test_cancellation_before_monitor_is_not_reset(self):
        body = "stop_reason=guardian_signal; cleanup=not_needed; timeout_pid=81; kill() { return 1; }; qwen_request_stop() { cleanup=confirmed; echo STOP_REQUESTED; }; qwen_monitor; printf '%s %s\\n' \"$stop_reason\" \"$cleanup\""
        result = self.shell(body)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout.splitlines(),['STOP_REQUESTED','guardian_signal confirmed'])

    def test_thresholds_and_current_hal_ignore_stale_cache(self):
        thermal = 'Thermal Status: 0\nCached temperatures:\nTemperature{mValue=49, mType=2, mName=battery, mStatus=0}\nCurrent temperatures from HAL:\nTemperature{mValue=43.9, mType=2, mName=battery, mStatus=0}\nCurrent cooling devices from HAL:\n'
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)/'thermal'; path.write_text(thermal)
            posix = '/'+path.drive[0].lower()+path.as_posix()[2:]
            result = self.shell("qwen_hal_battery < '"+posix+"'; qwen_stop_reason 44 0 393216; qwen_stop_reason 43.9 3 393216; qwen_stop_reason 43.9 0 393215; qwen_stop_reason 43.9 0 393216")
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout.splitlines(),['43.9','battery_44c','thermal_severe','memory_low','safe'])


if __name__ == '__main__': unittest.main()
