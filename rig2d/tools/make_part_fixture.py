"""Generate a deterministic synthetic part-preservation fixture (not a model).

No input image, weights, network, or external library is used. The simulated
subject and candidates deliberately contain omissions and overlap. Pixel files
are row-major, top-left origin; ARGB is straight-alpha, little-endian uint32.
"""

import argparse
import hashlib
import json
import struct
import sys
import zlib
from pathlib import Path


WIDTH = HEIGHT = 256
PIXELS = WIDTH * HEIGHT
CLAIM = "SYNTHETIC_NOT_MODEL_NOT_REAL_OC"
WING_IDS = tuple(f"wing_{number:02d}" for number in range(1, 13))
ROLE_KINDS = {"weapon_01": "accessory", "back_hair": "back_hair",
              "face": "face", "front_hair": "front_hair", "body": "body"}
WING_COLORS = (0xF56565, 0xED8936, 0xECC94B, 0x48BB78, 0x38B2AC, 0x4299E1,
               0x667EEA, 0x9F7AEA, 0xD53F8C, 0xF687B3, 0x76E4F7, 0xB4D455)


class Raster:
    """Tiny integer rasterizer; owners describe visible synthetic pixels only."""

    def __init__(self):
        self.pixels = [0] * PIXELS
        self.owners = [""] * PIXELS

    def put(self, x, y, owner, color, alpha=255):
        if 0 <= x < WIDTH and 0 <= y < HEIGHT:
            index = y * WIDTH + x
            self.pixels[index] = (alpha << 24) | color
            self.owners[index] = owner

    def polygon(self, points, owner, color, alpha=255):
        xs, ys = zip(*points)
        for y in range(max(0, min(ys)), min(HEIGHT - 1, max(ys)) + 1):
            for x in range(max(0, min(xs)), min(WIDTH - 1, max(xs)) + 1):
                crosses = [(x - a[0]) * (b[1] - a[1]) -
                           (y - a[1]) * (b[0] - a[0])
                           for a, b in zip(points, points[1:] + points[:1])]
                if all(value >= 0 for value in crosses) or all(value <= 0 for value in crosses):
                    self.put(x, y, owner, color, alpha)

    def ellipse(self, cx, cy, rx, ry, owner, color, alpha=255):
        for y in range(max(0, cy - ry), min(HEIGHT - 1, cy + ry) + 1):
            for x in range(max(0, cx - rx), min(WIDTH - 1, cx + rx) + 1):
                if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1:
                    self.put(x, y, owner, color, alpha)

    def line(self, start, end, owner, color, radius=0, alpha=255):
        x, y = start
        end_x, end_y = end
        dx, dy = abs(end_x - x), -abs(end_y - y)
        sx, sy = 1 if x < end_x else -1, 1 if y < end_y else -1
        error = dx + dy
        while True:
            for py in range(y - radius, y + radius + 1):
                for px in range(x - radius, x + radius + 1):
                    self.put(px, py, owner, color, alpha)
            if (x, y) == (end_x, end_y):
                break
            twice = error * 2
            if twice >= dy:
                error += dy
                x += sx
            if twice <= dx:
                error += dx
                y += sy


def draw_wings(raster):
    tips = ((23, 37), (9, 72), (13, 105), (14, 140), (28, 175), (47, 205))
    for index, owner in enumerate(WING_IDS):
        tip_x, tip_y = tips[index % 6]
        points = [(112, 144), (tip_x, tip_y), (tip_x + 20, tip_y + 15)]
        if index >= 6:
            points = [(WIDTH - 1 - x, y) for x, y in points]
        color = WING_COLORS[index]
        raster.polygon(points, owner, color)
        tip = points[1]
        feather_end = (tip[0] + (8 if index >= 6 else -8), tip[1] - 6)
        raster.line(tip, feather_end, owner, color, alpha=96 + index * 7)


