"""P0 探针 — 取景与投影覆盖的自检（诊断报告 FRAMING_DEFECT 的 T1a）。

为什么需要独立成模块：
    已有的 6 项自检**抓不到**取景缺陷。原因是那种缺陷让「几何」与「纹理」**同步错位**——
    `hole_ratio` 的分子（有无纹理）与分母（可见面积）双双落在错误的区域上，
    比值看起来仍然合理（甚至更好看）。这类缺陷只能靠**跨阶段一致性**检查抓住：
      · 投影采样区域 vs 头部包围盒（投影到底有没有采到头）
      · 渲染轮廓 vs 画面边界（头有没有被挤出画面）
      · 轮廓面积 vs 头部面积（分母是不是头的面积）
      · 世界 ↔ 像素往返（轴序有没有互换）

四项检查的判据都写在函数里，且**每一项都能用一份已失效的回执作为 RED 样本**。
"""

from __future__ import annotations

from typing import Any

import numpy as np


def check_projection_covers_head(sample_bbox_px: tuple[float, float, float, float],
                                 head_box: tuple[float, float, float, float],
                                 min_overlap: float = 0.95) -> dict[str, Any]:
    """`project_source` 的采样区域必须覆盖头部包围盒。

    RED 样本：真实线稿回执里投影采样行 379–645，而头部在行 40–305 —— **重叠为零**，
    纹理完全采自躯干。当时的 `hole_ratio` 仍然给出一串合理数字。
    """
    hx0, hy0, hx1, hy1 = head_box
    sx0, sy0, sx1, sy1 = sample_bbox_px
    ix0, iy0 = max(hx0, sx0), max(hy0, sy0)
    ix1, iy1 = min(hx1, sx1), min(hy1, sy1)
    inter = max(0.0, ix1 - ix0) * max(0.0, iy1 - iy0)
    head_area = max(1.0, (hx1 - hx0) * (hy1 - hy0))
    overlap = inter / head_area
    return {
        "value": round(float(overlap), 4),
        "threshold": min_overlap,
        "passed": bool(overlap >= min_overlap),
        "detail": {
            "head_box": [float(v) for v in head_box],
            "projection_sample_bbox": [round(float(v), 1) for v in sample_bbox_px],
        },
        "why": "投影采样区域必须覆盖头部；不覆盖时纹理与可见面积双双落在错误区域，比值无意义",
    }


def check_head_visible_in_frame(silhouette_bbox: list[int] | None,
                               resolution: int, min_margin_px: int = 4) -> dict[str, Any]:
    """front 视角的轮廓不能被画面边界裁切（必须留边距）。

    RED 样本：真实线稿回执的 front 轮廓 bbox 为 `[216, 0, 325, 49]`——**上界为 0**，
    也就是头被挤出了画面，只剩顶部一条。这一条检查单独就能让该缺陷当场显形。
    """
    if silhouette_bbox is None:
        return {"value": None, "threshold": min_margin_px, "passed": False,
                "detail": {"reason": "轮廓为空"}, "why": "轮廓为空说明渲染没有产生任何几何"}
    x0, y0, x1, y1 = silhouette_bbox
    margins = {"left": x0, "top": y0, "right": resolution - 1 - x1, "bottom": resolution - 1 - y1}
    worst = min(margins.values())
    return {
        "value": worst,
        "threshold": min_margin_px,
        "passed": bool(worst >= min_margin_px),
        "detail": {"silhouette_bbox": list(silhouette_bbox), "margins_px": margins, "resolution": resolution},
        "why": "front 轮廓触边说明头被挤出画面，分母不是头的可见面积",
    }


