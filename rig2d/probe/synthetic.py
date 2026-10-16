"""P0 探针 — 合成角色图（默认测试输入）。

**为什么默认用合成图而不是真图**（计划 §1.1 P0.1 的纪律）：
    探针输入**不得取自私聊 / 用户媒体库**。合成图与真图都不违反这一条，
    但合成图额外有两个好处：(a) 许可与隐私上完全没有争议；
    (b) 几何真值已知（我们知道自己画了什么），因此可以把「空洞面积」
    与「几何上应有的空洞面积」对照，作为测量管线的自检。

合成图的构造刻意包含真实角色图的三类结构：
    - 前景/背景可分离（纯色背景 + 明确 alpha）；
    - 头部左右**近似对称但非完全对称**（左脸有一颗痣、右侧一缕碎发），
      这样 A 级对称镜像的效果不会被「本来就是镜像」这种巧合夸大；
    - 皮肤、头发、眼睛三块材质各自有渐变，便于肉眼判断块匹配是否搬错区域。

标注：本文件产出的图像必须标注 `NOT_MODEL`（不是模型产物）与
`SYNTHETIC_FIXTURE`（合成夹具，不是真实角色）。
"""

from __future__ import annotations

from typing import Any

import numpy as np


def synthetic_character(width: int = 768, height: int = 1024, seed: int = 20261012) -> tuple[np.ndarray, np.ndarray, dict[str, Any]]:
    """返回 (rgb float 0..1 (h,w,3), alpha bool (h,w), meta)。

    确定性：同 seed 同输出。
    """
    rng = np.random.default_rng(seed)
    rgb = np.ones((height, width, 3), dtype=np.float64)
    alpha = np.zeros((height, height if False else width), dtype=bool)

    cy = height * 0.42
    cx = width * 0.5
    face_rx = width * 0.175
    face_ry = height * 0.205

    ys, xs = np.mgrid[0:height, 0:width]
    nx = (xs - cx) / face_rx
    ny = (ys - cy) / face_ry

    skin = np.array([0.97, 0.87, 0.80])
    skin_shadow = np.array([0.88, 0.74, 0.68])
    hair = np.array([0.30, 0.22, 0.24])
    hair_hi = np.array([0.44, 0.33, 0.33])

    def paint(mask: np.ndarray, color: np.ndarray) -> None:
        """把 color 涂到 mask 上，**同时把 mask 并入 alpha**。

        alpha 必须在这里维护：早先版本只在少数几处 `alpha |= ...`，
        于是脸/后发虽然被涂了颜色，alpha 仍为 0 —— 而探针的纹理 `valid`
        正是取源图 alpha（不用启发式），结果 `project_source` 的命中数恒为 0，
        渲染出全空图。这类错误会伪装成「几何求交失败」，实际是夹具的自洽性问题。
        背景在最后被强制置白并保持 alpha=0，因此「涂过的像素 = 角色」这条规则成立。
        """
        if color.ndim == 3:
            rgb[mask] = color[mask]
        else:
            rgb[mask] = color
        alpha[mask] = True

    # 后发（大椭圆，先画，会被脸盖住一部分）
    back_hair = (nx / 1.35) ** 2 + ((ny + 0.10) / 1.30) ** 2 <= 1.0
    shade = np.clip(0.5 + 0.5 * (-nx), 0.0, 1.0)[..., None]
    paint(back_hair, hair * (1 - shade) + hair_hi * shade)

    # 脖子 + 肩（身体整体层，不参与 ±90）。位置必须接在脸的下缘，
    # 否则下颌与肩之间会出现一段空白，正面投影在那里就没有纹理。
    chin_ny = 1.0
    chin_y = cy + chin_ny * face_ry
    neck = (np.abs(nx) <= 0.36) & (ys >= chin_y - 4) & (ys <= chin_y + height * 0.16)
    paint(neck, skin_shadow)
    shoulders = ((xs - cx) / (width * 0.46)) ** 2 + ((ys - (chin_y + height * 0.20)) / (height * 0.22)) ** 2 <= 1.0
    paint(shoulders, np.array([0.36, 0.38, 0.46]))

    # 脸
    face = nx ** 2 + ny ** 2 <= 1.0
    light = np.clip(0.75 + 0.25 * (-nx * 0.5 + 0.5), 0.0, 1.0)[..., None]
    paint(face, skin_shadow * (1 - light) + skin * light)

    # 前发（刘海）：盖住额头
    fringe = face & (ny < -0.28 + 0.22 * np.cos(nx * 2.2))
    paint(fringe, hair)

    # 眼睛
    for side in (-1, 1):
        ex = cx + side * face_rx * 0.40
        ey = cy + face_ry * 0.02
        rx, ry = face_rx * 0.20, face_ry * 0.10
        exn = (xs - ex) / rx
        eyn = (ys - ey) / ry
        white = exn ** 2 + eyn ** 2 <= 1.0
        paint(white, np.array([0.99, 0.98, 0.96]))
        iris = exn ** 2 + eyn ** 2 <= 0.42
        paint(iris, np.array([0.36, 0.44, 0.52]))
        pupil = exn ** 2 + eyn ** 2 <= 0.14
        paint(pupil, np.array([0.10, 0.10, 0.13]))
        lid = white & (eyn < -0.55)
        paint(lid, hair)

    # 眉
    for side in (-1, 1):
        ex = cx + side * face_rx * 0.40
        ey = cy - face_ry * 0.22
        brow = (np.abs((xs - ex) / (face_rx * 0.26)) <= 1.0) & (np.abs((ys - ey - side * 0.0) / (face_ry * 0.035)) <= 1.0)
        paint(brow & face, hair * 0.8 + hair_hi * 0.2)

    # 鼻 + 嘴
    nose = (np.abs(nx) <= 0.09) & (ny >= -0.05) & (ny <= 0.30)
    paint(nose & face, skin_shadow * 0.92)
    mouth = ((nx / 0.28) ** 2 + ((ny - 0.52) / 0.07) ** 2 <= 1.0) & face
    paint(mouth, np.array([0.72, 0.42, 0.42]))

    # 非对称标记（故意打破完美对称，避免把 A 级效果夸大）：
    # 左脸一颗痣
    mole = ((xs - (cx - face_rx * 0.62)) / 6.0) ** 2 + ((ys - (cy + face_ry * 0.30)) / 6.0) ** 2 <= 1.0
    paint(mole & face, np.array([0.52, 0.36, 0.34]))
    # 右侧一缕碎发越过脸颊
    wisp = (np.abs((xs - (cx + face_rx * 0.86)) / (face_rx * 0.06)) <= 1.0) & (ys > cy - face_ry * 0.4) & (ys < cy + face_ry * 0.9)
    paint(wisp & (face | back_hair), hair * 0.85)

    # 轻微胶片颗粒（保证块匹配不是在与纯色打交道）
    rgb = np.clip(rgb + rng.normal(0.0, 0.012, size=rgb.shape), 0.0, 1.0)
    rgb[~alpha] = 1.0

    # 头部包围盒（探针的几何拟合目标）：脸 + 后发 + 前发的范围。
    head_mask = face | back_hair | fringe
    hrows = np.nonzero(head_mask.any(axis=1))[0]
    hcols = np.nonzero(head_mask.any(axis=0))[0]
    head_box = [int(hcols[0]), int(hrows[0]), int(hcols[-1]), int(hrows[-1])]

    meta = {
        "label": "SYNTHETIC_FIXTURE",
        "not_model": "NOT_MODEL",
        "size": [width, height],
        "seed": seed,
        "face_rx_px": float(face_rx),
        "face_ry_px": float(face_ry),
        "center_px": [float(cx), float(cy)],
        "head_box_px": head_box,
        "deliberate_asymmetry": ["left_cheek_mole", "right_cheek_wisp"],
        "note": "合成夹具：不是真实角色、不是模型产物，用于在无许可/隐私暴露的前提下跑通测量管线。",
    }
    return rgb, alpha, meta