def draw_body(raster):
    raster.polygon([(110, 123), (146, 123), (161, 201), (146, 221),
                    (109, 221), (96, 201)], "body", 0x243B67)
    raster.polygon([(116, 146), (140, 146), (149, 201), (128, 216),
                    (106, 201)], "body", 0x46669E)
    raster.line((112, 202), (144, 202), "body", 0xFBD38D, radius=1)
    raster.ellipse(128, 142, 6, 6, "body", 0xF6E05E)


def draw_head(raster):
    raster.polygon([(94, 75), (162, 75), (155, 164), (137, 179),
                    (109, 171), (95, 143)], "back_hair", 0x382952)
    raster.ellipse(128, 80, 36, 43, "back_hair", 0x4A356C)
    raster.ellipse(128, 86, 26, 32, "face", 0xFFD4B8)
    for x in (116, 140):
        raster.ellipse(x, 87, 5, 4, "face", 0xF7FAFC)
        raster.ellipse(x, 87, 2, 3, "face", 0x215C64)
    raster.line((124, 102), (133, 102), "face", 0xBB536B)
    raster.ellipse(128, 56, 30, 16, "front_hair", 0x9F7AEA)
    raster.polygon([(100, 56), (131, 54), (117, 81)], "front_hair", 0x805AD5)
    raster.polygon([(126, 53), (156, 57), (146, 79)], "front_hair", 0xB794F4)
    raster.line((104, 61), (90, 29), "front_hair", 0xB794F4)
    raster.line((90, 29), (98, 13), "front_hair", 0xB794F4, alpha=144)
    raster.line((129, 43), (122, 17), "front_hair", 0xD6BCFA)
    raster.line((122, 17), (130, 7), "front_hair", 0xD6BCFA, alpha=64)
    raster.line((149, 61), (165, 25), "front_hair", 0xB794F4)
    raster.line((165, 25), (172, 17), "front_hair", 0xB794F4, alpha=192)


def draw_weapon(raster):
    raster.line((177, 223), (192, 202), "weapon_01", 0x553C7B, radius=2)
    raster.line((190, 204), (233, 153), "weapon_01", 0xC4F1F9, radius=1)
    raster.line((233, 153), (247, 137), "weapon_01", 0x76E4F7, alpha=117)
    raster.line((180, 198), (205, 216), "weapon_01", 0xFBD38D, radius=1)


def render():
    raster = Raster()
    draw_wings(raster)
    draw_body(raster)
    draw_head(raster)
    draw_weapon(raster)
    # A one-pixel, almost-transparent ornament has no simulated part candidate.
    raster.put(84, 224, "unclassified_detail", 0xED64A6, alpha=1)
    return raster


def mask_for(owners, owner):
    return bytes(int(actual == owner) for actual in owners)


def png_chunk(kind, payload):
    return (struct.pack(">I", len(payload)) + kind + payload +
            struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))


def preview_png(pixels):
    rows = []
    for y in range(HEIGHT):
        rgba = bytes(channel for pixel in pixels[y * WIDTH:(y + 1) * WIDTH]
                     for channel in ((pixel >> 16) & 255, (pixel >> 8) & 255,
                                     pixel & 255, pixel >> 24))
        rows.append(b"\0" + rgba)
    header = struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", header) +
            png_chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + png_chunk(b"IEND", b""))


def make_masks(raster):
    truth = {owner: mask_for(raster.owners, owner) for owner in (*WING_IDS, *ROLE_KINDS)}
    foreground = bytes(int(pixel >> 24 != 0) for pixel in raster.pixels)
    subject = bytes(int(fg and owner != "wing_12" and
                        not (owner == "weapon_01" and index % WIDTH >= 238))
                    for index, (fg, owner) in enumerate(zip(foreground, raster.owners)))
    protected = bytes(int(owner in (*WING_IDS, "weapon_01", "front_hair"))
                      for owner in raster.owners)
    candidates = {owner: bytearray(mask) for owner, mask in truth.items() if owner != "wing_12"}
    for owner in WING_IDS[:-1]:
        # Artificial foreground overlap, to exercise preservation under conflict.
        candidates[owner][92 * WIDTH + 128] = 1
        candidates[owner][205 * WIDTH + 128] = 1
    for index in range(PIXELS):
        if index % WIDTH >= 238:
            candidates["weapon_01"][index] = 0
        if index // WIDTH < 25:
            candidates["front_hair"][index] = 0
    return truth, {owner: bytes(mask) for owner, mask in candidates.items()}, foreground, subject, protected


