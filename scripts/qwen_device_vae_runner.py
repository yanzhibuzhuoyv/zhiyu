"""Research-only detached VAE launch/receipts; no ADB or model execution here."""
from __future__ import annotations

import re
import shlex

OWNED_DIRECTORY = re.compile(r"/data/local/tmp/zhiyu_qwen_research_[A-Za-z0-9_]+\Z")
SCRIPT_NAME = "vae_guardian.sh"
IDENTITY_FIELDS = {
    "native": ("native_pid", "native_start", "native_pgid", "native_session"),
    "guardian": ("guard_pid", "guard_start", "guard_pgid", "guard_session"),
}


def _owned(path: str) -> str:
    if not isinstance(path, str) or not OWNED_DIRECTORY.fullmatch(path):
        raise ValueError("directory must be a fresh owned Qwen research directory")
    return path


def launch_command(directory: str, vae: str, mode: str, pattern: str, budget: int) -> str:
    """Return a shell command; caller uploads fixed files and runs it once."""
    _owned(directory)
    if not isinstance(vae, str) or not vae.endswith("/vae.safetensors"):
        raise ValueError("VAE must use the fixed source filename")
    _owned(vae.removesuffix("/vae.safetensors"))
    if mode not in ("full", "tile256") or pattern not in ("zero", "sine"):
        raise ValueError("unsupported research VAE mode or pattern")
    if type(budget) is not int or not 1 <= budget <= 900:
        raise ValueError("budget must be an integer from 1 through 900 seconds")
    arguments = (directory + "/" + SCRIPT_NAME, "supervise", directory, vae,
                 mode, pattern, str(budget))
    args = " ".join(shlex.quote(value) for value in arguments)
    log = shlex.quote(directory + "/launch.log")
    return (f"set -C; test ! -e {log} && test ! -L {log} || exit 73; "
            f"nohup setsid -w /system/bin/sh {args} >{log} 2>&1 </dev/null & "
            "printf 'ZHIYU_GUARD_LAUNCH_PID:%s\\n' \"$!\"")


def _integer(fields: dict[str, str], key: str, low: int, high: int) -> int:
    value = fields.get(key, "")
    if not value.isascii() or not value.isdecimal() or not low <= int(value) <= high:
        raise ValueError(f"invalid receipt field {key}")
    return int(value)


def parse_receipt(text: str, kind: str) -> dict[str, str]:
    """Validate a complete, unique-key receipt; reject transport/truncation text."""
    if kind not in IDENTITY_FIELDS or not isinstance(text, str) or len(text) > 4096:
        raise ValueError("invalid receipt kind or size")
    fields: dict[str, str] = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if not separator or not key or key in fields or not value:
            raise ValueError("malformed or duplicate receipt field")
        fields[key] = value
    if fields.get("version") != "1" or fields.get("kind") != kind or fields.get("complete") != "1":
        raise ValueError("receipt is incomplete or has a different kind/version")
    _owned(fields.get("directory", ""))
    for key in IDENTITY_FIELDS[kind]:
        _integer(fields, key, 2 if key.endswith("pid") else 1, 2**63 - 1)
    if kind == "native":
        if fields.get("argv0") != fields["directory"] + "/image_smoke":
            raise ValueError("native executable is outside the owned directory")
        _integer(fields, "native_wait_code", 0, 255)
        _integer(fields, "wait_confirmed", 0, 1)
        _integer(fields, "term_interrupted", 0, 1)
    else:
        _integer(fields, "timeout_wait_code", 0, 255)
        _integer(fields, "budget_seconds", 1, 900)
        if fields.get("cleanup") not in ("not_needed", "confirmed", "unconfirmed"):
            raise ValueError("invalid cleanup receipt")
        if fields.get("stop_reason") not in (
            "finished", "battery_44c", "thermal_severe", "memory_low", "readings_unknown",
            "identity_unknown", "guardian_signal", "startup_failed", "timeout",
        ):
            raise ValueError("invalid stop reason")
    return fields


def parse_run(guardian_text: str, native_text: str | None) -> dict[str, object]:
    """Native wait and timeout status remain separate; PNG validation is external."""
    guardian = parse_receipt(guardian_text, "guardian")
    native = parse_receipt(native_text, "native") if native_text is not None else None
    if native and native["directory"] != guardian["directory"]:
        raise ValueError("receipts refer to different jobs")
    timeout_code = int(guardian["timeout_wait_code"])
    native_code = int(native["native_wait_code"]) if native and native["wait_confirmed"] == "1" else None
    state = "timeout" if timeout_code in (124, 137) else guardian["stop_reason"]
    success = (state == "finished" and timeout_code == 0 and native_code == 0
               and guardian["cleanup"] == "not_needed")
    return {"state": state, "guard_stop_reason": guardian["stop_reason"], "execution_success": success, "png_verified": False,
            "native_exit_code": native_code, "native_exit_provenance": "direct_shell_wait" if native_code is not None else "unconfirmed",
            "timeout_wait_code": timeout_code, "cleanup": guardian["cleanup"],
            "directory": guardian["directory"]}
