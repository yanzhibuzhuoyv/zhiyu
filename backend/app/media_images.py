"""Decode, bound, and re-encode media before accepting any user/operator image."""

import hashlib
import io
from dataclasses import dataclass

from PIL import Image, ImageOps, UnidentifiedImageError

MAX_FILE_BYTES = 5 * 1024 * 1024
MAX_PIXELS = 20_000_000
MAX_FRAMES = 60
MAX_ANIMATION_PIXELS = 40_000_000


@dataclass(frozen=True)
class SafeImage:
    data: bytes
    mime_type: str
    extension: str
    width: int
    height: int
    sha256: str


def normalize_image(raw: bytes) -> SafeImage:
    if not isinstance(raw, bytes) or not 1 <= len(raw) <= MAX_FILE_BYTES:
        raise ValueError("图片大小必须在 1 字节到 5 MiB 之间")
    try:
        with Image.open(io.BytesIO(raw), formats=("PNG", "JPEG", "WEBP", "GIF")) as image:
            width, height = image.size
            if not 1 <= width <= 8192 or not 1 <= height <= 8192 or width * height > MAX_PIXELS:
                raise ValueError("图片像素尺寸超出上限")
            frames = getattr(image, "n_frames", 1)
            if frames > MAX_FRAMES or width * height * frames > MAX_ANIMATION_PIXELS:
                raise ValueError("图片动画帧数或总像素超出上限")
            output = io.BytesIO()
            if frames > 1:
                copies, durations = [], []
                for index in range(frames):
                    image.seek(index)
                    copy = image.convert("RGBA")
                    copy.info.clear()
                    copies.append(copy)
                    durations.append(min(10000, max(20, image.info.get("duration", 100))))
                copies[0].save(output, "GIF", save_all=True, append_images=copies[1:],
                               duration=durations, loop=0, disposal=2)
                mime, extension = "image/gif", "gif"
            else:
                # A fresh pixel image strips EXIF location, comments and embedded instructions.
                oriented = ImageOps.exif_transpose(image)
                width, height = oriented.size
                pixels = Image.new("RGBA", oriented.size)
                pixels.paste(oriented.convert("RGBA"))
                pixels.save(output, "PNG", optimize=True)
                mime, extension = "image/png", "png"
            data = output.getvalue()
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError, EOFError) as exc:
        raise ValueError("图片格式不受支持或内容损坏") from exc
    if len(data) > MAX_FILE_BYTES:
        raise ValueError("图片规范化后大小超出上限")
    return SafeImage(data, mime, extension, width, height, hashlib.sha256(data).hexdigest())
