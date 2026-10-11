"""Probe guards use synthetic credentials and fake subprocesses only."""
import importlib.util
import subprocess
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import pytest

SOURCE = Path(__file__).parents[1] / "native_emulator_cloud_probe.py"
SPEC = importlib.util.spec_from_file_location("native_emulator_probe", SOURCE)
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


def test_instrument_streams_and_keeps_failed_output(tmp_path):
    output = tmp_path / "failed.log"

    def run(arguments, **options):
        assert "synthetic-secret" not in arguments
        assert options["stderr"] == subprocess.STDOUT
        options["stdout"].write(b"INSTRUMENTATION_STATUS: stream=progress\nFAILURES!!!\n")
        options["stdout"].flush()
        assert output.read_bytes().startswith(b"INSTRUMENTATION_STATUS")
        return SimpleNamespace(returncode=0)

    with patch.object(probe.subprocess, "run", side_effect=run), pytest.raises(RuntimeError, match="sanitized"):
        probe.instrument("synthetic.Test", output)
    assert b"FAILURES" in output.read_bytes()


def test_instrument_retains_timeout_and_does_not_overwrite(tmp_path):
    output = tmp_path / "timeout.log"

    def run(arguments, **options):
        options["stdout"].write(b"safe progress\n")
        raise subprocess.TimeoutExpired(arguments, 3600)

    with patch.object(probe.subprocess, "run", side_effect=run), pytest.raises(subprocess.TimeoutExpired):
        probe.instrument("synthetic.Test", output)
    assert output.read_bytes() == b"safe progress\n"
    with patch.object(probe.subprocess, "run") as command:
        with pytest.raises(FileExistsError):
            probe.instrument("synthetic.Test", output)
        command.assert_not_called()


def test_instrument_accepts_only_runner_success(tmp_path):
    output = tmp_path / "passed.log"

    def run(_arguments, **options):
        options["stdout"].write(b"INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
        return SimpleNamespace(returncode=0)

    with patch.object(probe.subprocess, "run", side_effect=run):
        probe.instrument("synthetic.Test", output)
    assert output.read_bytes().endswith(b"INSTRUMENTATION_CODE: -1\n")


def test_progress_success_text_cannot_hide_a_crashed_instrumentation(tmp_path):
    def run(_arguments, **options):
        options["stdout"].write(b"INSTRUMENTATION_STATUS: stream=OK (1 test)\n"
                               b"INSTRUMENTATION_FAILED: Process crashed.\nINSTRUMENTATION_CODE: 0\n")
        return SimpleNamespace(returncode=0)
    with patch.object(probe.subprocess, "run", side_effect=run), pytest.raises(RuntimeError):
        probe.instrument("synthetic.Test", tmp_path / "false-success.log")


def test_user_interrupt_stops_the_owned_runner_and_verifies_it_is_gone(tmp_path):
    calls = []
    stopped = []
    def adb(*arguments, **_options):
        calls.append(arguments)
        return (probe.EXPECTED_AVD + "\nOK\n").encode() if arguments == ("emu", "avd", "name") else b""
    with (patch.object(probe, "adb", side_effect=adb),
          patch.object(probe, "instrument", side_effect=KeyboardInterrupt), pytest.raises(KeyboardInterrupt)):
        probe.execute_probe("synthetic-secret", tmp_path / "interrupt.log", False, lambda: stopped.append(True))
    assert stopped == [True]
    assert ("shell", "am", "force-stop", "com.zhiyu.app.test") in calls
    assert ("shell", "am", "force-stop", "com.zhiyu.app") in calls
    assert any("pidof" in str(arguments) for arguments in calls)


@pytest.mark.parametrize("endpoint", ["http://api.deepseek.com", "https://api.deepseek.com.evil/v1", "https://api.deepseek.com/v1?relay=1"])
def test_credentials_reject_nonofficial_endpoint(tmp_path, endpoint):
    source = tmp_path / "synthetic.env"
    source.write_text(f"RAG_LLM_API_KEY=synthetic-secret\nRAG_LLM_BASE_URL={endpoint}\n", "utf-8")
    with pytest.raises(ValueError, match="configuration"):
        probe.credentials(source)


def test_timed_out_probe_stops_only_the_verified_emulator(tmp_path):
    calls = []

    def adb(*arguments, **_options):
        calls.append(arguments)
        return (probe.EXPECTED_AVD + "\nOK\n").encode() if arguments == ("emu", "avd", "name") else b""

    with patch.object(probe, "adb", side_effect=adb), patch.object(probe, "instrument",
            side_effect=subprocess.TimeoutExpired(["synthetic"], 43200)) as command:
        with pytest.raises(subprocess.TimeoutExpired):
            probe.execute_probe("synthetic-secret", tmp_path / "probe.log", True)
        assert command.call_args.kwargs["timeout"] == 43200
    assert ("shell", "am", "force-stop", "com.zhiyu.app.test") in calls
    assert ("shell", "am", "force-stop", "com.zhiyu.app") in calls
    assert all("synthetic-secret" not in arguments for arguments in calls)


def test_changed_emulator_prevents_force_stop_and_cleanup(tmp_path):
    calls = []

    def adb(*arguments, **_options):
        calls.append(arguments)
        return b"some-other-emulator\nOK\n"

    with patch.object(probe, "adb", side_effect=adb), patch.object(probe, "instrument",
            side_effect=RuntimeError("sanitized")):
        with pytest.raises(RuntimeError, match="Unexpected emulator"):
            probe.execute_probe("synthetic-secret", tmp_path / "probe.log", False)
        with pytest.raises(RuntimeError, match="Unexpected emulator"):
            probe.cleanup_proof(tmp_path / "not-read.env", "not-used", tmp_path / "probe.log", True)
    assert not any("force-stop" in arguments or "rm" in arguments for arguments in calls)
    assert not any("run-as" in arguments for arguments in calls)


def test_custom_report_is_bounded_to_new_native_log_inside_artifacts(tmp_path):
    with patch.object(probe, "ROOT", tmp_path):
        assert probe.report_path("native-official-ai-r7.log", False) == tmp_path / "artifacts/native-official-ai-r7.log"
        for unsafe in ("../private.log", "C:/private.log", "native-foo.txt", "native-old-credential-proof.json"):
            with pytest.raises(ValueError):
                probe.report_path(unsafe, False)
        destination = tmp_path / "artifacts/native-official-ai-r7.log"
        destination.parent.mkdir()
        destination.write_text("earlier result", "utf-8")
        with pytest.raises(FileExistsError):
            probe.report_path(destination.name, False)


def test_cleanup_proof_requires_actual_empty_transient_folder(tmp_path):
    source = tmp_path / "synthetic.env"
    source.write_bytes(b"synthetic credential source")
    before = probe.hashlib.sha256(source.read_bytes()).hexdigest()

    def adb(*arguments, **_options):
        if arguments == ("emu", "avd", "name"):
            return (probe.EXPECTED_AVD + "\nOK\n").encode()
        return b"key\n" if "ls" in arguments else b""

    with patch.object(probe, "adb", side_effect=adb), pytest.raises(RuntimeError, match="cleanup did not complete"):
        probe.cleanup_proof(source, before, tmp_path / "probe.log", True)
    proof = probe.json.loads((tmp_path / "probe-credential-proof.json").read_text("utf-8"))
    assert proof["source_unchanged"]
    assert not proof["private_transient_files_removed"]
