"""P0 探针 — 合成**线稿**夹具（NEXT_DUAL_INPUT §4.2 的 T4）。

为什么必须单独有它：
    现有合成夹具（`synthetic_character`）是**彩图**——身份载体是颜色与纹理。
    线稿的身份载体是**笔画拓扑**，两者的 A/B/C 级行为与失效信号都不同
    （线稿 B 级可能接出断头线；通用修复模型在二值笔画域会把线糊断）。
    拿彩图夹具当线稿路径的对照物，量出来的数没有意义。

本夹具的设计原则（与彩图夹具一致，但换了载体）：

  1. **几何真值已知**：脸的椭圆、刘海、眼睛、嘴都是解析定义的闭合区域，
     笔画只画在它们的**边界**上。因此「头在哪、笔画应该出现在哪」都是可计算的，
     可以用来核对探针的头部定位与空洞面积。
  2. **线稿的真实属性**：白底 + 深色笔画；**脸内部是纸色**（不是肤色），
     这一点正是彩图夹具与线稿夹具的根本差别，也是彩图定位法在线稿上必失效的原因。
  3. **刻意非对称**：左侧一缕碎发比右侧厚、右眼略小。这样 A 级（对称镜像）的
     收益不会被「本来就是镜像」这种巧合夸大——与彩图夹具同一考虑。
  4. **确定性**：无随机数；同参数同输出。

标注：产出必须标 `SYNTHETIC_FIXTURE` / `NOT_MODEL`。
"""

from __future__ import annotations

from typing import Any

import numpy as np


