"""Explicit isolated emulator probe: secrets only enter the app via private stdin."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from threading import Event
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
ADB = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5582"
EXPECTED_AVD = "zhiyu_local_20261009"


def adb(*arguments, data=None, timeout=60):
    return subprocess.run([str(ADB), "-s", SERIAL, *arguments], input=data,
                          capture_output=True,
                          check=True, timeout=timeout).stdout


def credentials(path):
    raw = path.read_bytes()
    entries = {}
    for line in raw.decode("utf-8-sig").splitlines():
        match = re.fullmatch(r"\s*(RAG_LLM_API_KEY|RAG_LLM_BASE_URL)\s*=\s*(.*?)\s*", line)
        if match:
            entries[match[1]] = match[2].strip().strip("\"'")
    key = entries.get("RAG_LLM_API_KEY", "")
    parsed = urlsplit(entries.get("RAG_LLM_BASE_URL", ""))
    if (parsed.scheme != "https" or parsed.netloc != "api.deepseek.com"
            or parsed.path not in {"", "/", "/v1", "/v1/"} or parsed.query or parsed.fragment
            or not 1 <= len(key) <= 4096 or any(not 33 <= ord(c) <= 126 for c in key)):
        raise ValueError("Authorized official credential configuration is invalid")
    return key, hashlib.sha256(raw).hexdigest()


def instrument(test_class, output, timeout=3600):
    arguments = [str(ADB), "-s", SERIAL, "shell", "am", "instrument", "-w", "-r",
                 "-e", "class", test_class,
                 "com.zhiyu.app.test/androidx.test.runner.AndroidJUnitRunner"]
    with output.open("xb") as stream:
        completed = subprocess.run(arguments, stdout=stream, stderr=subprocess.STDOUT,
                                   timeout=timeout, check=False)
    result = output.read_bytes()
    final = result.rsplit(b"INSTRUMENTATION_RESULT: stream=", 1)[-1]
    terminated = re.search(rb"\nINSTRUMENTATION_CODE: -1\s*\Z", result)
    failed = any(marker in result for marker in
                 (b"FAILURES", b"shortMsg=", b"INSTRUMENTATION_FAILED:", b"INSTRUMENTATION_ABORTED:"))
    if (completed.returncode or b"INSTRUMENTATION_RESULT: stream=" not in result
            or not re.search(rb"(?:^|\n)OK \(1 test\)\s*(?:\nINSTRUMENTATION_CODE: -1\s*)?\Z", final)
            or not terminated or failed):
        raise RuntimeError("Native official probe failed; inspect its sanitized instrumentation log")


def ensure_emulator():
    if adb("emu", "avd", "name").decode().splitlines()[0] != EXPECTED_AVD:
        raise RuntimeError("Unexpected emulator: refusing credential or cleanup operations")


def execute_probe(key, output, fifty_million, resume_fifty_million, on_stopped=lambda: None):
    ensure_emulator()
    try:
        adb("shell", "run-as", "com.zhiyu.app", "sh", "-c",
            "'umask 077; mkdir -p files/zhiyu_native_probe; cat > files/zhiyu_native_probe/key'",
            data=key.encode())
        adb("shell", "run-as", "com.zhiyu.app", "sh", "-c",
            "'umask 077; cat > files/zhiyu_native_probe/model'", data=b"deepseek-flash")
        key = ""
        test_class = ("com.zhiyu.app.data.FiftyMillionResumeTest" if resume_fifty_million
                      else "com.zhiyu.app.data.FiftyMillionMemoryTest" if fifty_million
                      else "com.zhiyu.app.data.NativeOfficialAiTest")
        instrument(test_class, output,
                   timeout=43200 if fifty_million else 3600)
    except BaseException:
        ensure_emulator()
        for package in ("com.zhiyu.app.test", "com.zhiyu.app"):
            adb("shell", "am", "force-stop", package)
        if adb("shell", "sh", "-c", "'pidof com.zhiyu.app com.zhiyu.app.test || true'").strip():
            raise RuntimeError("Owned probe processes did not stop; cleanup is not verified") from None
        on_stopped()
        raise


def cleanup_proof(path, before, output, stopped_after_failure):
    ensure_emulator()
    adb("shell", "run-as", "com.zhiyu.app", "rm", "-f",
        "files/zhiyu_native_probe/key", "files/zhiyu_native_probe/model")
    removed = not adb("shell", "run-as", "com.zhiyu.app", "ls", "files/zhiyu_native_probe").strip()
    unchanged = hashlib.sha256(path.read_bytes()).hexdigest() == before
    output.with_name(output.stem + "-credential-proof.json").write_text(
        json.dumps({"source_sha256": before, "source_unchanged": unchanged,
                    "private_transient_files_removed": removed, "serial": SERIAL,
                    "isolated_probe_stopped_after_failure": stopped_after_failure,
                    "avd": EXPECTED_AVD, "key_in_arguments": False}, indent=2), "utf-8")
    if not unchanged:
        raise RuntimeError("Credential source changed during the probe")
    if not removed:
        raise RuntimeError("Transient credential cleanup did not complete")


def report_path(name, fifty_million):
    selected = name or ("native-fifty-million.log" if fifty_million else "native-official-ai.log")
    if not re.fullmatch(r"native-[a-z0-9-]{1,80}\.log", selected):
        raise ValueError("Report must be a native log basename inside artifacts")
    output = ROOT / "artifacts" / selected
    if output.exists():
        raise FileExistsError("Probe report already exists; preserve prior evidence before another run")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fifty-million", action="store_true")
    parser.add_argument("--resume-fifty-million", action="store_true")
    parser.add_argument("--report", help="New native-*.log basename; earlier reports are preserved")
    options = parser.parse_args()
    if options.fifty_million and options.resume_fifty_million:
        raise ValueError("Choose either a fresh or resumed five-million probe")
    if options.resume_fifty_million:
        options.fifty_million = True
    ensure_emulator()
    if adb("reverse", "--list").strip():
        raise RuntimeError("Remove test-emulator PC mappings before this direct-only probe")
    output = report_path(options.report, options.fifty_million)
    path = ROOT.parent / "rag-system/.env"
    key, before = credentials(path)
    stopped = Event()
    try:
        execute_probe(key, output, options.fifty_million, options.resume_fifty_million, stopped.set)
    finally:
        key = ""
        cleanup_proof(path, before, output, stopped.is_set())
    print("Native official HTTPS probe passed; source unchanged; transient credentials removed.")


if __name__ == "__main__":
    main()