def check_silhouette_matches_headbox(silhouette_pixels: int,
                                     head_box: tuple[float, float, float, float],
                                     pixel_scale: float, resolution: int,
                                     exponent: float, world_per_pixel: float,
                                     lo: float = 0.6, hi: float = 1.5) -> dict[str, Any]:
    """front 轮廓面积应接近「头部包围盒在该分辨率下的面积 × 形状填充系数」。

    超椭球在包围盒内的填充系数随 exponent 变化（p=1 时 π/4≈0.785，p=4 时约 0.93）。
    这里用 `0.6..1.5` 的宽带只做**量级**检查：分母若来自别的区域（躯干、被裁切的切片），
    比值会远离 1。
    """
    # 期望面积必须用**本帧真实的世界/像素比例**换算。取景是自适应的（render_view 的 fit），
    # 早先这里假定「每像素 = 2·pixel_scale / resolution」，取景一改该假定即失效，
    # 于是比值虚高到 1.94（几何其实是对的）——自检本身的量纲错也要当成缺陷修。
    box_w_px = head_box[2] - head_box[0] + 1
    box_h_px = head_box[3] - head_box[1] + 1
    box_w_world = box_w_px * pixel_scale
    box_h_world = box_h_px * pixel_scale
    expected_px = (box_w_world / world_per_pixel) * (box_h_world / world_per_pixel) * 0.90
    ratio = float(silhouette_pixels) / max(expected_px, 1.0)
    return {
        "value": round(ratio, 4),
        "threshold": [lo, hi],
        "passed": bool(lo <= ratio <= hi),
        "detail": {
            "silhouette_pixels": int(silhouette_pixels),
            "expected_pixels": round(float(expected_px), 1),
            "world_per_pixel": round(float(world_per_pixel), 6),
        },
        "why": "轮廓面积与头部面积的比值必须落在合理区间；偏离说明分母不是头的面积",
    }


def check_center_roundtrip(box: tuple[float, float, float, float], image_size: tuple[int, int],
                           pixel_scale: float, tol_px: float = 1.0) -> dict[str, Any]:
    """世界中心 ↔ 像素中心往返必须还原头部包围盒中心（抓轴序互换）。

    RED 样本：`box_center_world(box, (w, h), ps)` 把 `(width,height)` 当成 `(height,width)` 解包，
    返回 `(-0.332, 0.009)`，而正确值是 `(0.0013, 0.342)`——**横向与纵向整体换位**。
    """
    from inputprofile import box_center_world, head_center_from_world

    width, height = image_size
    cx_world, cz_world = box_center_world(box, width, height, pixel_scale)
    cx_px, cy_px = head_center_from_world(cx_world, cz_world, width, height, pixel_scale)
    expected_cx = (box[0] + box[2]) * 0.5 + 0.5
    expected_cy = (box[1] + box[3]) * 0.5 + 0.5
    err = float(np.hypot(cx_px - expected_cx, cy_px - expected_cy))
    return {
        "value": round(err, 4),
        "threshold": tol_px,
        "passed": bool(err <= tol_px),
        "detail": {
            "world": [round(float(cx_world), 5), round(float(cz_world), 5)],
            "roundtrip_px": [round(float(cx_px), 2), round(float(cy_px), 2)],
            "expected_px": [round(float(expected_cx), 2), round(float(expected_cy), 2)],
        },
        "why": "世界↔像素往返必须还原包围盒中心；不还原说明 image_size 的轴序被互换",
    }


def framing_checks(sample_bbox_px, head_box, front_render, resolution, pixel_scale,
                   image_size, exponent) -> dict[str, Any]:
    """把四项检查打包，供 run_probe 调用。全部为**硬检查**（能作废门禁读数）。"""
    return {
        "projection_covers_head": check_projection_covers_head(sample_bbox_px, head_box),
        "head_visible_in_frame": check_head_visible_in_frame(front_render.silhouette_bbox(), resolution),
        "silhouette_matches_headbox": check_silhouette_matches_headbox(
            int(front_render.alpha.sum()), head_box, pixel_scale, resolution, exponent,
            front_render.world_per_pixel),
        "head_center_roundtrip": check_center_roundtrip(head_box, image_size, pixel_scale),
    }


def projection_sample_bbox(head_box, proxy, pixel_scale: float,
                           image_size: tuple[int, int],
                           head_center: tuple[float, float] = (0.0, 0.0)) -> tuple[float, float, float, float]:
    """`project_source` 实际会采样到的源图像素范围。

    当前实现的采样窗口以**图像中心**为原点、跨度等于代理在 x/z 方向的直径，
    因此这里直接按该定义推算，不做任何"应该是"的假设——
    自检要检查的是**代码实际行为**，不是设计意图。
    """
    width, height = image_size
    rx_px = float(proxy.radii[0]) / pixel_scale
    rz_px = float(proxy.radii[2]) / pixel_scale
    cx = width * 0.5 + head_center[0] / pixel_scale
    cy = height * 0.5 - head_center[1] / pixel_scale
    return (cx - rx_px, cy - rz_px, cx + rx_px, cy + rz_px)
