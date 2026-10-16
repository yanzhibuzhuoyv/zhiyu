"""Validates user-edited anchors and masks before any texture generation."""
import base64
import binascii
import copy
import io
import math
import re

import cv2
import numpy as np
from PIL import Image, ImageDraw

ROLES = ("base", "face", "eye_left", "eye_right", "mouth", "hair", "body", "accessory")
RESERVED = {"base", "eye_left", "eye_right", "mouth"}
SAFE_ID = re.compile(r"[A-Za-z0-9_-]{1,80}")


def number(value, low, high):
    if type(value) not in (int, float) or not low <= value <= high or not math.isfinite(value):
        raise ValueError("INVALID_NUMBER")
    return value


def name(value):
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= 80 or any(ord(c) < 32 for c in value):
        raise ValueError("INVALID_NAME")
    return value.strip()


def point(value, width, height):
    if not isinstance(value, list) or len(value) != 2:
        raise ValueError("INVALID_POINT")
    return [number(value[0], 0, width - 1), number(value[1], 0, height - 1)]


def rectangle(value, width, height):
    if not isinstance(value, list) or len(value) != 4:
        raise ValueError("INVALID_RECTANGLE")
    x0, y0 = point(value[:2], width, height)
    x1, y1 = number(value[2], 0, width), number(value[3], 0, height)
    if x1 - x0 < 1 or y1 - y0 < 1:
        raise ValueError("EMPTY_RECTANGLE")
    return [x0, y0, x1, y1]


def decode_b64(value, limit):
    if not isinstance(value, str) or len(value) > (limit + 2) // 3 * 4:
        raise ValueError("INVALID_OR_LARGE_MASK")
    try:
        data = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError("INVALID_MASK_BASE64") from error
    if len(data) > limit:
        raise ValueError("INVALID_OR_LARGE_MASK")
    return data


def part_mask(part, width, height):
    if "mask" in part:
        raw = decode_b64(part["mask"], 8 * 1024 * 1024)
        try:
            with Image.open(io.BytesIO(raw)) as image:
                if image.format != "PNG" or image.size != (width, height):
                    raise ValueError("INVALID_MASK_IMAGE")
                values = np.asarray(image.convert("L"))
                if not np.isin(values, (0, 255)).all():
                    raise ValueError("MASK_NOT_BINARY")
                return values > 0
        except OSError as error:
            raise ValueError("INVALID_MASK_IMAGE") from error
    polygon = part.get("polygon")
    if not isinstance(polygon, list) or not 3 <= len(polygon) <= 256:
        raise ValueError("INVALID_POLYGON")
    checked = [tuple(point(item, width, height)) for item in polygon]
    mask = Image.new("L", (width, height))
    ImageDraw.Draw(mask).polygon(checked, fill=255)
    return np.asarray(mask) > 0


def validate_definition(value, width, height):
    if not isinstance(value, dict) or set(value) - {"anchors", "parts", "subjectMask"}:
        raise ValueError("INVALID_DEFINITION")
    anchors = value.get("anchors")
    if not isinstance(anchors, dict) or set(anchors) != {"head", "neck", "eyeLeft", "eyeRight", "mouth"}:
        raise ValueError("INVALID_ANCHORS")
    for key, item in anchors.items():
        (point if key in ("head", "neck") else rectangle)(item, width, height)
    parts = value.get("parts", [])
    if not isinstance(parts, list) or len(parts) > 60:
        raise ValueError("PART_BUDGET")
    used, masks = set(RESERVED), []
    for part in parts:
        allowed = {"id", "name", "role", "z", "polygon", "mask", "pivot", "strength"}
        if not isinstance(part, dict) or set(part) - allowed:
            raise ValueError("INVALID_PART")
        ident = part.get("id")
        if not isinstance(ident, str) or not SAFE_ID.fullmatch(ident) or ident in used:
            raise ValueError("INVALID_OR_DUPLICATE_PART_ID")
        used.add(ident)
        name(part.get("name"))
        if part.get("role") not in ROLES or part["role"] == "base":
            raise ValueError("INVALID_PART_ROLE")
        number(part.get("z"), -1000, 90)
        number(part.get("strength"), 0, 1)
        point(part.get("pivot"), width, height)
        masks.append(part_mask(part, width, height))
    subject = np.ones((height, width), dtype=bool)
    if "subjectMask" in value:
        raw = decode_b64(value["subjectMask"], width * height)
        if len(raw) != width * height or any(byte > 1 for byte in raw):
            raise ValueError("INVALID_SUBJECT_MASK")
        subject = np.frombuffer(raw, dtype=np.uint8).reshape(height, width) > 0
    return copy.deepcopy({**value, "parts": parts}), masks, subject


def default_definition(image):
    width, height = image.size
    x, y, side = width * .25, height * .10, min(width * .5, height * .5)
    gray = cv2.cvtColor(np.asarray(image.convert("RGB")), cv2.COLOR_RGB2GRAY)
    detector = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    if not detector.empty() and min(width, height) >= 40:
        scale = min(1., 512 / max(width, height))
        small = cv2.resize(gray, None, fx=scale, fy=scale)
        faces = detector.detectMultiScale(small, 1.15, 5, minSize=(20, 20))
        if len(faces):
            fx, fy, fw, fh = max(faces, key=lambda box: box[2] * box[3])
            x, y, side = float(fx) / scale, float(fy) / scale, float(max(fw, fh)) / scale
    def rect(cx, cy, rw, rh):
        rw, rh = max(1., rw), max(1., rh)
        x0 = max(0., min(width - rw, cx - rw / 2))
        y0 = max(0., min(height - rh, cy - rh / 2))
        return [int(x0), int(y0), min(width, math.ceil(x0 + rw)), min(height, math.ceil(y0 + rh))]
    return {"anchors": {"head": [min(width - 1, x + side / 2), min(height - 1, y + side / 2)],
                        "neck": [min(width - 1, x + side / 2), min(height - 1, y + side * 1.12)],
                        "eyeLeft": rect(x + side * .30, y + side * .42, side * .22, side * .14),
                        "eyeRight": rect(x + side * .70, y + side * .42, side * .22, side * .14),
                        "mouth": rect(x + side * .50, y + side * .76, side * .28, side * .12)}, "parts": []}
