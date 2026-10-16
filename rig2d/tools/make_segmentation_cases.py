"""Prepare local segmentation inputs, without models or network requests.

Only image.png plus prompts.json (or prompts.full.json) are model inputs.
Ground-truth masks are evaluation-only. Synthetic prompts use oracle anchors
to simulate ideal human hints; they do not measure automatic OC localization.
All binary masks are top-left, row-major uint8 values 0/1. Coordinates are XY
pixels; boxes contain inclusive extrema. Source files are preserved verbatim.
"""

import argparse
import hashlib
import importlib.util
import io
import json
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image


SIZE = 512
DOC_URL = "https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut"
ASTRONAUT_SHA256 = "88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5"
KINDS = {"accessory", "body", "back_hair", "face", "front_hair"}
CLAIMS = {"model_inference": False, "automatic_localization": False,
          "real_oc": False, "user_media": False, "live2d": False}


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def png_bytes(array):
    output = io.BytesIO()
    Image.fromarray(array).save(output, format="PNG", compress_level=9)
    return output.getvalue()


def safe_relative(root, relative):
    if not isinstance(relative, str) or not relative:
        raise ValueError("payload path must be a nonempty relative string")
    path = Path(relative)
    if path.is_absolute() or path.drive or ".." in path.parts:
        raise ValueError("payload path must stay inside the fixture")
    target = (root / path).resolve()
    if not target.is_relative_to(root.resolve()):
        raise ValueError("payload path escapes the fixture")
    return target


def checked_payload(root, metadata, relative, size=None):
    path = safe_relative(root, relative)
    manifest = metadata.get("files")
    if not isinstance(manifest, dict):
        raise ValueError("fixture payload manifest must be an object")
    entry = manifest.get(relative)
    if not isinstance(entry, dict) or path.stat().st_size > 4 * 1024 * 1024:
        raise ValueError("missing manifest entry or oversized fixture payload")
    raw = path.read_bytes()
    if size is not None and len(raw) != size:
        raise ValueError("fixture payload size mismatch")
    if entry != {"size": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}:
        raise ValueError("fixture payload SHA-256 mismatch")
    return raw