def instance(owner, path, confidence):
    kind = "accessory" if owner in WING_IDS else ROLE_KINDS[owner]
    return {"id": owner, "kind": kind, "mask": path, "confidence": confidence}


def expected_observations(raster, candidates, foreground, subject):
    coverage = [sum(mask[index] for mask in candidates.values()) for index in range(PIXELS)]
    return {"wing_count": 12, "candidate_wing_count": 11, "max_accessory_instances": 64,
            "candidate_instance_count": len(candidates), "foreground_pixels": sum(foreground),
            "omitted_subject_pixels": sum(a and not b for a, b in zip(foreground, subject)),
            "conflict_foreground_pixels": sum(fg and count > 1 for fg, count in zip(foreground, coverage)),
            "unknown_foreground_pixels": sum(fg and count == 0 for fg, count in zip(foreground, coverage)),
            "partial_alpha_pixels": sum(0 < pixel >> 24 < 255 for pixel in raster.pixels)}


def make_payloads():
    raster = render()
    truth, candidates, foreground, subject, protected = make_masks(raster)
    payloads = {"source.argb": struct.pack("<" + "I" * PIXELS, *raster.pixels),
                "foreground.mask": foreground, "subject.mask": subject,
                "protected.mask": protected, "preview.png": preview_png(raster.pixels)}
    truth_instances, instances = [], []
    for owner, mask in truth.items():
        path = f"ground_truth/{owner}.mask"
        payloads[path] = mask
        truth_instances.append(instance(owner, path, 1.0))
    for owner, mask in candidates.items():
        path = f"instances/{owner}.mask"
        payloads[path] = mask
        confidence = round(0.93 - WING_IDS.index(owner) * 0.01, 2) if owner in WING_IDS else 0.91
        instances.append(instance(owner, path, confidence))
    metadata = {"schema": "rig2d.synthetic-part-fixture.v1", "claim": CLAIM,
                "width": WIDTH, "height": HEIGHT, "source": "source.argb",
                "source_encoding": "little-endian-uint32-0xAARRGGBB", "alpha_encoding": "straight",
                "mask_encoding": "row-major-uint8-0-or-1", "origin": "top-left",
                "subject_mask": "subject.mask", "protected_mask": "protected.mask",
                "foreground_mask": "foreground.mask", "preview": "preview.png",
                "instances": instances, "ground_truth_instances": truth_instances,
                "omitted_candidate_ids": ["wing_12"],
                "provenance": {"synthetic": True, "model_inference": False, "real_oc": False},
                "expected": expected_observations(raster, candidates, foreground, subject),
                "files": {path: {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                          for path, data in payloads.items()}}
    return payloads, metadata


def generate(out: Path) -> dict:
    """Write a new fixture directory; an existing directory is never reused."""
    out = Path(out)
    if out.exists() or out.is_symlink():
        raise FileExistsError(f"output path already exists: {out}")
    payloads, metadata = make_payloads()
    out.mkdir(parents=True, exist_ok=False)
    for relative, data in payloads.items():
        target = out / relative
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(data)
    (out / "metadata.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
                                       encoding="utf-8", newline="\n")
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    try:
        metadata = generate(args.out)
    except (OSError, ValueError) as error:
        print(f"part fixture error: {error}", file=sys.stderr)
        return 2
    print(json.dumps({"claim": CLAIM, "out": str(args.out.resolve()),
                      "expected": metadata["expected"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
