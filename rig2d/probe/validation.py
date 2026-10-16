"""Resource/input limits for a desktop diagnostic probe, not quality thresholds."""
from __future__ import annotations

import math
from typing import Any

MAX_INPUT_BYTES = 32 * 1024 * 1024
MAX_INPUT_PIXELS = 32 * 1024 * 1024
MAX_SOURCE_RAYS = 4 * 1024 * 1024


def validate_parameters(args: Any) -> None:
    for name, minimum, maximum in (
        ("resolution", 32, 512), ("grid", 4, 256), ("texture", 16, 1024),
        ("supersample", 1, 4), ("dilate", 0, 8),
    ):
        value = getattr(args, name)
        if not isinstance(value, int) or isinstance(value, bool) or not minimum <= value <= maximum:
            raise ValueError(f"--{name} 必须为 {minimum}..{maximum} 的整数（探针资源上限）")
    for name, minimum, maximum in (("proxy_exponent", 1., 16.), ("proxy_cover", 0.25, 2.)):
        value = getattr(args, name)
        if not math.isfinite(value) or not minimum <= value <= maximum:
            raise ValueError(f"--{name.replace('_', '-')} 必须为 {minimum}..{maximum} 的有限数")


def validate_source_rays(width: int, height: int, supersample: int) -> None:
    if width * height * supersample * supersample > MAX_SOURCE_RAYS:
        raise ValueError("源图 × supersample² 超过 4194304 条射线资源上限；请降低 --supersample")
