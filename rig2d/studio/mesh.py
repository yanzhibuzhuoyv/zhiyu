"""Precompute the only vertex deltas consumed by browser and JVM blending."""
import math
import numpy as np


def padded_envelope(distance, inner):
    return 1. if distance <= inner else max(0., (1 - distance) / (1 - inner))


def local_feature_delta(role, x, y, bounds):
    x0, y0, width, height = bounds
    dx, dy = x - x0 - width / 2, y - y0 - height / 2
    envelope = max(0., 1 - abs(2 * dx / width)) * max(0., 1 - abs(2 * dy / height))
    if role in ("eye_left", "eye_right"):
        envelope = padded_envelope(abs(2 * dx / width), .8) * padded_envelope(abs(2 * dy / height), .6)
        return [0., -dy * .90 * envelope]
    return [dx * .10 * envelope, dy * .45 * envelope]


def mesh(bounds, role, anchors, pivot, strength, image_size):
    x0, y0, width, height = bounds
    minimum = 7 if role in ("eye_left", "eye_right", "mouth") else 3
    columns = min(17, max(minimum, math.ceil(width / 48) + 1))
    rows = min(17, max(minimum, math.ceil(height / 48) + 1))
    vertices, uv, indices = [], [], []
    for row in range(rows):
        for col in range(columns):
            u, v = col / (columns - 1), row / (rows - 1)
            vertices.extend([x0 + u * width, y0 + v * height])
            uv.extend([u, v])
    for row in range(rows - 1):
        for col in range(columns - 1):
            i = row * columns + col
            indices.extend([i, i + 1, i + columns, i + 1, i + columns + 1, i + columns])
    deltas = {key: [] for key in ("headX", "headY", "tilt", "blinkLeft", "blinkRight", "mouth", "breath", "sway")}
    head_x, head_y = anchors["head"]
    neck_y = anchors["neck"][1]
    image_width, image_height = image_size
    for x, y in zip(vertices[::2], vertices[1::2]):
        evaluated_y = y0 + height / 2 if role in ("eye_left", "eye_right", "mouth") else y
        transition = max(1., neck_y - head_y, max(image_width, image_height) * .20)
        head_weight = max(0., min(1., (neck_y - evaluated_y) / transition))
        move = {key: [0., 0.] for key in deltas}
        move["headX"] = [image_width * .022 * head_weight, 0.]
        move["headY"] = [0., image_height * .013 * head_weight]
        angle = .045 * head_weight
        move["tilt"] = [-(y - head_y) * angle, (x - head_x) * angle]
        if role in ("eye_left", "eye_right"):
            key = "blinkLeft" if role == "eye_left" else "blinkRight"
            move[key] = local_feature_delta(role, x, y, bounds)
        if role == "mouth":
            move["mouth"] = local_feature_delta(role, x, y, bounds)
        move["breath"] = [0., -(image_height - y) * .004 * (1 - head_weight * .6)]
        if role in ("hair", "accessory"):
            scale = .06 * strength
            move["sway"] = [-(y - pivot[1]) * scale, (x - pivot[0]) * scale]
        for key in deltas:
            deltas[key].extend(move[key])
    return {"vertices": np.asarray(vertices, dtype=np.float32).tolist(),
            "uv": np.asarray(uv, dtype=np.float32).tolist(), "indices": indices,
            "deltas": {key: np.asarray(values, dtype=np.float32).tolist()
                       for key, values in deltas.items()}}
