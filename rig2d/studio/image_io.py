"""Bounded image decoding and canonical work-pixel provenance."""
import hashlib
import io
import struct
import warnings

import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

MAX_SOURCE_BYTES = 20 * 1024 * 1024
MAX_DECODE_PIXELS = 40_000_000
MAX_SIDE = 1536


def canonical(image):
    if not isinstance(image, Image.Image):
        raise ValueError("INVALID_IMAGE")
    width, height = image.size
    if min(width, height) < 2 or max(width, height) > MAX_SIDE:
        raise ValueError("WORK_IMAGE_BOUNDS")
    rgba = np.array(image.convert("RGBA"), dtype=np.uint8, copy=True)
    rgba[rgba[:, :, 3] == 0, :3] = 0
    return Image.fromarray(rgba)


def decode_source(raw):
    if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_SOURCE_BYTES:
        raise ValueError("INVALID_OR_LARGE_SOURCE")
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(raw)) as opened:
                width, height = opened.size
                if min(width, height) < 2 or width * height > MAX_DECODE_PIXELS:
                    raise ValueError("DECODE_IMAGE_BOUNDS")
                if opened.format not in ("PNG", "JPEG", "WEBP") or getattr(opened, "n_frames", 1) != 1:
                    raise ValueError("UNSUPPORTED_IMAGE")
                image = ImageOps.exif_transpose(opened).convert("RGBA")
                image.thumbnail((MAX_SIDE, MAX_SIDE), Image.Resampling.LANCZOS)
                return canonical(image)
    except (OSError, UnidentifiedImageError, Image.DecompressionBombError,
            Image.DecompressionBombWarning, SyntaxError) as error:
        raise ValueError("INVALID_IMAGE") from error


def png_bytes(image):
    stream = io.BytesIO()
    image.save(stream, format="PNG", compress_level=6)
    return stream.getvalue()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pixel_sha(image):
    return sha(b"RigPlanarRgba-v1\0" + struct.pack(">II", *image.size) + image.tobytes())
