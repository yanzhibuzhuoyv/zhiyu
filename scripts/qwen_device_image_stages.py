"""Typed research-only stage commands/receipts. Never runs ADB or a model."""
from __future__ import annotations

import shlex

try:
    from scripts import qwen_device_vae_runner as guard
except ModuleNotFoundError:
    import qwen_device_vae_runner as guard

SCRIPT_NAME = "image_guardian.sh"
CHECKPOINT_NAME = "checkpoint.qwenlatent"
STAGE_FIELDS = {"stage", "source_directory", "checkpoint_path", "tokens", "seed", "budget_seconds"}


def _number(value: int, low: int, high: int) -> int:
    if type(value) is not int or not low <= value <= high:
        raise ValueError("stage integer is outside the supported range")
    return value


def _launch(directory: str, arguments: tuple[str, ...]) -> str:
    log = shlex.quote(directory + "/launch.log")
    args = " ".join(shlex.quote(value) for value in (directory + "/" + SCRIPT_NAME, "supervise", *arguments))
    return (f"set -C; test ! -e {log} && test ! -L {log} || exit 73; "
            f"nohup setsid -w /system/bin/sh {args} >{log} 2>&1 </dev/null & "
            "printf 'ZHIYU_GUARD_LAUNCH_PID:%s\\n' \"$!\"")


def launch_sample(directory: str, source_directory: str, tokens: int, seed: int, budget: int = 3900) -> str:
    """Models/conditions in read-only source; checkpoint in fresh job directory."""
    guard._owned(directory); guard._owned(source_directory)
    if directory == source_directory:
        raise ValueError("model source must not be the output job")
    _number(tokens, 1, 498); _number(seed, 0, 2147483647); _number(budget, 1, 3900)
    return _launch(directory, ("sample", directory, source_directory, str(tokens), str(seed), str(budget)))


def launch_decode(directory: str, source_directory: str, checkpoint_directory: str,
                  mode: str, budget: int = 900) -> str:
    guard._owned(directory); guard._owned(source_directory); guard._owned(checkpoint_directory)
    if directory in (source_directory, checkpoint_directory):
        raise ValueError("decode output must be separate from read-only inputs")
    if mode not in ("full", "tile256"):
        raise ValueError("unsupported checkpoint decoder mode")
    _number(budget, 1, 900)
    return _launch(directory, ("decode", directory, source_directory, checkpoint_directory, mode, str(budget)))


def _stage_fields(fields: dict[str, str]) -> set[str]:
    stage = fields.get("stage")
    if stage not in ("sample", "decode"):
        raise ValueError("unsupported receipt stage")
    directory = guard._owned(fields.get("directory", ""))
    source = guard._owned(fields.get("source_directory", ""))
    if directory == source:
        raise ValueError("model source aliases output")
    checkpoint = fields.get("checkpoint_path", "")
    if not checkpoint.endswith("/" + CHECKPOINT_NAME):
        raise ValueError("invalid checkpoint filename")
    checkpoint_dir = guard._owned(checkpoint.removesuffix("/" + CHECKPOINT_NAME))
    required = set(STAGE_FIELDS)
    if stage == "sample":
        if checkpoint_dir != directory:
            raise ValueError("sample checkpoint is outside its output job")
        guard._integer(fields, "tokens", 1, 498)
        guard._integer(fields, "seed", 0, 2147483647)
        guard._integer(fields, "budget_seconds", 1, 3900)
    else:
        if checkpoint_dir == directory or fields.get("tokens") != "from_checkpoint" or fields.get("seed") != "from_checkpoint":
            raise ValueError("decoder cannot self-attest checkpoint header metadata")
        if fields.get("decode_mode") not in ("full", "tile256"):
            raise ValueError("invalid decoder mode")
        required.add("decode_mode")
        guard._integer(fields, "budget_seconds", 1, 900)
    return required


def _kind_fields(fields: dict[str, str], kind: str) -> set[str]:
    required = set(guard.IDENTITY_FIELDS[kind])
    for key in guard.IDENTITY_FIELDS[kind]:
        guard._integer(fields, key, 2 if key.endswith("pid") else 1, 2**63 - 1)
    if kind == "native":
        required.update(("argv0", "native_wait_code", "wait_confirmed", "term_interrupted"))
        if fields.get("argv0") != fields["directory"] + "/image_smoke":
            raise ValueError("native executable is outside the job")
        guard._integer(fields, "native_wait_code", 0, 255)
        guard._integer(fields, "wait_confirmed", 0, 1)
        guard._integer(fields, "term_interrupted", 0, 1)
    else:
        required.update(("timeout_wait_code", "stop_reason", "cleanup"))
        guard._integer(fields, "timeout_wait_code", 0, 255)
        if fields.get("cleanup") not in ("not_needed", "confirmed", "unconfirmed") or fields.get("stop_reason") not in (
            "finished", "battery_44c", "thermal_severe", "memory_low", "readings_unknown",
            "identity_unknown", "guardian_signal", "startup_failed", "timeout",
        ):
            raise ValueError("invalid guardian outcome")
    return required


def parse_receipt(text: str, kind: str) -> dict[str, str]:
    fields = guard._receipt_fields(text)
    if kind not in guard.IDENTITY_FIELDS or fields.get("kind") != kind or fields.get("version") != "2" or fields.get("complete") != "1":
        raise ValueError("incomplete stage receipt or wrong kind/version")
    required = {"version", "kind", "directory", "complete", *_stage_fields(fields), *_kind_fields(fields, kind)}
    if set(fields) != required:
        raise ValueError("unknown or missing stage receipt fields")
    return fields


def parse_run(guardian_text: str, native_text: str | None) -> dict[str, object]:
    guardian = parse_receipt(guardian_text, "guardian")
    native = parse_receipt(native_text, "native") if native_text is not None else None
    snapshot = {"directory", *STAGE_FIELDS, *({"decode_mode"} if guardian["stage"] == "decode" else set())}
    if native and any(guardian[field] != native.get(field) for field in snapshot):
        raise ValueError("stage receipts refer to different requests")
    result = guard._run_result(guardian, native)
    return {**result, "stage": guardian["stage"], "source_directory": guardian["source_directory"],
            "checkpoint_path": guardian["checkpoint_path"], "budget_seconds": int(guardian["budget_seconds"]),
            "tokens": int(guardian["tokens"]) if guardian["stage"] == "sample" else None,
            "seed": int(guardian["seed"]) if guardian["stage"] == "sample" else None,
            "decode_mode": guardian.get("decode_mode"), "latent_verified": False, "png_verified": False}