def load_fixture(root):
    path = root / "metadata.json"
    if path.stat().st_size > 4 * 1024 * 1024:
        raise ValueError("oversized fixture metadata")
    metadata = json.loads(path.read_text("utf-8"))
    if (not isinstance(metadata, dict) or
            metadata.get("schema") != "rig2d.synthetic-part-fixture.v1" or
            metadata.get("width") != 256 or metadata.get("height") != 256 or
            metadata.get("source_encoding") != "little-endian-uint32-0xAARRGGBB" or
            metadata.get("alpha_encoding") != "straight"):
        raise ValueError("expected the bounded 256x256 straight-alpha synthetic fixture")
    raw = checked_payload(root, metadata, metadata.get("source"), 256 * 256 * 4)
    argb = np.frombuffer(raw, dtype="<u4").reshape(256, 256)
    rgba = np.stack(((argb >> 16) & 255, (argb >> 8) & 255,
                     argb & 255, argb >> 24), axis=-1).astype(np.uint8)
    preview = checked_payload(root, metadata, metadata.get("preview"))
    with Image.open(io.BytesIO(preview)) as image:
        if image.size != (256, 256) or not np.array_equal(np.array(image.convert("RGBA")), rgba):
            raise ValueError("fixture preview differs from original ARGB pixels")
    items = metadata.get("ground_truth_instances")
    if not isinstance(items, list) or not 1 <= len(items) < 62:
        raise ValueError("fixture requires 1..61 ground-truth instances plus the subject region")
    masks, owners = {}, np.zeros((256, 256), dtype=np.uint8)
    for index, item in enumerate(items, 1):
        if not isinstance(item, dict):
            raise ValueError("ground-truth instance must be an object")
        ident, kind = item.get("id"), item.get("kind")
        if not isinstance(ident, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", ident):
            raise ValueError("invalid instance id")
        if ident == "subject" or ident in masks or not isinstance(kind, str) or kind not in KINDS:
            raise ValueError("reserved/duplicate instance id or unsupported kind")
        mask = np.frombuffer(checked_payload(root, metadata, item.get("mask"), 256 * 256), np.uint8)
        mask = mask.reshape(256, 256)
        if np.any(mask > 1) or not np.any(mask) or np.any(mask & (owners > 0)):
            raise ValueError("ground truth must contain nonempty exclusive binary visible instances")
        if np.any(mask & (rgba[:, :, 3] == 0)):
            raise ValueError("instance ground truth extends outside alpha foreground")
        masks[ident] = {"kind": kind, "mask": mask}
        owners[mask > 0] = index
    if sum(name.startswith("wing_") for name in masks) != 12:
        raise ValueError("expected twelve independent synthetic wings")
    owners[(rgba[:, :, 3] > 0) & (owners == 0)] = len(items) + 1
    return rgba, masks, owners, raw, preview


def repeat2(array):
    return np.repeat(np.repeat(array, 2, axis=0), 2, axis=1)


def complex_background():
    """Integer stripes, tile contrast, gradients and deterministic hash noise."""
    y, x = np.indices((SIZE, SIZE), dtype=np.uint32)
    noise = ((x * 1103515245 + y * 12345 + 20261013) ^ (x * y * 2654435761)) % 47
    stripe = (((x + 2 * y) // 23) % 2) * 45
    tile = (((x // 71) + (y // 59)) % 2) * 33
    return np.stack((92 + (x * 63 // SIZE) + noise + stripe,
                     105 + (y * 58 // SIZE) + noise + tile,
                     123 + ((x + y) * 35 // (2 * SIZE)) + noise), axis=-1).astype(np.uint8)


def line_subject(rgba, owners):
    """Keep one-pixel owner outlines and grayscale-detail contrast >=30."""
    foreground = rgba[:, :, 3] > 0
    luma = (rgba[:, :, :3].astype(np.uint32) * [77, 150, 29]).sum(axis=2) // 256
    edge = np.zeros(foreground.shape, dtype=bool)
    padded_owner = np.pad(owners, 1)
    padded_luma = np.pad(luma.astype(np.int32), 1)
    for dy, dx in ((0, -1), (0, 1), (-1, 0), (1, 0)):
        neighbor = padded_owner[1 + dy:257 + dy, 1 + dx:257 + dx]
        shade = padded_luma[1 + dy:257 + dy, 1 + dx:257 + dx]
        edge |= (owners != neighbor) | (np.abs(luma.astype(np.int32) - shade) >= 30)
    ink = foreground & edge
    shade = (24 + luma // 5).astype(np.uint8)
    return np.stack((shade * ink, shade * ink, shade * ink,
                     rgba[:, :, 3] * ink), axis=-1).astype(np.uint8)


def oracle_region(ident, kind, mask):
    ys, xs = np.where(mask)
    if not len(xs):
        raise ValueError("cannot prompt an empty target")
    # An anchor nearest the target centroid is guaranteed on the visible mask.
    distance = (xs.astype(float) - xs.mean()) ** 2 + (ys.astype(float) - ys.mean()) ** 2
    index = int(np.argmin(distance))
    negative_y, negative_x = np.argwhere(mask == 0)[0]
    return {"id": ident, "kind": kind,
            "points": [[int(xs[index]), int(ys[index]), 1],
                       [int(negative_x), int(negative_y), 0]],
            "box": [int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())]}


def synthetic_case(name, subject, masks, originals, background):
    foreground = (subject[:, :, 3] > 0).astype(np.uint8)
    alpha = subject[:, :, 3:4].astype(np.uint32)
    image = ((subject[:, :, :3].astype(np.uint32) * alpha +
              background.astype(np.uint32) * (255 - alpha) + 127) // 255).astype(np.uint8)
    files = {"image.png": png_bytes(image), "subject.png": png_bytes(subject),
             "subject.rgba": subject.tobytes(), "background.png": png_bytes(background),
             "foreground.mask": foreground.tobytes(), "source.argb": originals[0],
             "source.png": originals[1]}
    truth, regions = [], [oracle_region("subject", "body", foreground)]
    for ident, item in masks.items():
        mask = repeat2(item["mask"]) & foreground
        path = f"ground_truth/{ident}.mask"
        files[path] = mask.tobytes()
        truth.append({"id": ident, "kind": item["kind"], "mask": path, "pixels": int(mask.sum())})
        regions.append(oracle_region(ident, item["kind"], mask))
    prompt = {"schema": 1, "width": SIZE, "height": SIZE, "regions": regions}
    quick_ids = {"subject", "face", "wing_12", "weapon_01"}
    files["prompts.full.json"] = json_bytes(prompt)
    files["prompts.json"] = json_bytes({**prompt, "regions": [r for r in regions if r["id"] in quick_ids]})
    line = name == "line_oc"
    definition = ("visible ink pixels: alpha>0 after 256px owner boundaries or luma "
                  "4-neighbor contrast>=30, then nearest 2x; not filled silhouette" if line else
                  "all source foreground alpha>0, then exact nearest 2x; includes partial alpha")
    metadata = {"width": SIZE, "height": SIZE, "image": "image.png", "prompts": "prompts.json",
                "full_prompts": "prompts.full.json", "evaluation": "synthetic_oracle_prompted_masks",
                "claims": {**CLAIMS, "synthetic": True},
                "sample_license": {"name": "Project-generated synthetic fixture; no third-party artwork",
                                   "source": "tools/make_part_fixture.py", "redistribution": "No separate license grant"},
                "transform": {"scale_xy": [2, 2], "offset_xy": [0, 0], "resampling": "nearest",
                              "alpha": "straight; integer composite rounding +127 then //255",
                              "line_transform": definition if line else None},
                "prompt_provenance": "oracle anchors and boxes derived from GT simulate ideal manual hints; not automatic localization",
                "ground_truth": {"definition": definition, "foreground": "foreground.mask", "instances": truth,
                                 "unknown_pixels": int(foreground.sum()) - sum(i["pixels"] for i in truth)},
                "foreground_pixels": int(foreground.sum()), "subject_rgba": "subject.rgba"}
    return files, metadata


def local_astronaut():
    """Locate packaged data only. Never invoke skimage's downloading loader."""
    spec = importlib.util.find_spec("skimage")
    return Path(spec.origin).parent / "data" / "astronaut.png" if spec and spec.origin else None


def astronaut_case(path):
    if path.stat().st_size > 4 * 1024 * 1024:
        raise ValueError("oversized astronaut payload")
    raw = path.read_bytes()
    # This is the upstream skimage.data._registry pinned payload identity.
    if hashlib.sha256(raw).hexdigest() != ASTRONAUT_SHA256:
        raise ValueError("local photo is not the pinned public-domain skimage astronaut payload")
    with Image.open(io.BytesIO(raw)) as image:
        if image.format != "PNG" or image.size != (SIZE, SIZE):
            raise ValueError("the packaged astronaut PNG must be 512x512")
    regions = [{"id": "subject", "kind": "body", "points": [[163, 345, 1], [424, 104, 0]],
                "box": [12, 12, 509, 511]},
               {"id": "face", "kind": "face", "points": [[223, 126, 1], [333, 140, 0]],
                "box": [177, 67, 271, 180]}]
    prompt = json_bytes({"schema": 1, "width": SIZE, "height": SIZE, "regions": regions})
    metadata = {"width": SIZE, "height": SIZE, "image": "image.png", "prompts": "prompts.json",
                "full_prompts": "prompts.full.json", "evaluation": "qualitative_only_no_ground_truth",
                "claims": {**CLAIMS, "synthetic": False}, "ground_truth": None,
                "sample_license": {"name": "NASA photograph, public domain (per scikit-image documentation)",
                                   "source_url": DOC_URL, "credit": "NASA / Eileen Collins"},
                "prompt_provenance": "manual image inspection anchors; no pixel GT or localization model",
                "transform": {"scale_xy": [1, 1], "offset_xy": [0, 0], "resampling": "none; original PNG bytes"}}
    return {"image.png": raw, "prompts.json": prompt, "prompts.full.json": prompt}, metadata


def generate(out: Path, fixture: Path, astronaut_path: Path | None = None) -> dict:
    """Validate every input before publishing to a new exclusive output path."""
    out, fixture = Path(out), Path(fixture)
    if out.exists() or out.is_symlink():
        raise FileExistsError(f"output path already exists: {out}")
    rgba, masks, owners, raw, preview = load_fixture(fixture)
    background, payloads, cases, skipped = complex_background(), {}, {}, {}
    photo = Path(astronaut_path) if astronaut_path is not None else local_astronaut()
    if photo is not None and photo.is_file():
        files, cases["astronaut"] = astronaut_case(photo)
        payloads.update({f"astronaut/{path}": data for path, data in files.items()})
    else:
        skipped["astronaut"] = "local_astronaut_unavailable_no_download"
    for name, subject in (("colored_oc", repeat2(rgba)), ("line_oc", repeat2(line_subject(rgba, owners)))):
        files, cases[name] = synthetic_case(name, subject, masks, (raw, preview), background)
        payloads.update({f"{name}/{path}": data for path, data in files.items()})
    metadata = {"schema": "rig2d.segmentation-cases.v1", "claims": {**CLAIMS},
                "max_working_dimension": 1024, "origin": "top-left", "coordinates": "XY pixels",
                "boxes": "inclusive min/max pixel extrema", "mask_encoding": "row-major-uint8-0-or-1",
                "pipeline_inputs": ["image.png", "prompts.json or prompts.full.json"],
                "ground_truth_usage": "evaluation only; never provide masks as model input",
                "cases": cases, "skipped": skipped,
                "files": {path: {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                          for path, data in payloads.items()}}
    out.mkdir(parents=True, exist_ok=False)
    for relative, data in payloads.items():
        target = out / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (out / "metadata.json").write_bytes(json_bytes(metadata))
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--astronaut", type=Path, help="optional local packaged NASA astronaut PNG; never downloaded")
    args = parser.parse_args()
    try:
        metadata = generate(args.out, args.fixture, args.astronaut)
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"segmentation cases error: {error}", file=sys.stderr)
        return 2
    print(json.dumps({"out": str(args.out.resolve()), "cases": list(metadata["cases"]),
                      "skipped": metadata["skipped"], "model_inference": False}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
