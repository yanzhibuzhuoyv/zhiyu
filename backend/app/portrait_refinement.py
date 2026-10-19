"""User-directed OC mask edits preserve disconnected wings, props and effects.

This pixel-only operation neither predicts anatomy nor calls the subject model.
The owner-selected original stays intact; every edit produces another preview.
"""

import io
import math
from dataclasses import dataclass

from PIL import Image, ImageChops, ImageDraw

from .media_images import normalize_image
from .portrait_subject import PortraitExtractionError, PortraitSubject

MAX_STROKES = 128
MAX_POINTS_PER_STROKE = 1024
MIN_RADIUS = 0.002
MAX_RADIUS = 0.15


@dataclass(frozen=True)
class _Stroke:
    action: str
    points: tuple[tuple[float, float], ...]
    radius: float


def _number(value, lower, upper):
    return type(value) in (int, float) and lower <= value <= upper and math.isfinite(value)


def _strokes(values):
    if type(values) is not list or len(values) > MAX_STROKES:
        raise PortraitExtractionError("笔刷操作必须是列表，一次最多 128 条")
    checked = []
    for item in values:
        if type(item) is not dict or set(item) != {"action", "points", "radius"}:
            raise PortraitExtractionError("笔刷操作字段无效，请重新绘制")
        action, points, radius = item["action"], item["points"], item["radius"]
        if type(action) is not str or action not in {"keep", "remove"}:
            raise PortraitExtractionError("笔刷操作必须选择保留或擦除")
        if not _number(radius, MIN_RADIUS, MAX_RADIUS):
            raise PortraitExtractionError("笔刷半径必须在图片短边的 0.2% 到 15% 之间")
        if type(points) is not list or not 1 <= len(points) <= MAX_POINTS_PER_STROKE:
            raise PortraitExtractionError("每条笔刷需要 1 到 1024 个连续点")
        converted = []
        for point in points:
            if type(point) not in (list, tuple) or len(point) != 2:
                raise PortraitExtractionError("笔刷坐标必须是横纵两个数值")
            if not all(_number(value, 0, 1) for value in point):
                raise PortraitExtractionError("笔刷坐标必须是 0 到 1 之间的有限数值")
            converted.append((float(point[0]), float(point[1])))
        checked.append(_Stroke(action, tuple(converted), float(radius)))
    return tuple(checked)


def _decode(raw):
    safe = normalize_image(raw)
    with Image.open(io.BytesIO(safe.data)) as image:
        if getattr(image, "n_frames", 1) != 1:
            raise PortraitExtractionError("人物修整需要静态图片，请另选一张静态图")
        return safe, image.convert("RGBA")


def _bbox(values, original, subject):
    valid = type(values) in (list, tuple) and len(values) == 4
    if not valid or any(type(value) is not int for value in values):
        raise PortraitExtractionError("人物预览范围必须是四个整数像素坐标")
    left, top, right, bottom = values
    if not 0 <= left < right <= original.width or not 0 <= top < bottom <= original.height:
        raise PortraitExtractionError("人物预览范围超出完整原图")
    if subject.size != (right - left, bottom - top):
        raise PortraitExtractionError("人物预览尺寸与原图范围不一致，请重新读取预览")
    return (left, top, right, bottom)


def _paint(alpha, strokes):
    width, height = alpha.size
    draw = ImageDraw.Draw(alpha)
    for stroke in strokes:
        value = 255 if stroke.action == "keep" else 0
        radius = max(1, round(stroke.radius * min(width, height)))
        points = [(round(x * (width - 1)), round(y * (height - 1))) for x, y in stroke.points]
        if len(points) > 1:
            draw.line(points, fill=value, width=2 * radius + 1, joint="curve")
        for x, y in (points[0], points[-1]):
            draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=value)


def refine_subject(original_raw: bytes, subject_raw: bytes, bbox: tuple,
                   strokes: list[dict]) -> PortraitSubject:
    checked = _strokes(strokes)
    original, pixels = _decode(original_raw)
    _, subject = _decode(subject_raw)
    selected = _bbox(bbox, pixels, subject)
    alpha = Image.new("L", pixels.size, 0)
    alpha.paste(subject.getchannel("A"), selected[:2])
    _paint(alpha, checked)
    # Cap by source alpha rather than multiply it again on every manual edit.
    alpha = ImageChops.darker(alpha, pixels.getchannel("A"))
    visible = alpha.getbbox()
    if visible is None:
        raise PortraitExtractionError("修整后没有可见主体，请保留人物或撤回擦除操作")
    pixels.putalpha(alpha)
    output = io.BytesIO()
    # The union includes every disconnected visible pixel, including faint effects.
    pixels.crop(visible).save(output, "PNG", optimize=True)
    return PortraitSubject(original, normalize_image(output.getvalue()), visible, "manual_refinement")
