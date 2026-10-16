"""P0 探针 — 输入画像与头部定位。

为什么需要这个模块（实测教训）：
    第一版 `fit_head_box` 用「肤色 + 发色」粗筛定位头部。它对**彩色插画**有效，
    但对**黑白线稿**完全失效——线稿的脸是**白纸色**，与背景同色，肤色掩码命中 0 个像素，
    于是「头部包围盒」退化成整张图，代理椭球被拟合到整个角色身上，
    纹理反投影把衣服褶皱当成脸糊上去（渲染结果就是一片噪点）。
    危险之处在于：它**不报错**，只是给出一个看起来像数字的结果。

因此本模块把「输入画像」显式化：
  1. 先判断输入属于哪种 profile（彩色 / 线稿 / 灰度）；
  2. 彩色走肤色+发色粗筛；线稿走亮度结构分析；
  3. 无法定位时**明确报错**，绝不用整张图当头部；
  4. 提供人工覆盖入口 `--head-box`（探针允许人工给几何真值，
     因为 P0 要测的是「缺口面积」，不是「自动检测精度」）。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np

# profile 判据：平均饱和度。均值而非分位数——分位数会被少量彩色像素（如朱砂印章）拉高。
SATURATION_MEAN_THRESHOLD = 0.05

# 头部定位的**下限**（占图像最长边的比例）。
# 不再设上限：旧的 0.55 上限既误伤紧凑人像（实测真实线稿头占画幅 58%/高 74%），
# 又拦不住真正退化的结果（那是接近整幅图）。改用 head_vs_body_shape 的形状判据，
# 见 docs/VERIFY_FRONT_LINEART_RECEIPT_2026-10-12.md §3。
HEAD_MIN_FRACTION = 0.08


@dataclass(frozen=True)
class InputProfile:
    profile: str                     # "color" | "line_art" | "grayscale"
    saturation_mean: float
    saturation_p99: float
    ink_fraction: float              # 暗像素占比（线稿的墨量）
    background_is_light: bool
    background_reliable: bool        # 背景分离是否可信（泛洪分离失败时为 False）
    notes: list[str]

    def as_dict(self) -> dict[str, Any]:
        return {
            "profile": self.profile,
            "saturation_mean": round(float(self.saturation_mean), 5),
            "saturation_p99": round(float(self.saturation_p99), 5),
            "ink_fraction": round(float(self.ink_fraction), 5),
            "background_is_light": bool(self.background_is_light),
            "background_reliable": bool(self.background_reliable),
            "notes": self.notes,
        }


def saturation(rgb: np.ndarray) -> np.ndarray:
    mx = rgb.max(axis=-1)
    mn = rgb.min(axis=-1)
    return np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0.0)


def profile_input(rgb: np.ndarray, alpha: np.ndarray) -> InputProfile:
    """判断输入画像。**这一步的结论会决定头部如何定位，因此必须显式记录。**"""
    if (rgb.ndim != 3 or rgb.shape[-1] != 3 or min(rgb.shape[:2]) < 1
            or alpha.shape != rgb.shape[:2] or alpha.dtype != np.bool_
            or not np.isfinite(rgb).all() or np.any((rgb < 0) | (rgb > 1))):
        raise ValueError("输入必须为有限的 H×W×3 RGB（0..1）与同尺寸布尔 alpha")
    if not alpha.any():
        raise ValueError("角色区域 alpha 为空，无法进行输入画像或定位")
    sat = saturation(rgb)
    inside = alpha if alpha.any() else np.ones(alpha.shape, dtype=bool)
    sat_mean = float(sat[inside].mean()) if inside.any() else 0.0
    sat_p99 = float(np.percentile(sat[inside], 99)) if inside.any() else 0.0
    lum = rgb.mean(axis=-1)
    ink = float((lum[inside] < 0.45).mean()) if inside.any() else 0.0
    border = np.concatenate([lum[0, :], lum[-1, :], lum[:, 0], lum[:, -1]])
    background_is_light = bool(border.mean() > 0.5)
    notes: list[str] = []

    # 背景分离的可靠性：泛洪填充若把画面吃掉 >95% 或 <2%，说明它没找到真正的背景
    # （淡铅笔稿的纸纹会让泛洪一路渗进角色，实测 alpha 覆盖 1.000、bbox 撑满整幅图）。
    coverage = float(alpha.mean()) if alpha.size else 0.0
    background_reliable = 0.02 < coverage < 0.95
    if not background_reliable:
        notes.append(
            f"背景分离不可信（alpha 覆盖 {coverage:.3f}）：泛洪填充没有分离出角色。"
            "定位将退化为「把纸面当背景、用 Otsu 找笔画」，不再依赖 alpha。",
        )

    if sat_mean < SATURATION_MEAN_THRESHOLD:
        # 单色输入一律按**线稿**处理，不再区分 line_art / grayscale。
        # 实测教训：淡铅笔稿的纸底会让「暗于 0.45 的占比」只有 1.4%，旧判据因此把它归为
        # `grayscale`；而 `judge` 只对 `line_art` 走 ±45° 分流，于是判定角度错成 ±90°。
        # 既然偏转范围是按「身份载体是笔画还是色块」定的，判据就该跟载体走，而不是跟墨量走。
        # 用 Otsu 算笔画占比，对浓淡两种稿子都成立。
        stroke_fraction = float((lum < otsu_threshold(lum[alpha if alpha.any() else np.ones_like(alpha, dtype=bool)])).mean())
        profile = "line_art" if stroke_fraction > 0.005 else "grayscale"
        notes.append(
            f"平均饱和度 {sat_mean:.4f} < {SATURATION_MEAN_THRESHOLD}：输入几乎没有颜色信息"
            f"（Otsu 笔画占比 {stroke_fraction:.4f}）。"
            "基于肤色/发色的头部定位**必然失效**（线稿的脸是纸色），必须改用亮度结构定位。",
        )
    else:
        profile = "color"
    if background_is_light and profile == "line_art":
        notes.append("背景为亮色且墨量集中在笔画上：这是「白底黑线」线稿的典型形态。")
    return InputProfile(profile, sat_mean, sat_p99, ink, background_is_light, background_reliable, notes)


def head_vs_body_shape(ink: np.ndarray, box: tuple[float, float, float, float],
                       profile: "InputProfile | None" = None) -> dict[str, Any]:
    """判定候选框里装的是**头**还是**头+躯干**（T1e：替换单纯的尺寸上限）。

    为什么不能靠尺寸上限（核实报告 §3.5 已指出，这里记录结论）：
      · 55% 上限既是「误伤紧凑人像」的原因（这张线稿的头占画幅 58%、高 74%，完全合理）；
      · 又是当时唯一拦住 `714×798` 那个退化结果的闸门。
      删掉它 → 退化结果会被放行；留着它 → 真实构图被拒。**两者不能同时靠尺寸解决。**

    判据最终采用**贴边信号**，不采用轮廓增长率作为判决依据——两次失败记录在案：

      尝试一「行内笔画**计数**」：两案例全判反。计数反映纹理密度而非轮廓：
        头在下巴处轮廓收窄，但眼睛与嘴集中中下部 → 计数反而变大；
        「头+躯干」的框里下半部笔画稀疏（衣服线淡） → 计数变小。

      尝试二「行内笔画**轮廓宽度**」：合成彩图的头/躯干分得很开
        （头 1.06 vs 头+躯干 1.46，可用），但**真实线稿失效**——
        这张图人物的手抬在下巴前，正好位于框的下部，把轮廓撑宽
        （实测 `w50=0.94, w90=0.925, bottom_growth=2.07`），于是紧凑头被判成躯干。
        那是**真实内容**，不是缺陷。

    因此判决改用「贴边」：退化框（自动定位一路扩到画面边缘的结果）必然同时贴住
    右侧与底部；紧凑人像的合法框通常不贴边。轮廓增长率**继续上报**（`bottom_growth`）
    作为诊断信号，但不参与判决。

    失败时的行为是**要求人工 `--head-box`**，不是静默接受——这是设计上的兜底，
    因此判据宁可保守（多要求几次人工）也不要放行退化结果。
    """
    x0, y0, x1, y1 = (int(round(v)) for v in box)
    h, w = ink.shape
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(w - 1, x1), min(h - 1, y1)
    band = ink[y0:y1 + 1, x0:x1 + 1]
    if band.size == 0:
        return {"passed": False, "reasons": ["候选框为空"], "profile": None}

    rows = band.shape[0]
    box_w = band.shape[1]
    extents = np.zeros(rows, dtype=np.float64)
    for r in range(rows):
        cols = np.nonzero(band[r])[0]
        if cols.size == 0:
            continue
        lo = int(np.percentile(cols, 1))
        hi = int(np.percentile(cols, 99))
        extents[r] = float(max(hi - lo, 1))
    window = max(3, rows // 30)
    smooth = np.convolve(extents, np.ones(window) / window, mode="same")

    def at(fraction: float) -> float:
        idx = int(np.clip(fraction * (rows - 1), 0, rows - 1))
        return float(smooth[idx])

    w10, w20, w50, w90 = at(0.10), at(0.20), at(0.50), at(0.90)
    normalized = {k: round(v / box_w, 3) for k, v in
                  (("w10", w10), ("w20", w20), ("w50", w50), ("w90", w90))}
    bottom_growth = w90 / max(w20, 1.0)

    touches = {
        "left": x0 <= 2, "top": y0 <= 2,
        "right": x1 >= w - 3, "bottom": y1 >= h - 3,
    }
    edge_count = sum(1 for v in touches.values() if v)
    near_whole = box_w >= 0.92 * w and rows >= 0.92 * h
    # 第二判据：轮廓下部明显变宽 **且** 框几乎占满画面高度——两者同时成立才判躯干。
    # 单用 bottom_growth 会误伤：「头+手」的真实构图下部同样变宽（实测 2.07）。
    # 组合条件必须**两个信号同时**成立，任何一个单独出现都不足以判负。
    height_fraction = rows / float(h)
    wide_and_tall = bottom_growth > 1.40 and height_fraction > 0.75

    reasons = []
    if near_whole:
        reasons.append("框接近整幅图")
    if touches["bottom"] and touches["right"]:
        reasons.append("框同时贴住画面右侧与底部（自动定位扩到边缘的典型症状）")
    elif edge_count >= 2:
        reasons.append(f"框贴住 {edge_count} 条画面边缘")
    if wide_and_tall:
        reasons.append(
            f"下部轮廓宽度是上部的 {bottom_growth:.2f} 倍且框高占画面 {height_fraction:.0%}"
            "（头+躯干的典型形状）",
        )

    passed = not (near_whole or (touches["bottom"] and touches["right"])
                  or edge_count >= 2 or wide_and_tall)
    return {
        "passed": passed,
        "profile": "head" if passed else "head+body(疑似)",
        "extent_profile": normalized,
        "bottom_growth": round(float(bottom_growth), 4),
        "height_fraction": round(float(height_fraction), 4),
        "touches_frame_edge": touches,
        "edge_touch_count": int(edge_count),
        "near_whole_image": bool(near_whole),
        "reasons": reasons,
        "note": "bottom_growth 单独不判决，必须与「框高占画面 >75%」同时成立才判躯干"
                "（真实线稿里手会撑宽下部轮廓，单用会误伤）",
    }


def fit_head_box(alpha: np.ndarray, rgb: np.ndarray, profile: InputProfile) -> tuple[float, float, float, float]:
    """估计头部包围盒 (col0, row0, col1, row1)，按 profile 走不同策略。

    - `color`：肤色 + 发色粗筛（对彩色插画有效）。
    - `line_art`：Otsu 笔画 + 平滑密度定位（浓淡稿都成立）。

    校验分两层，**都不再用「最长边 ≤ 55%」这类尺寸上限**（核实报告 §3）：
      1. 下限：最长边 ≥ 8% 画面（防退化成一两个像素）；
      2. 形状：`head_vs_body_shape` 判「是头还是头+躯干」。
    判定失败时**抛错并要求人工 `--head-box`**，绝不静默返回一个退化的框。
    """
    h, w = alpha.shape
    longest = max(h, w)
    lo_limit = HEAD_MIN_FRACTION * longest

    if profile.profile == "color":
        box = _fit_head_box_color(alpha, rgb)
        ink = _ink_mask(alpha, rgb, profile)
    else:
        box = _fit_head_box_line_art(alpha, rgb, profile)
        ink = _ink_mask(alpha, rgb, profile)

    width = box[2] - box[0] + 1
    height = box[3] - box[1] + 1
    if max(width, height) < lo_limit:
        raise ValueError(
            f"头部定位失败：得到 {width:.0f}x{height:.0f} 像素，过小"
            f"（下限 {lo_limit:.0f}，即最长边的 {HEAD_MIN_FRACTION}）。\n"
            f"输入画像：{profile.profile}。请用 --head-box x0,y0,x1,y1 人工给出头部范围。",
        )

    shape = head_vs_body_shape(ink, box, profile)
    if not shape["passed"]:
        raise ValueError(
            f"头部定位失败：候选框 {width:.0f}x{height:.0f} 被判为**不是头部**——"
            + "；".join(shape["reasons"])
            + f"（下部/上部轮廓宽度比 = {shape['bottom_growth']}；"
            f"框高占画面 {shape['height_fraction']:.0%}）。\n"
            f"输入画像：{profile.profile}（平均饱和度 {profile.saturation_mean:.4f}）。\n"
            "本判据替代了旧的「最长边 ≤ 55% 尺寸上限」——那个上限既误伤紧凑人像，"
            "又拦不住真正退化的结果（见 docs/VERIFY_FRONT_LINEART_RECEIPT_2026-10-12.md §3）。\n"
            "请用 --head-box x0,y0,x1,y1 人工给出头部范围——P0 测的是缺口面积，不是自动检测精度。",
        )
    return box


def _ink_mask(alpha: np.ndarray, rgb: np.ndarray, profile: InputProfile) -> np.ndarray:
    """统一的「笔画掩码」，供形状判据使用。"""
    lum = rgb.mean(axis=-1)
    region = alpha if profile.background_reliable else np.ones(alpha.shape, dtype=bool)
    level = min(max(otsu_threshold(lum[region]), 0.30), 0.95)
    return (lum < level) & region


def _fit_head_box_color(alpha: np.ndarray, rgb: np.ndarray) -> tuple[float, float, float, float]:
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    skin = (r > 0.72) & (g > 0.55) & (g < r) & (b < r) & (r - b > 0.06)
    hair = (r < 0.55) & (g < 0.45) & (b < 0.45)
    candidate = (skin | hair) & alpha
    if candidate.sum() < 0.005 * alpha.size:
        candidate = alpha
    return _box_from_candidate(candidate)


def otsu_threshold(values: np.ndarray, bins: int = 256) -> float:
    """Otsu 最大类间方差阈值（自适应，不需要先知道墨占多少）。

    为什么必须自适应：两张真实线稿的墨量差了**两个数量级**——
      浓墨线稿：暗于 0.45 的像素可观；铅笔淡稿：暗于 0.45 的只占 0.39%。
    固定阈值（0.55）在淡稿上只抓到 2% 的像素，行密度退化成噪声带，
    头部定位于是给出 566×62 的细横条。Otsu 在这张淡稿上给出 0.826，
    低于它的像素占 16.4%，正好把铅笔笔画与纸面分开。
    """
    hist, edges = np.histogram(values, bins=bins, range=(0.0, 1.0))
    counts = hist.astype(np.float64)
    total = counts.sum()
    if total <= 0:
        raise ValueError("Otsu 阈值：输入为空")
    prob = counts / total
    centers = (edges[:-1] + edges[1:]) * 0.5
    weight0 = np.cumsum(prob)
    weight1 = 1.0 - weight0
    mean0 = np.cumsum(prob * centers)
    mean1 = (prob * centers).sum() - mean0
    with np.errstate(divide="ignore", invalid="ignore"):
        between = (mean0 / weight0 - mean1 / weight1) ** 2 * weight0 * weight1
    between[~np.isfinite(between)] = -1.0
    return float(centers[int(np.argmax(between))])


def _fit_head_box_line_art(alpha: np.ndarray, rgb: np.ndarray,
                           profile: "InputProfile | None" = None) -> tuple[float, float, float, float]:
    """线稿：用「笔画密度」找头部。

    阈值走 Otsu（自适应）。若背景分离不可信（`profile.background_reliable=False`），
    则不依赖 alpha，直接「把纸面当背景、靠笔画定位」。

    上下边界用**平均密度 + 低阈值**取「包含锚点的最大连续区间」，而不是「逐行密度跌破峰值就停」。
    实测教训：后者对噪声极敏感——淡铅笔稿在眼睛一带密度最高，逐行判据在眼睛下方
    因某一行稀疏就停住，定位结果 695×200（只框到眼睛）；浓墨线稿则相反，偏大 1.4 倍。
    平均密度能把「整体有笔画」与「某一行恰好稀疏」区分开。
    """
    h, w = alpha.shape
    lum = rgb.mean(axis=-1)
    reliable_bg = bool(profile.background_reliable) if profile is not None else bool(0.02 < alpha.mean() < 0.97)
    region = alpha if reliable_bg else np.ones(alpha.shape, dtype=bool)

    ink_level = min(max(otsu_threshold(lum[region]), 0.30), 0.95)
    ink = (lum < ink_level) & region
    if ink.sum() < 0.0005 * ink.size:
        raise ValueError(
            f"线稿定位失败：Otsu 阈值 {ink_level:.3f} 下只找到 {int(ink.sum())} 个笔画像素。"
            "图像可能几乎是空白，或笔画比背景亮（反色稿）。",
        )
    row_density = ink.sum(axis=1).astype(np.float64)

    # 锚点：图像上部笔画最密的一段
    anchor = int(np.argmax(row_density[: max(1, int(h * 0.55))]))
    peak = float(row_density[max(0, anchor - 20):anchor + 20].mean())
    floor = max(row_density.max() * 0.004, peak * 0.035, 1.0)

    window = max(9, h // 40)
    smooth = np.convolve(row_density, np.ones(window) / window, mode="same")

    top = anchor
    while top > 0 and smooth[max(0, top - window):top].mean() >= floor:
        top -= 1
    bottom = anchor
    while bottom < h - 1 and smooth[bottom:bottom + window].mean() >= floor:
        bottom += 1

    if bottom - top + 1 < 0.10 * h:                 # 收得太紧：回退到整体笔画范围
        rows = np.nonzero(row_density >= floor)[0]
        if rows.size:
            top, bottom = int(rows[0]), int(rows[-1])
    cut = int(0.78 * max(h, w))
    if bottom - top + 1 > cut:                      # 高度上限
        bottom = top + cut - 1

    band = ink[top:bottom + 1]
    col_density = band.sum(axis=0)
    if col_density.max() <= 0:
        raise ValueError("线稿定位失败：候选带内没有笔画像素")
    strong = np.nonzero(col_density >= max(col_density.max() * 0.06, 1.0))[0]
    left, right = int(strong[0]), int(strong[-1])
    return (float(left), float(top), float(right), float(bottom))


def _box_from_candidate(candidate: np.ndarray) -> tuple[float, float, float, float]:
    """彩色插画：用「峰值行宽」定位头部，并**收紧到头部本身**。

    两处收紧的理由（都是实测出来的）：
      1. **宽度用列密度下限裁掉身体与手臂**。彩色角色图的掩码里肩膀比头宽得多；
         直接用整段的行跨度当宽度会把椭球撑到肩宽，「代理宽于头部」→
         正面两侧出现无纹理的假缺口（实测正面空洞率 7.4%）。
      2. **高度上限 0.75×最长边**。同理，过高的椭球会把脖子与衣服一起框进去。
    收紧后代理只覆盖头 + 发，计划 §4.3 声明的范围边界（身体不在代理内）才真正成立。
    """
    h, w = candidate.shape
    rows_any = np.nonzero(candidate.any(axis=1))[0]
    if rows_any.size == 0:
        return (0.0, 0.0, float(w - 1), float(h - 1))
    widths = candidate.sum(axis=1)
    peak_row = int(np.argmax(widths))
    threshold = widths[peak_row] * 0.60
    bottom = int(rows_any[-1])
    for row in range(peak_row, int(rows_any[-1]) + 1):
        if widths[row] < threshold:
            bottom = row
            break
    max_height = 0.75 * max(h, w)
    if bottom - rows_any[0] + 1 > max_height:
        bottom = int(rows_any[0] + max_height - 1)

    band = candidate[int(rows_any[0]):bottom + 1]
    col_density = band.sum(axis=0)
    strong = np.nonzero(col_density >= col_density.max() * 0.25)[0]
    if strong.size == 0:
        cols = np.nonzero(band.any(axis=0))[0]
        left, right = int(cols[0]), int(cols[-1])
    else:
        left, right = int(strong[0]), int(strong[-1])
    if right - left + 1 > 0.55 * w:                 # 头部不应超过画面一半宽
        centre = (left + right) // 2
        half = int(0.275 * w)
        left, right = max(0, centre - half), min(w - 1, centre + half)

    # **收紧上下边界**：把「行内候选像素数 < 峰值行宽的 3%」的行剥掉。
    # 不收紧的话，包围盒上下会各多出一截空白（例如头顶留白、下巴以下的脖子），
    # 代理椭球随之超出源图内容的范围——那部分几何正面可见但没有纹理，
    # 于是变成 6% 的「正面假缺口」。
    row_counts = candidate[:, max(0, left):right + 1].sum(axis=1)
    floor = max(row_counts.max() * 0.03, 1.0)
    top = int(rows_any[0])
    while top < bottom and row_counts[top] < floor:
        top += 1
    while bottom > top and row_counts[bottom] < floor:
        bottom -= 1
    return (float(left), float(top), float(right), float(bottom))


def parse_head_box(text: str) -> tuple[float, float, float, float]:
    parts = [float(v) for v in text.replace(" ", "").split(",")]
    if len(parts) != 4 or not np.isfinite(parts).all():
        raise ValueError("--head-box 需要 4 个有限数：x0,y0,x1,y1")
    x0, y0, x1, y1 = parts
    if x1 <= x0 or y1 <= y0:
        raise ValueError("--head-box 的 x1/y1 必须大于 x0/y0")
    return (x0, y0, x1, y1)


def validate_head_box(box: tuple[float, float, float, float], image_size: tuple[int, int]) -> None:
    """头部包围盒必须落在图像内、长宽比合理、并且**不是整幅图**。

    判据的演化（两次都因为真实输入而改）：
      1. 初版用「最长边 ∈ 8%..55%」。它挡不住线稿返回整张图（那是 100%），
         却挡得住**真实的紧凑人像**——实测这张正面铅笔稿的头部占画幅 58%，
         完全合理，却被拒绝。上限本身解决不了它想解决的问题。
      2. 现在改成判「是不是整幅图」：若宽与高都 ≥ 92% 画面，说明定位退化成整幅图（真缺陷）；
         同时保留下限 8% 防止退化成一两个像素。尺寸大小本身不再设上限——
         紧凑构图是合法输入。
    """
    w, h = image_size
    if w < 1 or h < 1 or len(box) != 4 or not np.isfinite(box).all():
        raise ValueError("图像尺寸必须为正，头部包围盒必须包含四个有限数")
    x0, y0, x1, y1 = box
    if not (0 <= x0 < x1 < w and 0 <= y0 < y1 < h):
        raise ValueError(
            f"头部包围盒超出图像范围：{box} vs 图像 {w}x{h}。\n"
            f"注意坐标必须用**工作副本**的像素（本图工作副本为 {w}x{h}，"
            "原图若更大已被 thumbnail 缩放到最长边 ≤1024）。",
        )
    box_w = x1 - x0 + 1
    box_h = y1 - y0 + 1
    longest = max(box_w, box_h)
    scale = max(w, h)
    if longest < HEAD_MIN_FRACTION * scale:
        raise ValueError(
            f"头部包围盒过小：{box_w:.0f}x{box_h:.0f}，最长边应 ≥ 最长边的 {HEAD_MIN_FRACTION}"
            f"（即 {HEAD_MIN_FRACTION * scale:.0f} 像素）。",
        )
    if box_w >= 0.92 * w and box_h >= 0.92 * h:
        raise ValueError(
            f"头部包围盒几乎是整幅图（{box_w:.0f}x{box_h:.0f} vs 图像 {w}x{h}）："
            "定位退化成了「整幅图」。请用 --head-box 指定头部范围。",
        )
    aspect = box_w / box_h
    if not (0.35 <= aspect <= 2.8):
        raise ValueError(f"头部包围盒长宽比异常：{aspect:.2f}（头部通常接近 1:1 到 1:1.6）")


def box_center_world(box: tuple[float, float, float, float], width: int, height: int,
                     pixel_scale: float) -> tuple[float, float]:
    """头部包围盒中心 → 世界偏移 (x, z)。

    **签名显式给出 width / height，不再用 `image_size` 元组**。
    这是诊断报告 D2 的修法：早先签名是 `(box, image_size, pixel_scale)` 并在内部
    `h, w = image_size` 解包，而调用点传的是 `(w, h)`——横向与纵向整体换位，
    返回 `(-0.332, 0.009)` 而正确值是 `(0.0013, 0.342)`。
    隐式的元组顺序正是出错的根源，因此改成两个具名参数，让"传错顺序"在调用点上不可能发生。

    符号约定：世界 `x = (col - w/2) * pixel_scale`；世界 `z = -(row - h/2) * pixel_scale`
    （图像行号向下增长，世界 z 向上）。头在图像上方 ⇒ z > 0。
    """
    cx = (box[0] + box[2]) * 0.5 + 0.5
    cy = (box[1] + box[3]) * 0.5 + 0.5
    return ((cx - width * 0.5) * pixel_scale, -(cy - height * 0.5) * pixel_scale)


def head_center_from_world(cx_world: float, cz_world: float, width: int, height: int,
                           pixel_scale: float) -> tuple[float, float]:
    """[box_center_world] 的逆变换：世界偏移 → 像素中心。用于往返自检。"""
    return (cx_world / pixel_scale + width * 0.5, -cz_world / pixel_scale + height * 0.5)


def radii_from_box(box: tuple[float, float, float, float], pixel_scale: float,
                   width_k: float = 1.0, height_k: float = 1.0, depth_k: float = 0.72) -> np.ndarray:
    """头部包围盒 → 代理椭球半径（世界单位）。

    语义（曾经含糊过，代价是正面出现 5.7% 的洞）：
      - `box` 是**头部整体**（脸 + 头发）的包围盒，不是脸的包围盒；
      - `width_k = 1.0` 表示椭球横向**直径 = 头宽**。这样投影后正面视角不会在两侧
        留下无纹理的边条：椭球若比源图内容更宽，那部分几何在正面视角同样可见，
        会立刻变成「正面有洞」。自检 `front_hole_near_zero` 就是抓这个的。
      - `height_k = 1.0` 同理；`depth_k = 0.72` 是头骨前后径/横径的解剖比例。
      - 单位换算只在最后乘一次 `pixel_scale`。
    """
    head_w_px = box[2] - box[0] + 1.0
    head_h_px = box[3] - box[1] + 1.0
    rx_px = head_w_px * 0.5 * width_k
    rz_px = head_h_px * 0.5 * height_k
    ry_px = rx_px * depth_k
    return np.array([rx_px, ry_px, rz_px]) * pixel_scale


def draw_proxy_overlay(rgb: np.ndarray, head_box: tuple[float, float, float, float],
                       center: tuple[float, float], radii_px: tuple[float, float],
                       pixel_scale: float = 1.0) -> np.ndarray:
    """P0.6 的目视检查底图：在源图上画出头部包围盒与代理椭球轮廓。

    为什么必须画出来：第一版的失败（代理拟合到整身）在数字上完全看不出来——
    `probe.json` 里的半径就是几个正数。只有把椭圆画回源图，才能一眼看出它有没有套在头上。
    """
    out = rgb.copy()
    h, w, _ = out.shape
    x0, y0, x1, y1 = (int(round(v)) for v in head_box)
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(w - 1, x1), min(h - 1, y1)
    # 包围盒：橙色
    for x in range(x0, x1 + 1):
        for y in (y0, y1):
            out[y, x] = np.array([1.0, 0.45, 0.0])
    for y in range(y0, y1 + 1):
        for x in (x0, x1):
            out[y, x] = np.array([1.0, 0.45, 0.0])
    # 椭球轮廓（世界 → 屏幕）：洋红
    if not np.isfinite(pixel_scale) or pixel_scale <= 0:
        raise ValueError("pixel_scale 必须为正有限数")
    cx, cy = head_center_from_world(center[0], center[1], w, h, pixel_scale)
    rx, ry = radii_px
    theta = np.linspace(0, 2 * np.pi, 2048)
    xs = np.round(cx + rx * np.cos(theta)).astype(int)
    ys = np.round(cy - ry * np.sin(theta)).astype(int)
    inside = (xs >= 0) & (xs < w) & (ys >= 0) & (ys < h)
    out[ys[inside], xs[inside]] = np.array([1.0, 0.0, 0.35])
    return out
