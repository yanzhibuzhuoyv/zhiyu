"""Source-bound planar layers; never declares automatic identity verification."""
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from .definition import default_definition, name as valid_name, validate_definition
from .image_io import canonical, decode_source, pixel_sha, png_bytes, sha
from .mesh import mesh

CONTRACT = json.loads((Path(__file__).parent / "contract.json").read_text(encoding="utf-8"))


def _rect_mask(rect, shape):
    x0, y0, x1, y1 = rect
    mask = np.zeros(shape, dtype=bool)
    mask[int(y0):int(np.ceil(y1)), int(x0):int(np.ceil(x1))] = True
    return mask


def _mask_bounds(mask):
    ys, xs = np.flatnonzero(mask.any(axis=1)), np.flatnonzero(mask.any(axis=0))
    if not len(xs):
        return None
    x0, y0, x1, y1 = int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1
    return [x0, y0, x1 - x0, y1 - y0]


def _texture_budget(base, planes):
    masks = [base[:, :, 3] > 0, *(plane[4] for plane in planes)]
    bounds = [_mask_bounds(mask) for mask in masks]
    total = sum(value[2] * value[3] for value in bounds if value is not None)
    if total > CONTRACT["maxTexturePixels"] or total * 4 > CONTRACT["maxTextureBytes"]:
        raise ValueError("TEXTURE_RESOURCE_BUDGET")


def _layer(ident, label, role, z, pixels, anchors, pivot, strength, files):
    bounds = _mask_bounds(pixels[:, :, 3] > 0)
    if bounds is None:
        return None
    x0, y0, width, height = bounds
    x1, y1 = x0 + width, y0 + height
    texture = png_bytes(Image.fromarray(pixels[y0:y1, x0:x1]))
    digest = sha(texture)
    path = f"textures/{digest}.png"
    files[path] = texture
    return {"id": ident, "name": label, "role": role, "z": z,
            "texture": path, "textureSha": digest, "bounds": bounds,
            "phase": (int(sha(ident.encode())[:8], 16) % 6283) / 1000,
            "strength": strength,
            **mesh(bounds, role, anchors, pivot, strength, (pixels.shape[1], pixels.shape[0]))}


def _planes(pixels, definition, masks, foreground):
    anchors = definition["anchors"]
    assigned = np.zeros(foreground.shape, dtype=bool)
    planes, patches = [], np.zeros(foreground.shape, dtype=bool)
    for ident, key, role in (("eye_left", "eyeLeft", "eye_left"),
                             ("eye_right", "eyeRight", "eye_right"), ("mouth", "mouth", "mouth")):
        selected = _rect_mask(anchors[key], foreground.shape) & foreground & ~assigned
        if selected.any():
            assigned |= selected
            patches |= selected & (pixels[:, :, 3] == 255)
            planes.append((ident, ident, role, 100, selected, anchors["head"], 1.))
    for part, mask in sorted(zip(definition["parts"], masks), key=lambda pair: (pair[0]["z"], pair[0]["id"]), reverse=True):
        selected = mask & foreground & ~assigned
        assigned |= selected
        if selected.any():
            planes.append((part["id"], part["name"], part["role"], part["z"], selected,
                           part["pivot"], part["strength"]))
    base = pixels.copy()
    base[assigned] = 0
    if patches.any():
        repaired = cv2.inpaint(pixels[:, :, :3], patches.astype(np.uint8) * 255, 3, cv2.INPAINT_TELEA)
        base[patches, :3] = repaired[patches]
        base[patches, 3] = 255
    base[~foreground] = 0
    return planes, base, bool(patches.any())


def _verify_neutral(image, layers, files, expected):
    canvas = Image.new("RGBA", image.size)
    import io
    for layer in sorted(layers, key=lambda item: (item["z"], item["id"])):
        texture = Image.open(io.BytesIO(files[layer["texture"]])).convert("RGBA")
        canvas.alpha_composite(texture, (layer["bounds"][0], layer["bounds"][1]))
    if not np.array_equal(np.asarray(canvas), expected):
        raise ValueError("NEUTRAL_SOURCE_MISMATCH")


def build_asset(image, definition, name):
    image = canonical(image)
    name = valid_name(name)
    width, height = image.size
    definition, masks, subject = validate_definition(definition, width, height)
    pixels = np.array(image, copy=True)
    foreground = subject & (pixels[:, :, 3] > 0)
    if not foreground.any():
        raise ValueError("EMPTY_SUBJECT")
    planes, base, repaired = _planes(pixels, definition, masks, foreground)
    _texture_budget(base, planes)
    files = {"source.png": png_bytes(image)}
    layers = []
    base_layer = _layer("base", "保留原图", "base", -1001, base,
                        definition["anchors"], definition["anchors"]["neck"], 1., files)
    if base_layer:
        layers.append(base_layer)
    for ident, label, role, z, selected, pivot, strength in planes:
        part = np.zeros_like(pixels)
        part[selected] = pixels[selected]
        layer = _layer(ident, label, role, z, part, definition["anchors"], pivot, strength, files)
        if layer:
            layers.append(layer)
    expected = pixels.copy()
    expected[~foreground] = 0
    _verify_neutral(image, layers, files, expected)
    asset = {"format": CONTRACT["format"], "mode": "front_mesh", "width": width,
             "height": height, "name": name, "sourceSha": sha(files["source.png"]),
             "pixelSha": pixel_sha(image), "layers": sorted(layers, key=lambda layer: (layer["z"], layer["id"])),
             "parameters": CONTRACT["parameters"], "capabilities": {
                 "trueSideViews": False, "cubismMoc3": False, "identityVerified": False,
                 "reviewRequired": True, "neutralSourceExact": True,
                 "sourceAlphaPreserved": "subjectMask" not in definition,
                 "hiddenPatchGenerated": repaired, "generator": "planar-v1"}}
    return {"asset": asset, "files": files, "definition": definition}
