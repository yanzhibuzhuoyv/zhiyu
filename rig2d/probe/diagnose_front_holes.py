"""P0 探针 — 诊断：front 视角的空洞究竟来自**几何**还是**纹理**（核实报告 T1f）。

问题（VERIFY_FRONT_LINEART §6）：修复 D1/D2 后，合成夹具的 `front_hole_near_zero`
从贴边通过的 0.0198 升到 0.0769（4 倍超标）。报告要求说明这是「几何拟合」还是「新引入的误差」。

判据（可量化、不靠叙述）：
    对每一个 front 视角的**空洞像素**，反解它在源图上对应的像素，检查该源像素属于
    角色（alpha=True）还是背景（alpha=False）：

      · 若空洞几乎全部对应**背景**像素 → 代理覆盖了源图没有内容的区域 = **几何拟合**差异，
        不是管线缺陷；
      · 若空洞里有大量**角色**像素 → 说明投影丢掉了本该采到的纹理 = **真缺陷**。

反解方式：front 视角的屏幕像素 (u,v) 与源图像素是同一套正交投影
（世界 x = u*step + head_center_x、世界 z = v*step + head_center_z 的逆），
因此可以直接换算，无需重新求交。

用法：
    python probe/diagnose_front_holes.py --out artifacts/diag-front-holes
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))

from headproxy import build_proxy  # noqa: E402
from inputprofile import box_center_world, fit_head_box, parse_head_box, profile_input, radii_from_box, validate_head_box  # noqa: E402
from project import dilate_texture, project_source, render_view  # noqa: E402
from run_probe import FIVE_VIEWS, VIEW_NAMES, load_input  # noqa: E402
from validation import validate_parameters, validate_source_rays  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="front 空洞成因诊断（T1f）")
    parser.add_argument("--image", type=Path, default=None)
    parser.add_argument("--out", type=Path, default=Path("artifacts/diag-front-holes"))
    parser.add_argument("--resolution", type=int, default=384)
    parser.add_argument("--grid", type=int, default=96)
    parser.add_argument("--texture", type=int, default=512)
    parser.add_argument("--head-box", type=parse_head_box, default=None)
    parser.add_argument("--proxy-exponent", type=float, default=4.0)
    args = parser.parse_args()
    args.supersample, args.dilate, args.proxy_cover = 2, 2, 1.
    try:
        validate_parameters(args)
    except ValueError as error:
        parser.error(str(error))
    if args.out.exists() or args.out.is_symlink():
        parser.error(f"输出目录已存在，禁止覆盖诊断证据：{args.out}")

    rgb, alpha, _, meta = load_input(args.image)
    h, w, _ = rgb.shape
    validate_source_rays(w, h, args.supersample)
    pixel_scale = 2.0 / max(w, 1)
    profile = profile_input(rgb, alpha)
    if args.head_box:
        head_box = args.head_box
    else:
        head_box = fit_head_box(alpha, rgb, profile)
    validate_head_box(head_box, (w, h))
    args.out.mkdir(parents=True, exist_ok=False)
    radii = radii_from_box(head_box, pixel_scale)
    cx_world, cz_world = box_center_world(head_box, w, h, pixel_scale)
    proxy = build_proxy(args.grid, args.grid, radii, exponent=args.proxy_exponent)

    texture = dilate_texture(
        project_source(proxy, rgb, alpha, pixel_scale, args.texture, args.texture,
                       supersample=2, head_center=(cx_world, cz_world)),
        proxy, 2)
    render = render_view(proxy, texture, 0.0, args.resolution, pixel_scale)
    step = render.world_per_pixel

    holes = render.alpha & ~render.filled
    rows, cols = np.nonzero(holes)
    total_holes = int(holes.sum())
    total_visible = int(render.alpha.sum())

    # 屏幕像素 → 世界 → 源图像素。
    # **必须复用 render_view 的取景公式**（自适应 fit），不能用 pixel_scale 硬推：
    # 第一版用 pixel_scale 反推，把 y 的符号也算错了，得到「72.9% 空洞对应角色像素」的
    # 假结论（正确值是 5793/5802 对应背景）。取景是自适应的，反解也必须走同一个公式。
    half = args.resolution / 2.0
    screen_x = (cols + 0.5 - half) * step
    screen_z = -(rows + 0.5 - half) * step
    world_x = screen_x + cx_world
    world_z = screen_z + cz_world
    src_col = np.floor(world_x / pixel_scale + w / 2.0).astype(np.int64)
    src_row = np.floor(-world_z / pixel_scale + h / 2.0).astype(np.int64)
    inside = (src_col >= 0) & (src_col < w) & (src_row >= 0) & (src_row < h)

    hole_on_character = int((alpha[src_row[inside], src_col[inside]]).sum())
    hole_on_background = int(inside.sum() - hole_on_character)
    hole_off_image = int((~inside).sum())

    # 对照：同一视角**已覆盖**区域的源像素构成（应几乎全是角色）
    vrows, vcols = np.nonzero(render.alpha & render.filled)
    vx = (vcols + 0.5 - half) * step + cx_world
    vz = -(vrows + 0.5 - half) * step + cz_world
    vc = np.floor(vx / pixel_scale + w / 2.0).astype(np.int64)
    vr = np.floor(-vz / pixel_scale + h / 2.0).astype(np.int64)
    vin = (vc >= 0) & (vc < w) & (vr >= 0) & (vr < h)
    filled_on_character = int(alpha[vr[vin], vc[vin]].sum())
    filled_on_background = int(vin.sum() - filled_on_character)

    report: dict[str, Any] = {
        "diagnosis": "front 空洞成因（几何 vs 纹理）",
        "input": meta,
        "profile": profile.profile,
        "head_box_px": [float(v) for v in head_box],
        "radii_world": [float(v) for v in radii],
        "proxy_exponent": args.proxy_exponent,
        "resolution": args.resolution,
        "world_per_pixel": float(step),
        "front_visible_pixels": total_visible,
        "front_hole_pixels": total_holes,
        "front_hole_ratio": float(total_holes) / max(total_visible, 1),
        "holes_on_background_source": hole_on_background,
        "holes_on_character_source": hole_on_character,
        "holes_off_image": hole_off_image,
        "hole_composition": {
            "background_fraction": hole_on_background / max(total_holes, 1),
            "character_fraction": hole_on_character / max(total_holes, 1),
            "off_image_fraction": hole_off_image / max(total_holes, 1),
        },
        "filled_composition_check": {
            "character_fraction": filled_on_character / max(filled_on_character + filled_on_background, 1),
            "note": "已覆盖区域的源像素应几乎全是角色；若不是，说明纹理采样本身有问题",
        },
        "verdict": verdict(hole_on_background, hole_on_character, hole_off_image, total_holes),
    }
    (args.out / "front-hole-diagnosis.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    # 视觉证据：把空洞像素按成因着色（红=源为背景，蓝=源为角色，灰=源在图外）
    vis = render.color.copy()
    vis[render.alpha & render.filled] *= 0.35
    for index in range(rows.size):
        r, c = int(rows[index]), int(cols[index])
        if not inside[index]:
            vis[r, c] = np.array([0.5, 0.5, 0.5])
        elif alpha[src_row[index], src_col[index]]:
            vis[r, c] = np.array([0.0, 0.4, 1.0])       # 源为角色 → 真缺陷信号
        else:
            vis[r, c] = np.array([1.0, 0.1, 0.1])       # 源为背景 → 几何拟合差异
    vis[~render.alpha] = 1.0
    Image.fromarray((np.clip(vis, 0, 1) * 255).astype(np.uint8)).save(args.out / "hole-causes.png")

    print(json.dumps({k: report[k] for k in
                      ("front_hole_ratio", "holes_on_background_source", "holes_on_character_source",
                       "holes_off_image", "hole_composition", "verdict")},
                     indent=2, ensure_ascii=False))
    return 0


def verdict(on_bg: int, on_char: int, off: int, total: int) -> dict[str, Any]:
    if total == 0:
        return {"conclusion": "no_holes", "text": "正面没有空洞。"}
    char_frac = on_char / total
    bg_frac = on_bg / total
    if char_frac >= 0.30:
        conclusion = "TEXTURE_DEFECT"
        text = (f"空洞里有 {char_frac:.1%} 对应**角色像素**——投影丢掉了本该采到的纹理，"
                "属于真缺陷，必须查 project_source。")
    elif bg_frac >= 0.60:
        conclusion = "GEOMETRIC_FIT"
        text = (f"空洞里 {bg_frac:.1%} 对应**背景像素**——代理覆盖了源图本来没有内容的区域，"
                "属于「代理形状 vs 头形状」的拟合差异，不是管线缺陷。修法是让代理贴合头部轮廓"
                "（例如按内容形状拟合，而不是按矩形包围盒）。")
    else:
        conclusion = "MIXED"
        text = (f"混合成因：背景 {bg_frac:.1%} / 角色 {char_frac:.1%} / 图外 {off / total:.1%}。"
                "需要分别处理，不得笼统归因。")
    return {"conclusion": conclusion, "text": text}


if __name__ == "__main__":
    raise SystemExit(main())