def synthetic_line_art(width: int = 768, height: int = 1024, stroke: float = 0.18,
                       stroke_width: float = 2.0) -> tuple[np.ndarray, np.ndarray, dict[str, Any]]:
    """返回 (rgb float 0..1 (h,w,3), alpha bool (h,w), meta)。

    参数
    ----
    stroke        : 笔画灰度（0=黑，1=纸）。默认 0.18 接近铅笔/墨线。
    stroke_width  : 笔画宽度（像素，按区域周长采样时使用）。

    alpha 的语义（**必须写清楚，否则会被误读**）：
        线稿里脸内部是纸色，若把 alpha 定义为「暗像素」则脸内部全被当背景，
        探针的头框会退化成只框住笔画。真实线稿资产同样需要「角色区域」这一层，
        因此这里把 alpha 定义为**头部闭合区域的并集**（脸 + 头发 + 脖子底座），
        笔画是其边界。这样：
          · alpha 给出「哪里是角色」（与彩图夹具同一语义）；
          · 纹理有效区（笔画）天然稀疏，正是线稿的真实工况。
        两者不是一回事这一点，在回执里必须一并引用。
    """
    if not (0.0 <= stroke < 1.0):
        raise ValueError("stroke 必须在 [0,1) 内")
    rgb = np.ones((height, width, 3), dtype=np.float64)
    alpha = np.zeros((height, width), dtype=bool)

    ys, xs = np.mgrid[0:height, 0:width]

    # ---- 几何真值（解析定义）----
    cx, cy = width * 0.5, height * 0.38
    face_rx, face_ry = width * 0.175, height * 0.155
    hair_rx, hair_ry = width * 0.255, height * 0.230
    hair_cy = cy - height * 0.030
    chin_y = cy + face_ry
    neck_h = height * 0.13

    nx = (xs - cx) / face_rx
    ny = (ys - cy) / face_ry
    hx = (xs - cx) / hair_rx
    hy = (ys - hair_cy) / hair_ry

    face = nx ** 2 + ny ** 2 <= 1.0
    hair = hx ** 2 + hy ** 2 <= 1.0
    neck = (np.abs(xs - cx) <= width * 0.075) & (ys >= chin_y - 2) & (ys <= chin_y + neck_h)

    # ---- 角色区域 ----
    alpha |= face | hair | neck

    # ---- 笔画：只画在闭合区域的**边界**上 ----
    def boundary(mask: np.ndarray, width_px: float) -> np.ndarray:
        """形态学边界：mask 与其腐蚀之差。只用 numpy，确定性。"""
        r = max(1, int(round(width_px)))
        padded = np.pad(mask, r, mode="constant")
        eroded = np.ones_like(mask)
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                eroded &= padded[r + dy:r + dy + mask.shape[0], r + dx:r + dx + mask.shape[1]]
        return mask & ~eroded

    ink = np.zeros((height, width), dtype=bool)
    for region, w_px in ((face, stroke_width), (hair, stroke_width * 1.6), (neck, stroke_width)):
        ink |= boundary(region, w_px)

    # 五官笔画（内部结构线，线稿的身份主要靠它们）
    for side, scale in ((-1, 1.0), (1, 0.92)):        # 右眼略小 → 刻意非对称
        ex = cx + side * face_rx * 0.40
        ey = cy - face_ry * 0.10
        rx, ry = face_rx * 0.26 * scale, face_ry * 0.105 * scale
        exn = (xs - ex) / rx
        eyn = (ys - ey) / ry
        eye = exn ** 2 + eyn ** 2 <= 1.0
        ink |= boundary(eye, max(1.0, stroke_width * 0.8))
        # 上眼睑加重（线稿里眼线通常更实）
        lid = eye & (eyn < -0.45) & (eyn > -0.95)
        ink |= boundary(lid, max(1.0, stroke_width * 0.6))
        # 瞳孔
        pupil = exn ** 2 + eyn ** 2 <= 0.28
        ink |= boundary(pupil, max(1.0, stroke_width * 0.6))

    # 眉：两条短弧
    for side in (-1, 1):
        bx = cx + side * face_rx * 0.40
        by = cy - face_ry * 0.36
        bxn = (xs - bx) / (face_rx * 0.24)
        byn = (ys - by) / (face_ry * 0.055)
        ink |= boundary(bxn ** 2 + byn ** 2 <= 1.0, max(1.0, stroke_width * 0.7))

    # 鼻：一条竖钩
    nose = (np.abs(xs - (cx + face_rx * 0.05)) <= stroke_width) & (ys > cy + face_ry * 0.05) & (ys < cy + face_ry * 0.30)
    ink |= nose
    # 嘴：一条弧
    mouth = (np.abs((ys - (cy + face_ry * 0.58)) - 0.12 * (xs - cx) ** 2 / face_rx * 6.0) <= stroke_width) & \
            (np.abs(xs - cx) <= face_rx * 0.30)
    ink |= mouth & (xs <= cx + face_rx * 0.30) & (xs >= cx - face_rx * 0.30)

    # 刘海：一条横过额头的弧线
    fringe = face & (ny < -0.42 + 0.30 * np.cos(nx * 1.9))
    ink |= boundary(fringe, stroke_width * 1.3)

    # 碎发（刻意非对称：左侧一缕比右侧厚且更长）
    left_wisp = (np.abs(xs - (cx - hair_rx * 0.72)) <= stroke_width * 1.6) & \
                (ys > hair_cy - hair_ry * 0.45) & (ys < hair_cy + hair_ry * 0.85)
    right_wisp = (np.abs(xs - (cx + hair_rx * 0.86)) <= stroke_width * 0.8) & \
                 (ys > hair_cy - hair_ry * 0.30) & (ys < hair_cy + hair_ry * 0.45)
    ink |= left_wisp | right_wisp

    ink &= alpha                                     # 笔画不越出角色区域
    rgb[ink] = stroke
    rgb[~alpha] = 1.0

    head_box = _region_box(face | hair)
    meta = {
        "label": "SYNTHETIC_FIXTURE",
        "not_model": "NOT_MODEL",
        "carrier": "line_art",
        "size": [width, height],
        "stroke_gray": stroke,
        "stroke_width_px": stroke_width,
        "geometry_truth": {
            "face_ellipse": {"cx": float(cx), "cy": float(cy), "rx": float(face_rx), "ry": float(face_ry)},
            "hair_ellipse": {"cx": float(cx), "cy": float(hair_cy), "rx": float(hair_rx), "ry": float(hair_ry)},
            "neck_bottom_y": float(chin_y + neck_h),
            "head_box_px": head_box,
            "note": "几何真值可解析计算，用于核对探针的头部定位与空洞面积",
        },
        "alpha_semantics": (
            "alpha = 头部闭合区域的并集（脸+头发+脖子底座），**不是**暗像素。"
            "线稿里面部是纸色，若按暗像素取 alpha，脸内部会被当成背景。"
            "真实线稿资产同样需要这一层「角色区域」语义。"
        ),
        "deliberate_asymmetry": ["left_thicker_wisp", "right_eye_smaller"],
        "note": "合成线稿夹具：白底+深色笔画，脸内部为纸色（这正是彩图定位法在线稿上失效的原因）。",
    }
    return rgb, alpha, meta


def _region_box(mask: np.ndarray) -> list[int]:
    rows = np.nonzero(mask.any(axis=1))[0]
    cols = np.nonzero(mask.any(axis=0))[0]
    return [int(cols[0]), int(rows[0]), int(cols[-1]), int(rows[-1])]


def synthetic_line_art_available() -> bool:
    """自检用：夹具能否构造且几何真值自洽。"""
    rgb, alpha, meta = synthetic_line_art(width=256, height=340)
    if rgb.shape[:2] != alpha.shape or not alpha.any():
        return False
    ink = rgb.mean(axis=-1) < 0.5
    if not ink.any() or (ink & ~alpha).any():
        return False
    box = meta["geometry_truth"]["head_box_px"]
    return box[2] > box[0] and box[3] > box[1]
