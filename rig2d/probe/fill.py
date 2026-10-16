"""P0 探针 — B 级补全：块匹配（计划 §5.2 B）。

纪律（与计划 §5.6 的 PatchMatchFillNativeTest 同一条）：
**输出像素必须来自本图**。块匹配不生成任何新纹理，只在本图里搬运自相似的块，
搬运本图像素不等于身份无风险：笔画连接与部位对应仍需单独验收。

算法（确定性、无随机）：
  1. 从已填充区域出发，逐环（3×3 结构元）向空洞内推进；
  2. 每一环里，对每个新像素在**同一行**内搜索一个横向偏移 `delta`，
     使「目标行片段 ∩ 候选行片段」的共同已知像素上的 MSE 最小；
  3. 按最优 `delta` 把同一行的已填充像素搬进来——因此输出像素必然来自本图。

为什么只在同一行搜索（`delta` 只有横向分量）：跨行搬块会把额头纹理搬到下巴，
产生肉眼可见的错位。行内搜索对应计划 §5.2 明确列出的两类（头发延伸、衣物褶皱），
它们都是横向延展的结构。垂直搜索属于后续阶段扩展，探针不假装已实现。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np


@dataclass
class PatchMatchResult:
    color: np.ndarray          # (H, W, 3) 填充后的颜色
    filled: np.ndarray         # (H, W) 填充后的有效掩码
    filled_pixels: int         # 本次新填的像素数
    used_sources: np.ndarray   # (H, W, 2) 每个新填充像素的来源坐标；未填充处为 (-1,-1)
    rings_used: int
    stopped_reason: str        # "done" | "empty_search_domain" | "max_rings"
    empty_search_pixels: int   # 因搜索域为空而放弃的像素数（必须如实计数）
    max_offset_used: int       # 实际用到的最大横向偏移（用于核对「没有跨区域涂抹」）
    chained_distance_limit: int  # 本次允许的链式传播上限（环）


def best_horizontal_offset(color: np.ndarray, filled: np.ndarray, r: int, c: int,
                           window: int, search_radius: int) -> int | None:
    """在 row=r 上找最优横向偏移 delta（把 c 映射到 c+delta）。

    做法：比较**目标窗口**（`c-window .. c+window`，通常是「空洞 + 紧邻的已填充边」）
    与**候选窗口**（平移 delta 后）在两者共同**已填充**的像素上的 MSE。
    候选窗口必须整体落在已填充区域内（否则会从空洞里取样，把空洞搬进空洞）。

    返回 None 表示搜索域为空（该行没有任何可用候选）。

    历史缺陷：早先只比较「目标窗口里已填充的那些位置」，而推进环上的目标像素周围
    往往一个已填充像素都没有 → 共同集合为空 → 永远返回 None → **B 级一格都没填**
    （`empty_search_pixels` 高达 31 万、`patch_filled_pixels` 恒为 0）。
    现在窗口里只要有已填充边界就足以定出偏移。
    """
    h, w = filled.shape
    if not (0 <= r < h):
        return None
    row = color[r]
    known = filled[r]
    tc0, tc1 = max(0, c - window), min(w, c + window + 1)
    if tc1 - tc0 < 2:
        return None
    best: tuple[float, int] | None = None
    lo = max(-search_radius, -tc0)
    hi = min(search_radius, w - tc1)
    for delta in range(lo, hi + 1):
        if abs(delta) <= window:
            continue                      # 太近的偏移 == 自己，无信息
        sc0, sc1 = tc0 + delta, tc1 + delta
        if sc0 < 0 or sc1 > w:
            continue
        if not known[sc0:sc1].all():
            continue                      # 候选窗口必须全部已知
        common = known[tc0:tc1]
        if not common.any():
            continue
        diff = row[tc0:tc1][common] - row[sc0:sc1][common]
        score = float(np.mean(np.sum(diff * diff, axis=-1)))
        if best is None or score < best[0]:
            best = (score, delta)
    return None if best is None else best[1]


def _dilate(mask: np.ndarray) -> np.ndarray:
    padded = np.pad(mask, 1, mode="constant")
    out = np.zeros_like(mask)
    for dr in range(3):
        for dc in range(3):
            out |= padded[dr:dr + mask.shape[0], dc:dc + mask.shape[1]]
    return out


def patch_match_fill(alpha: np.ndarray, color: np.ndarray, filled: np.ndarray,
                     half: int = 4, search_radius: int = 24, max_chained_distance: int = 3,
                     max_rings: int = 512) -> PatchMatchResult:
    """在空洞里做行内块匹配填充（局部，不跨区域搬运）。

    alpha  : (H, W) bool，几何可见区域（只在这个区域内填）
    color  : (H, W, 3) 当前颜色
    filled : (H, W) bool，当前有效掩码（A 级之后）
    half   : 匹配窗口半宽（窗口 = 2*half+1）
    search_radius : 单步的来源偏移上限
    max_chained_distance : **链式传播的环数上限**。这是本函数最重要的参数：

        第一版 `search_radius=64` 时，B 级「填掉」了 ±90° 的 52% 空洞
        （`patch_filled_pixels` 三万多），看数字像块匹配大获成功。
        逐行核对后发现：每一步偏移确实只有几十像素，但**链式传播**把它推进了几百像素——
        正脸鼻梁附近的像素被一节一节搬到后脑，颜色连成一片、几何完全错位；
        局部检查全部「通过」，全局结果是涂抹。

        本实现额外限制**几何向空洞推进的环数**：
        超过 `max_chained_distance × half` 环的填充不予承认（保留为 C 级残差）。
        这样 B 只覆盖「窄缝」，与计划 §5.2 对 B 的定位（头发延伸、衣物褶皱）一致。

        该参数不是来源链 hop / 累计横向 offset 上限：同一环内的已填像素
        仍可被后续像素借用，来源链可能较长。provenance 校验只证明颜色
        来自本图，不能证明部位或笔画对应正确；这些仍是阶段 3 的质量边界。
    """
    if half < 1:
        raise ValueError("half 必须为正")
    if search_radius < 1:
        raise ValueError("search_radius 必须为正")
    if max_chained_distance < 1:
        raise ValueError("max_chained_distance 必须为正")
    if not np.any(alpha & ~filled):
        raise ValueError("没有空洞可填——空搜索域必须被拒绝，不能假装成功")

    h, w = alpha.shape
    out_color = color.copy()
    out_filled = filled.copy()
    used = np.full((h, w, 2), -1, dtype=np.int64)
    initial = filled.copy()
    rings = 0
    stopped = "max_rings"
    empty = 0
    max_offset = 0
    limit = max_chained_distance * half

    while rings < max_rings:
        if rings >= limit:
            stopped = "chained_distance_limit"
            break
        if not np.any(alpha & ~out_filled):
            stopped = "done"
            break
        ring = _dilate(out_filled) & alpha & ~out_filled
        if not ring.any():
            stopped = "empty_search_domain"
            break
        rings += 1
        rows, cols = np.nonzero(ring)
        for r, c in zip(rows.tolist(), cols.tolist(), strict=True):
            delta = best_horizontal_offset(out_color, out_filled, r, c, half, search_radius)
            if delta is None:
                empty += 1
                continue
            # 只填这个像素本身：环推进已保证「由外向内」的填充顺序，
            # 相邻像素会在后续环里各自拿到自己的最优偏移。
            sc = c + delta
            if not (0 <= sc < w) or not out_filled[r, sc]:
                empty += 1
                continue
            out_color[r, c] = out_color[r, sc]
            out_filled[r, c] = True
            used[r, c] = (r, sc)
            max_offset = max(max_offset, abs(int(delta)))
    return PatchMatchResult(
        color=out_color,
        filled=out_filled,
        filled_pixels=int((out_filled & alpha & ~initial).sum()),
        used_sources=used,
        rings_used=rings,
        stopped_reason=stopped,
        empty_search_pixels=empty,
        max_offset_used=max_offset,
        chained_distance_limit=limit,
    )


def residual_mask(alpha: np.ndarray, filled: np.ndarray) -> np.ndarray:
    """C 级需要处理的残差 = 仍有几何但没有纹理的像素。"""
    return alpha & ~filled


def summarize(alpha: np.ndarray, filled: np.ndarray) -> dict[str, Any]:
    total = int(alpha.sum())
    holes = int(residual_mask(alpha, filled).sum())
    return {
        "silhouette_pixels": total,
        "hole_pixels": holes,
        "hole_ratio": (float(holes) / float(total)) if total else 0.0,
    }


def provenance_is_from_image(result: PatchMatchResult, source_pixels: set[tuple[int, int]]) -> bool:
    """校验「输出像素必来自本图」（计划 §5.6 的硬要求）。

    填充是**链式**的：环推进时新填的像素可能又成为后续像素的来源。
    因此判定必须沿来源链回溯到**最初就存在**的像素（`source_pixels`），
    而不是只看直接来源——只看直接来源会把合法的链式搬运误判成违规
    （实测会被误判 1770/7243）。
    """
    h, w = result.filled.shape
    used = result.used_sources
    if used.shape != (h, w, 2) or result.color.shape != (h, w, 3):
        return False
    if any(not (0 <= r < h and 0 <= c < w and result.filled[r, c])
           for r, c in source_pixels):
        return False
    new_pixels = np.argwhere(result.filled)
    for r, c in new_pixels:
        seen: set[tuple[int, int]] = set()
        cr, cc = int(r), int(c)
        while True:
            if (cr, cc) in source_pixels:
                if not np.array_equal(result.color[r, c], result.color[cr, cc]):
                    return False                 # 声明来源正确，但颜色不是源像素
                break
            if (cr, cc) in seen:
                return False                     # 环：来源链没有终点
            seen.add((cr, cc))
            sr, sc = int(used[cr, cc, 0]), int(used[cr, cc, 1])
            if sr < 0 or not (0 <= sr < h and 0 <= sc < w):
                return False                     # 断链：来源不是本图里的像素
            cr, cc = sr, sc
    return True
