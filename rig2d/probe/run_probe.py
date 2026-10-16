"""P0 探针入口 — 参数化 2D 通话形象：多视角一致性可行性探针（计划 §1）。

用法
----
    python probe/run_probe.py                        # 用合成夹具跑（默认，零许可暴露）
    python probe/run_probe.py --image path/to.png    # 用真实角色图跑（**不得取自私聊/用户媒体库**）
    python probe/run_probe.py --out artifacts/rig-probe-2026-10-12

产出（计划 §1.5）
----------------
    <out>/input.png            输入副本（真实图会先去掉可识别元信息并缩放到工作尺寸）
    <out>/input.sha256         输入 SHA256
    <out>/proxy.json           代理参数（半径、对称平面、网格尺寸）
    <out>/views/*.png          front / right45 / right90 / left45 / left90 五视角
    <out>/holes/*.png          每视角的空洞掩码（白 = 有几何无纹理）
    <out>/contact-sheet.png    目视对比图册（P0.6 用）
    <out>/probe.json           回执：每阶段空洞残留表 + 门禁判定 + 许可核查
    <out>/NO_PRODUCT_CLAIM.txt 显式标注

**必须显式标注 `NO_PRODUCT_CLAIM`**（计划 §1.5）：探针不是产品能力验收。
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import sys
import time
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageOps

sys.path.insert(0, str(Path(__file__).resolve().parent))

from fill import patch_match_fill, summarize as summarize_fill  # noqa: E402
from headproxy import build_proxy, hair_shell_mask, license_report  # noqa: E402
from project import (  # noqa: E402
    TextureGrid,
    camera_basis,
    dilate_texture,
    project_source,
    render_view,
    symmetry_mirror,
)
from framing import framing_checks, projection_sample_bbox  # noqa: E402
from inputprofile import (  # noqa: E402
    box_center_world,
    draw_proxy_overlay,
    fit_head_box,
    parse_head_box,
    profile_input,
    radii_from_box,
    validate_head_box,
)
from synthetic import synthetic_character  # noqa: E402
from validation import (  # noqa: E402
    MAX_INPUT_BYTES, MAX_INPUT_PIXELS, validate_parameters, validate_source_rays,
)

WORK_MAX_EDGE = 1024          # 工作副本最长边；沿用 zhiyu 的 ≤1536 / ≤4MP 纪律，探针取更保守的 1024
FIVE_VIEWS = [0.0, 45.0, 90.0, -45.0, -90.0]
VIEW_NAMES = {0.0: "front", 45.0: "right45", 90.0: "right90", -45.0: "left45", -90.0: "left90"}


def sha256_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_input(path: Path | None, fixture: str = "color") -> tuple[np.ndarray, np.ndarray, bytes, dict[str, Any]]:
    """载入输入。返回 (rgb, alpha, png_bytes, meta)。"""
    if path is None:
        if fixture == "line_art":
            from synthetic_line_art import synthetic_line_art
            rgb, alpha, meta = synthetic_line_art()
        else:
            rgb, alpha, meta = synthetic_character()
        buffer = io.BytesIO()
        Image.fromarray((np.dstack([rgb, alpha.astype(np.float64)]) * 255).astype(np.uint8), "RGBA").save(buffer, "PNG")
        return rgb, alpha, buffer.getvalue(), meta

    if path.stat().st_size > MAX_INPUT_BYTES:
        raise ValueError("输入文件超过 32 MiB 的探针资源上限")
    raw = path.read_bytes()
    if len(raw) > MAX_INPUT_BYTES:
        raise ValueError("输入文件超过 32 MiB 的探针资源上限")
    with Image.open(io.BytesIO(raw)) as opened:
        if opened.width * opened.height > MAX_INPUT_PIXELS:
            raise ValueError("输入图像超过 32 MP 的探针解码资源上限")
        if getattr(opened, "n_frames", 1) != 1:
            raise ValueError("探针只支持单帧图像，不接受动画")
        image = ImageOps.exif_transpose(opened).convert("RGBA")
        image.thumbnail((WORK_MAX_EDGE, WORK_MAX_EDGE), Image.LANCZOS)
    image.info.clear()  # 工作副本不保留 EXIF / ICC / 文本等输入元信息
    array = np.asarray(image).astype(np.float64) / 255.0
    rgb = array[..., :3]
    if image.mode == "RGBA" and np.any(array[..., 3] < 1.0):
        alpha = array[..., 3] > 0.5
    else:
        alpha = background_mask(rgb)
    if not alpha.any():
        raise ValueError("图像全透明或没有可分离的角色 foreground")
    buffer = io.BytesIO()
    image.save(buffer, "PNG")
    meta = {
        "label": "REAL_IMAGE",
        "source_name": path.name,
        "source_sha256": sha256_of(raw),
        "working_size": [image.width, image.height],
        "note": "真实角色图；工作副本已缩放到最长边 ≤ 1024 并转为 RGBA。",
    }
    return rgb, alpha, buffer.getvalue(), meta


def background_mask(rgb: np.ndarray) -> np.ndarray:
    """无 alpha 时的背景分离：从四边泛洪填充「接近纯色背景」的区域。

    只用于把背景排除在投影之外；不做任何语义分割（部件的分割是阶段 1 的事）。
    """
    from collections import deque

    h, w, _ = rgb.shape
    corners = np.stack([rgb[0, 0], rgb[0, w - 1], rgb[h - 1, 0], rgb[h - 1, w - 1]])
    background_color = corners.mean(axis=0)
    tolerance = 0.08
    close = np.linalg.norm(rgb - background_color, axis=-1) < tolerance
    visited = np.zeros((h, w), dtype=bool)
    queue: deque[tuple[int, int]] = deque()
    for x in range(w):
        for y in (0, h - 1):
            if close[y, x] and not visited[y, x]:
                visited[y, x] = True
                queue.append((y, x))
    for y in range(h):
        for x in (0, w - 1):
            if close[y, x] and not visited[y, x]:
                visited[y, x] = True
                queue.append((y, x))
    while queue:
        y, x = queue.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and close[ny, nx] and not visited[ny, nx]:
                visited[ny, nx] = True
                queue.append((ny, nx))
    return ~visited


def save_png(path: Path, array: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = np.clip(array * 255.0, 0, 255).astype(np.uint8)
    Image.fromarray(data).save(path)


def view_image(render, show_holes: bool) -> np.ndarray:
    rgb = render.color.copy()
    if show_holes:
        holes = render.alpha & ~render.filled
        rgb[holes] = np.array([1.0, 0.0, 0.35])          # 空洞用洋红标出
        rgb[render.alpha & render.filled] *= 0.55        # 已覆盖区压暗，突出空洞
    elif True:
        outline = render.alpha & ~_erode(render.alpha)
        rgb[outline] = np.array([0.1, 0.1, 0.1])
    rgb[~render.alpha] = 1.0
    return rgb


def _erode(mask: np.ndarray) -> np.ndarray:
    padded = np.pad(mask, 1, mode="constant")
    out = np.ones_like(mask)
    for dr in range(3):
        for dc in range(3):
            out &= padded[dr:dr + mask.shape[0], dc:dc + mask.shape[1]]
    return out


def contact_sheet(renders: list, columns: int = 5) -> np.ndarray:
    """目视对比图册（P0.6）：上排 = 渲染结果，下排 = 空洞掩码。"""
    tiles = [[view_image(r, False) for r in renders], [view_image(r, True) for r in renders]]
    rows = [np.concatenate(row, axis=1) for row in tiles]
    return np.concatenate(rows, axis=0)


def main() -> int:
    parser = argparse.ArgumentParser(description="P0 探针：多视角一致性可行性")
    parser.add_argument("--image", type=Path, default=None, help="真实角色图路径（不得取自私聊/用户媒体库）")
    parser.add_argument("--fixture", choices=["color", "line_art"], default="color",
                        help="合成夹具类型：color（彩图，默认）或 line_art（线稿）。"
                             "线稿路径必须用 line_art 夹具做对照——彩图夹具量出来的数对线稿无意义"
                             "（NEXT_DUAL_INPUT §4.2）。")
    parser.add_argument("--out", type=Path, default=Path("artifacts/rig-probe-2026-10-12"))
    parser.add_argument("--resolution", type=int, default=384)
    parser.add_argument("--grid", type=int, default=96)
    parser.add_argument("--texture", type=int, default=512)
    parser.add_argument("--supersample", type=int, default=2)
    parser.add_argument("--dilate", type=int, default=2, help="纹理采样间距补齐的迭代次数")
    parser.add_argument("--proxy-exponent", type=float, default=4.0,
                        help="代理形状 Lp 指数：2 = 椭球，1 = 多面体（默认 4，正面投影接近圆角矩形，"
                             "能贴合头部轮廓、避免代理形状本身制造假缺口）")
    parser.add_argument("--proxy-cover", type=float, default=1.0,
                        help="代理相对头部包围盒的外扩系数。默认 1.0：head_box 已经是头的范围，"
                             "超椭球的圆角投影本来就能填满它；再外扩会让代理**大于源图的头**，"
                             "渲染轮廓随之超出发帧（自检 silhouette_matches_headbox / head_visible_in_frame "
                             "会当场抓到——1.06 时实测比值 3.65）。")
    parser.add_argument("--head-box", type=parse_head_box, default=None,
                        help="人工指定头部范围 x0,y0,x1,y1（像素）。自动定位失败时使用；"
                             "P0 测的是缺口面积，不是自动检测精度，因此允许人工给几何真值。")
    args = parser.parse_args()
    try:
        validate_parameters(args)
    except ValueError as error:
        parser.error(str(error))

    started = time.time()
    out: Path = args.out
    if out.exists() or out.is_symlink():
        parser.error(f"输出目录已存在，禁止覆盖探针证据：{out}；请使用新的 -rN 目录")

    rgb, alpha, png_bytes, meta = load_input(args.image, args.fixture)
    h, w, _ = rgb.shape
    validate_source_rays(w, h, args.supersample)

    # 相机与几何：把代理椭球拟合到**头部**包围盒（不是整张图）。
    # 世界单位约定：源图 x ∈ [-1, 1] 对应世界 x ∈ [-1, 1]，因此 pixel_scale = 2 / 图宽。
    pixel_scale = 2.0 / max(w, 1)
    profile = profile_input(rgb, alpha)
    head_box = args.head_box if args.head_box is not None else fit_head_box(alpha, rgb, profile)
    validate_head_box(head_box, (w, h))
    # 参数与输入校验成功才独占创建输出目录；旧回执一律不覆盖。
    out.mkdir(parents=True, exist_ok=False)
    meta["working_sha256"] = sha256_of(png_bytes)
    (out / "input.png").write_bytes(png_bytes)
    (out / "input.sha256").write_text(meta["working_sha256"] + "\n", encoding="utf-8")
    # 半径与取景中心都**只从 head_box 派生**（唯一真值来源），并在同一处做单位换算。
    # 第一版把 pixel_scale 漏乘了一半、又把中心 z 的符号写反，两个错都只有 overlay 能看出来。
    cover = args.proxy_cover
    radii = radii_from_box(head_box, pixel_scale, width_k=cover, height_k=cover, depth_k=0.72 * cover)
    # 显式传 width/height（诊断报告 D2：元组顺序曾经被互换）
    head_center_x, head_center_z = box_center_world(head_box, w, h, pixel_scale)

    proxy = build_proxy(columns=args.grid, rows=args.grid, radii=radii, exponent=args.proxy_exponent)
    (out / "proxy.json").write_text(
        json.dumps({**proxy.as_dict(), "head_box_px": list(head_box), "head_center_world": [head_center_x, head_center_z], "pixel_scale": pixel_scale},
                   indent=2, ensure_ascii=False),
        encoding="utf-8",
    )

    texture = project_source(proxy, rgb, alpha, pixel_scale, args.texture, args.texture,
                             supersample=args.supersample, head_center=(head_center_x, head_center_z))
    texture_dilated = dilate_texture(texture, proxy, iterations=args.dilate)

    stages: dict[str, list[dict[str, Any]]] = {"baseline": [], "A_symmetry": [], "B_patchmatch": []}
    provenance_ok = True
    for yaw in FIVE_VIEWS:
        name = VIEW_NAMES[yaw]
        base = render_view(proxy, texture_dilated, yaw, args.resolution, pixel_scale)
        stages["baseline"].append({"view": name, **base.stats()})
        if yaw == 0.0:
            front_render = base

        mirrored = symmetry_mirror(texture_dilated, proxy)
        after_a = render_view(proxy, mirrored, yaw, args.resolution, pixel_scale)
        stages["A_symmetry"].append({"view": name, **after_a.stats()})

        after_b_color = after_a.color.copy()
        after_b_filled = after_a.filled.copy()
        filled_alpha = after_a.alpha.copy()
        holes = int((filled_alpha & ~after_b_filled).sum())
        b_meta: dict[str, Any] = {"view": name, "patch_filled_pixels": 0}
        if holes:
            result = patch_match_fill(filled_alpha, after_b_color, after_b_filled, half=4, search_radius=24, max_chained_distance=3)
            after_b_color, after_b_filled = result.color, result.filled
            b_meta.update(
                patch_filled_pixels=result.filled_pixels,
                rings_used=result.rings_used,
                stopped_reason=result.stopped_reason,
                empty_search_pixels=result.empty_search_pixels,
                max_offset_used=result.max_offset_used,
                chained_distance_limit=result.chained_distance_limit,
            )
            from fill import provenance_is_from_image

            src_pixels = {(int(r), int(c)) for r, c in zip(*np.nonzero(after_a.filled), strict=True)}
            provenance_ok &= provenance_is_from_image(result, src_pixels)
        b_stats = summarize_fill(filled_alpha, after_b_filled)
        stages["B_patchmatch"].append({"view": name, **b_stats, **b_meta})

        save_png(out / "views" / f"{name}.png", view_image(after_a, False))
        save_png(out / "holes" / f"{name}.png", view_image(after_a, True))

    # 目视图册：用 A 级后的结果（B 级主要影响残差，肉眼差异小于 A）
    a_renders = [render_view(proxy, symmetry_mirror(texture_dilated, proxy), yaw, args.resolution, pixel_scale) for yaw in FIVE_VIEWS]
    save_png(out / "contact-sheet.png", contact_sheet(a_renders))
    # P0.6 目视检查：把包围盒与代理椭球画回源图（数字看不出「椭圆有没有套在头上」）
    overlay = draw_proxy_overlay(
        rgb, head_box, (head_center_x, head_center_z),
        (float(radii[0]) / pixel_scale, float(radii[2]) / pixel_scale),
        pixel_scale=pixel_scale,
    )
    save_png(out / "proxy-overlay.png", overlay)

    self_checks = run_self_checks(stages, proxy, texture_dilated, head_box, (w, h))
    framing = framing_checks(
        projection_sample_bbox(head_box, proxy, pixel_scale, (w, h), (head_center_x, head_center_z)),
        head_box,
        front_render,
        args.resolution,
        pixel_scale,
        (w, h),
        args.proxy_exponent,
    )
    self_checks.update(framing)
    hard = {k: v for k, v in self_checks.items() if isinstance(v, dict) and v.get("severity") != "advisory"}
    advisory = {k: v for k, v in self_checks.items() if isinstance(v, dict) and v.get("severity") == "advisory"}
    self_checks["all_passed"] = all(bool(v["passed"]) for v in hard.values())
    self_checks["advisory_passed"] = all(bool(v["passed"]) for v in advisory.values())
    self_checks["hard_check_count"] = len(hard)
    self_checks["advisory_check_count"] = len(advisory)
    verdict = judge(stages, profile.profile, self_checks=self_checks, provenance_ok=provenance_ok)
    receipt = {
        "probe": "rig-probe-2026-10-13",
        "measurement_schema": 2,
        "claim": "NO_PRODUCT_CLAIM",
        "note": "探针不是产品能力验收（计划 §1.5）。结论只能用于「是否继续投入」的门禁判定。",
        "input": meta,
        "input_profile": {**profile.as_dict(), "head_box_source": "manual(--head-box)" if args.head_box else "auto"},
        "geometry": {**proxy.as_dict(), "head_box_px": list(head_box),
                     "head_center_world": [head_center_x, head_center_z], "pixel_scale": pixel_scale, "proxy_exponent": args.proxy_exponent,
                     "proxy_cover": args.proxy_cover},
        "texture": texture.stats(),
        "texture_after_dilate": texture_dilated.stats(),
        "hair_shell_cells": int(hair_shell_mask(proxy).sum()),
        "stages": stages,
        "visual_outputs_stage": "A_symmetry",
        "self_checks": self_checks,
        "projection_sample_bbox_px": [round(float(v), 1) for v in
                                      projection_sample_bbox(head_box, proxy, pixel_scale, (w, h), (head_center_x, head_center_z))],
        "provenance_only_from_image": provenance_ok,
        "gate": verdict,
        "licensing": license_report(),
        "elapsed_seconds": round(time.time() - started, 2),
        "determinism": {"seed": meta.get("seed"), "random_ops": False},
    }
    (out / "probe.json").write_text(json.dumps(receipt, indent=2, ensure_ascii=False, allow_nan=False), encoding="utf-8")
    (out / "NO_PRODUCT_CLAIM.txt").write_text(
        "NO_PRODUCT_CLAIM\n\n"
        "本目录是一次可行性探针的产物，不是产品能力验收。\n"
        "不得据此声明「支持 ±90°」「补全完成」「Live2D」或任何身份验收结论。\n"
        "判据与门禁见 artifacts/ 下的 RED 日志与 docs/PROBE_P0.md。\n",
        encoding="utf-8",
    )

    print(json.dumps({"gate": verdict, "out": str(out)}, indent=2, ensure_ascii=False))
    return 0 if verdict["measurement_valid"] else 2


def run_self_checks(stages: dict[str, list[dict[str, Any]]], proxy, texture,
                    head_box, image_size: tuple[int, int]) -> dict[str, Any]:
    """测量管线的硬自检。

    这些检查存在的理由不是「好看」，而是因为下面每一条都曾被真实破坏过：
      - 正面视角的空洞率**必须接近 0**：源图就是正面图，正面不该有缺口。
        曾因背面剔除符号写反而得到 99.98%——若当时有这个断言，错误会当场暴露。
      - ±45° 的空洞率**必须显著高于正面**：侧面露出的区域本来就不在源图里。
      - 左右视角必须互相对称（合成夹具只有轻微不对称）：不对称说明相机或镜像写错了。
      - 纹理有效格占比必须既不为 0（投影没工作）也不为 1（投影把整张图糊上去了）。
    """
    def ratio(view: str) -> float:
        return next(row["hole_ratio"] for row in stages["baseline"] if row["view"] == view)

    front = ratio("front")
    r45, l45 = ratio("right45"), ratio("left45")
    r90, l90 = ratio("right90"), ratio("left90")
    valid_fraction = float(texture.valid.mean())

    checks = {
        "front_hole_near_zero": {
            "value": front, "threshold": 0.02, "passed": front <= 0.02,
            "why": "源图是正面图，正面视角不应有缺口（背面剔除符号写反时这里会变成 ~1.0）",
        },
        "side45_worse_than_front": {
            "value": min(r45, l45), "threshold": front + 0.05, "passed": min(r45, l45) > front + 0.05,
            "why": "±45° 露出的侧面区域不在源图里，空洞率必须显著高于正面",
        },
        # 左右对称是**提示级**（advisory）：源图本身可能就不对称（合成夹具刻意造了
        # 左脸痣与右颊碎发；真实图的 3/4 侧头更不对称），那属于输入属性而不是管线缺陷。
        # 但它仍然要报出来——相机或镜像写错时这里会剧烈偏离。
        "left_right_symmetry_45": {
            "value": abs(r45 - l45), "threshold": 0.02, "passed": abs(r45 - l45) <= 0.02,
            "severity": "advisory",
            "why": "左右视角差异过大：可能是相机/镜像写错，也可能是源图本身不对称（需人工判断）",
        },
        "left_right_symmetry_90": {
            "value": abs(r90 - l90), "threshold": 0.02, "passed": abs(r90 - l90) <= 0.02,
            "severity": "advisory",
            "why": "同上",
        },
        "texture_valid_fraction_sane": {
            "value": valid_fraction, "threshold": [0.02, 0.95],
            "passed": 0.02 <= valid_fraction <= 0.95,
            "why": "接近 0 表示投影没工作；接近 1 表示把整张图都糊到代理上了",
        },
        "camera_basis_consistent": camera_basis_check(),
        "head_box_is_plausible": head_box_check(head_box, image_size),
    }
    hard = {k: v for k, v in checks.items() if isinstance(v, dict) and v.get("severity") != "advisory"}
    advisory = {k: v for k, v in checks.items() if isinstance(v, dict) and v.get("severity") == "advisory"}
    # 只有**硬自检**能作废门禁读数；advisory 用于人工判断输入是否合规。
    checks["all_passed"] = all(bool(v["passed"]) for v in hard.values())
    checks["advisory_passed"] = all(bool(v["passed"]) for v in advisory.values())
    checks["hard_check_count"] = len(hard)
    checks["advisory_check_count"] = len(advisory)
    return checks


def head_box_check(head_box, size: tuple[int, int]) -> dict[str, Any]:
    """头部包围盒合理性：落在图内、不是整幅图、长宽比合理。

    判据与 `inputprofile.validate_head_box` 保持一致（**不再是**旧的 8%..55% 上限）：
    那个上限本意是「别把全身当头部」，但它同时误伤了**真实的紧凑人像**——
    实测正面铅笔稿的头部占画幅 58%（高 74%），完全合理却被拒。
    真正该判的是「是不是整幅图」，因为那才是定位退化的症状。
    """
    w, h = size
    box_w = head_box[2] - head_box[0] + 1
    box_h = head_box[3] - head_box[1] + 1
    inside = 0 <= head_box[0] < head_box[2] < w and 0 <= head_box[1] < head_box[3] < h
    not_whole = not (box_w >= 0.92 * w and box_h >= 0.92 * h)
    aspect = box_w / max(box_h, 1.0)
    aspect_ok = 0.35 <= aspect <= 2.8
    return {
        "value": [round(float(box_w), 1), round(float(box_h), 1)],
        "passed": bool(inside and not_whole and aspect_ok),
        "detail": {
            "inside_image": bool(inside),
            "not_whole_image": bool(not_whole),
            "aspect": round(float(aspect), 3),
            "frame_fraction": [round(box_w / w, 3), round(box_h / h, 3)],
        },
        "why": "包围盒必须在图内、不是整幅图（定位退化的典型症状）、长宽比接近头部",
    }


def camera_basis_check() -> dict[str, Any]:
    """相机基自检：正面视角的屏幕右方向必须是世界 +x，且 ±90° 必须看到不同的半球。"""
    from project import camera_basis

    d0, r0, u0 = camera_basis(0.0)
    d90, r90, _ = camera_basis(90.0)
    front_right_is_x = bool(np.allclose(r0, [1.0, 0.0, 0.0]))
    # yaw=+90 时相机在 -x 侧，视线指向 +x；此时屏幕右方向应为世界 +y。
    plus90_right_is_y = bool(np.allclose(r90, [0.0, 1.0, 0.0], atol=1e-9))
    front_sight_is_plus_y = bool(np.allclose(d0, [0.0, 1.0, 0.0], atol=1e-9))
    hemispheres_differ = bool(abs(float(d0 @ d90)) < 1e-9)
    return {
        "value": [round(float(v), 6) for v in d90],
        "passed": front_right_is_x and plus90_right_is_y and hemispheres_differ and front_sight_is_plus_y,
        "detail": {
            "front_right_is_world_x": front_right_is_x,
            "plus90_right_is_world_y": plus90_right_is_y,
            "camera_hemispheres_differ": hemispheres_differ,
            "front_sight_is_plus_y": front_sight_is_plus_y,
        },
        "why": "固定屏幕平面会让 ±90° 仍然只看到正面半球（曾因此得出「±90° 几乎不缺纹理」的假结论）",
    }


def judge(stages: dict[str, list[dict[str, Any]]], profile: str = "color", *,
          self_checks: dict[str, Any] | None = None,
          provenance_ok: bool | None = None) -> dict[str, Any]:
    """按计划 §1.2 的判定门禁给出结论，**按 profile 分流**。如实判定，不美化。

    分流依据（FRAMING_DEFECT §0 决策 2：线稿偏转范围下调为 ±45°）：

    | profile | 判定角度 | worst_90 的地位 |
    | --- | --- | --- |
    | `color`（彩图） | **±90°** | 参与判定（`worst_45` 定性、`worst_90` 列预期） |
    | `line_art` / `grayscale`（单色稿） | **±45°** | **`not_applicable`**：既不通过也不失败，不做判据 |

    为什么必须分流：线稿只要求 ±45°，若仍拿 ±90° 的残留当判据，会把一个**未被要求**的角度
    当成失败——那不是判定，是口径错误。彩图仍按 ±90° 判，两者**不能共用一份阈值表**。

    `worst_45` 的阈值（10% / 25%）仍沿用计划 §1.2 的锁值；阈值本身是否需要按 profile 重标
    （FRAMING_DEFECT §7 的 T2）**尚未定**，本轮不动。
    """
    if profile not in ("color", "line_art", "grayscale"):
        raise ValueError(f"未知输入 profile：{profile!r}")

    def ratio(stage: str, view: str) -> float:
        rows = [row for row in stages[stage] if row["view"] == view]
        if len(rows) != 1:
            raise ValueError(f"{stage}/{view} 测量缺失或重复")
        value = float(rows[0]["hole_ratio"])
        if not np.isfinite(value) or not 0 <= value <= 1:
            raise ValueError(f"{stage}/{view} 空洞率必须为 0..1 的有限数")
        return value

    after_b_45 = max(ratio("B_patchmatch", "right45"), ratio("B_patchmatch", "left45"))
    after_b_90 = max(ratio("B_patchmatch", "right90"), ratio("B_patchmatch", "left90"))
    after_b_front = ratio("B_patchmatch", "front")

    if after_b_45 <= 0.10:
        conclusion = "GATE_PASS_C_VIABLE"
        action = "进阶段 1"
    elif after_b_45 <= 0.25:
        conclusion = "GATE_PASS_NEEDS_C"
        action = "进阶段 1，C 权重提高"
    else:
        conclusion = "GATE_FAIL_STOP_AND_RENEGOTIATE"
        action = "停下重新谈判（计划 §1.4）"

    # 单色稿（线稿 / 灰度）都按 ±45° 判：偏转范围取决于**身份载体是笔画还是色块**，
    # 而不是取决于墨量多少。若这里只认 "line_art"，淡稿会被误判成 grayscale 后走 ±90° 口径。
    is_line_art = profile in ("line_art", "grayscale")
    judged_angle = "±45°" if is_line_art else "±90°"
    side_expected = after_b_90 > 0.40
    area_only_conclusion = conclusion
    measurement_valid = bool(self_checks and self_checks.get("all_passed") is True and provenance_ok is True)
    if not measurement_valid:
        conclusion, action = "GATE_INVALID_MEASUREMENT", "先修复硬自检或像素来源校验；不得进入下一阶段"
    elif is_line_art:
        conclusion, action = "GATE_INCOMPLETE_LINE_ART_METRICS", "线稿仅有面积读数，笔画拓扑验收尚未完成"
    return {
        "profile": profile,
        "judged_angle": judged_angle,
        "front_hole_ratio_after_B": after_b_front,
        "worst_45_hole_ratio_after_B": after_b_45,
        "worst_90_hole_ratio_after_B": after_b_90,
        "worst_90_status": "not_applicable" if is_line_art else "expected_above_0.40",
        "thresholds": {"pass": 0.10, "conditionally_pass": 0.25, "side_expected_above": 0.40},
        "conclusion": conclusion,
        "area_only_conclusion": area_only_conclusion,
        "measurement_valid": measurement_valid,
        "quality_gate_complete": measurement_valid and not is_line_art,
        "action": action,
        "side_holes_within_expectation": bool(side_expected),
        "side_holes_note": (
            "±90° 残留 > 40% 属于计划 §1.2 明确列为「预期之内」的情况："
            "正面图没有整个侧脸、耳、颞、下颌侧面、后脑，必须由多视角产生或生成，不视为探针失败。"
            + ("　本输入为线稿，偏转范围已下调为 ±45°，因此 worst_90 **不作为判据**（not_applicable）。"
               if is_line_art else "")
        ),
        "caveat": f"本门禁只回答「(c) 3D 投影 + 分级补全能否支撑 {judged_angle}」，不回答画质是否可用。",
    }


if __name__ == "__main__":
    raise SystemExit(main())
