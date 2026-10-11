"""Runs only the explicitly authorized Agnes test on the owned AVD; no key in argv/logs."""
import json
import argparse
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5582"


def adb(*args, data=None, timeout=60):
    return subprocess.run([str(ADB), "-s", SERIAL, *args], input=data,
                          capture_output=True, timeout=timeout, check=True).stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", default="r1")
    parser.add_argument("--mode", choices=("generation", "background_gate"), default="generation")
    args = parser.parse_args()
    if not re.fullmatch(r"r[1-9][0-9]?", args.run_id):
        raise ValueError("Invalid run id")
    if adb("emu", "avd", "name").decode().splitlines()[0] != "zhiyu_local_20261009":
        raise RuntimeError("Unexpected emulator")
    source = Path.home() / "Desktop" / "新建 文本文档.txt"
    raw = source.read_bytes()
    key = raw.decode("utf-8-sig").strip()
    if not 1 <= len(key) <= 4096 or any(not 33 <= ord(c) <= 126 for c in key):
        raise ValueError("Invalid credential file format")
    out = ROOT / "artifacts" / ("native-v4-agnes-cloud-" + args.run_id)
    out.mkdir(exist_ok=False)
    success = False
    try:
        adb("shell", "run-as", "com.zhiyu.app", "sh", "-c",
            "'umask 077; mkdir -p files/zhiyu_agnes_probe; cat > files/zhiyu_agnes_probe/key'",
            data=key.encode())
        key = ""
        with (out / "instrumentation.log").open("xb") as log:
            method = "officialPhoneClientGeneratesOnceReplaysAndDescribesSyntheticOc" if args.mode == "generation" else "checksExistingSyntheticBackgroundWithoutGeneratingAgain"
            result = subprocess.run([str(ADB), "-s", SERIAL, "shell", "am", "instrument", "-w", "-r",
                "-e", "cloud_agnes", "enabled", "-e", "class", "com.zhiyu.app.data.NativeAgnesCloudTest#" + method,
                "com.zhiyu.app.test/androidx.test.runner.AndroidJUnitRunner"], stdout=log,
                stderr=subprocess.STDOUT, timeout=1000, check=False)
        log = (out / "instrumentation.log").read_bytes()
        success = result.returncode == 0 and b"OK (1 test)" in log and b"shortMsg=" not in log and b"FAILURES" not in log
        shape = subprocess.run([str(ADB), "-s", SERIAL, "exec-out", "run-as", "com.zhiyu.app", "cat",
            "files/zhiyu_agnes_probe/response-shape.json"], capture_output=True, check=False)
        if shape.returncode == 0:
            (out / "response-shape.json").write_bytes(shape.stdout)
        if success:
            for name in ("result.json", "synthetic-oc.png", "synthetic-background.png"):
                (out / name).write_bytes(adb("exec-out", "run-as", "com.zhiyu.app", "cat", "files/zhiyu_agnes_probe/" + name))
    finally:
        adb("shell", "am", "force-stop", "com.zhiyu.app")
        adb("shell", "run-as", "com.zhiyu.app", "rm", "-f", "files/zhiyu_agnes_probe/key")
        absent = subprocess.run([str(ADB), "-s", SERIAL, "shell", "run-as", "com.zhiyu.app", "test", "!", "-e",
            "files/zhiyu_agnes_probe/key"], capture_output=True, check=False).returncode == 0
        proof = {"success": success, "source_unchanged": source.read_bytes() == raw,
                 "transient_key_removed": absent, "key_in_arguments": False, "key_digest_recorded": False,
                 "private_user_media": False, "serial": SERIAL}
        (out / "credential-proof.json").write_text(json.dumps(proof, indent=2), "utf-8")
        if not absent or not proof["source_unchanged"]:
            raise RuntimeError("Credential cleanup verification failed")
    print(json.dumps({"success": success, "report": str(out)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
