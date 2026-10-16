"""P0 探针 — 几何原语：超椭球（superellipsoid）代理。

**为什么不是纯椭球**（实测驱动，不是审美偏好）：

    把代理做成交互式椭球后，正面视角出现 10.3% 的洞，而源图就是正面图、正面不该有洞。
    逐块核对发现原因**不在纹理**：头的轮廓（脸 + 头发）接近**圆角矩形**，
    而椭球的正面投影是椭圆——椭圆的四个「角」盖不到，那部分几何可见但源图里没有对应像素，
    于是被记成空洞。也就是说：**代理形状本身在制造假缺口**，会让 ±45°/±90° 的读数整体偏高。

超椭球 `Σ|x_i/r_i|^p = 1`（本仓库 p = 4）的正面投影是**圆角矩形**，
能贴合头部轮廓，同时仍然是一个光滑闭曲面（法线处处有定义）；
且当 p = 2 时退化为普通椭球；p = 1 是 L1 多面体，并非椭球。

解析式：
    f(x) = Σ|x_i/r_i|^p − 1
    ∇f_i ∝ sign(x_i/r_i)·|x_i/r_i|^(p−1)/r_i
    p = 2 时才化简为 normalize(x_i/r_i²)。

球面参数化（p = 2 时即标准球坐标）：
    u = (sinφ cosθ, sinφ sinθ, cosφ)
    q = (|u_x|^(2/p) sgn, |u_y|^(2/p) sgn, |u_z|^(2/p) sgn)
    曲面点 = q ⊙ r
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

DEFAULT_EXPONENT = 4.0     # p=4 → 正面投影接近圆角矩形；p=2 → 普通椭球
DEFAULT_TESSELLATION = 512  # 数值求交的采样数（只影响精度，不影响结果形态）


@dataclass(frozen=True)
class ProxyShape:
    """Lp 代理形状参数。`exponent = 2` 是椭球，1 是多面体。"""

    radii: np.ndarray
    exponent: float = DEFAULT_EXPONENT

    def __post_init__(self) -> None:
        r = np.asarray(self.radii, dtype=np.float64)
        if r.shape != (3,) or not np.all(np.isfinite(r)) or np.any(r <= 0):
            raise ValueError("半径必须是三个正有限数")
        if not np.isfinite(self.exponent) or self.exponent < 1.0:
            raise ValueError("exponent 必须 ≥ 1（2 = 椭球）")
        object.__setattr__(self, "radii", r)

    @property
    def is_ellipsoid(self) -> bool:
        return abs(self.exponent - 2.0) < 1e-9

    def as_dict(self) -> dict:
        return {"radii": [float(v) for v in self.radii], "exponent": float(self.exponent),
                "shape": "ellipsoid" if self.is_ellipsoid else "superellipsoid"}


def surface_point(unit: np.ndarray, shape: ProxyShape) -> np.ndarray:
    """单位球方向 → 曲面点。`unit` 形状 (..., 3)，**不要求已归一化**（内部会归一化）。

    参数化：先归一化 `û = u/|u|`，再取 `q_i = sgn(û_i)·|û_i|^(2/p)`。
    由 `|q_i|^p = |û_i|²` 与 `Σ|û_i|² = 1` 得 `Σ|q_i|^p = 1`，
    故 `q ⊙ r` 正落在超椭球面 `Σ|x_i/r_i|^p = 1` 上。

    两条必须记住的教训（都实测过）：
      1. **必须先归一化**。少了这一步，`Σ|u_i|² = 1` 不成立，点会跑到面内或面外
         （实测 p=1 时 implicit = +0.61、p=4 时 +7.14，而 p=2 恰好为 0——
          因为只有 p=2 时该式与归一化无关，很容易被误判成「实现是对的」）。
      2. 直接「单位球 × 半径」不是超椭球的解，只在 p=2（普通椭球）时成立。
    """
    p = shape.exponent
    norm = np.linalg.norm(unit, axis=-1, keepdims=True)
    safe = np.divide(unit, norm, out=np.zeros_like(unit), where=norm > 0)
    flipped = np.sign(safe) * np.abs(safe) ** (2.0 / p)
    return flipped * shape.radii


def surface_normal(points: np.ndarray, shape: ProxyShape) -> np.ndarray:
    """实际 Lp 隐式曲面的梯度法线；p=1 的折线处取零分量子梯度。"""
    scaled = points / shape.radii
    grad = np.sign(scaled) * np.abs(scaled) ** (shape.exponent - 1.) / shape.radii
    norm = np.linalg.norm(grad, axis=-1, keepdims=True)
    return np.divide(grad, norm, out=np.zeros_like(grad), where=norm > 0)


def implicit(points: np.ndarray, shape: ProxyShape) -> np.ndarray:
    """f(p) = Σ|x_i/r_i|^p − 1；< 0 在内部，= 0 在面上。

    **必须用 L^p 范数**，不能写成 `(Σ(x_i/r_i)²)^p`——后者是张量积形式，
    与本仓库的参数化 `Σ|x_i/r_i|^p = 1` 不是同一个曲面（实测：p=4 时两者偏差达 7.1，
    表现为「网格点全在面外」，而 p=2 时两者恰好等价，极易被误判成实现正确）。
    """
    s = np.sum(np.abs(points / shape.radii) ** shape.exponent, axis=-1)
    return s ** (1.0 / shape.exponent) - 1.0          # 取 1/p 次方：数值上更接近「到面的距离」


def raycast(origins: np.ndarray, sight: np.ndarray, shape: ProxyShape,
            rotation: np.ndarray | None = None,
            tessellation: int = DEFAULT_TESSELLATION,
            memory_budget_bytes: int = 96 * 1024 * 1024) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """射线与超椭球求交，取**沿视线最近**的那个交点。

    参数化（刻意选成让符号不可能写反的形式，见 probe/project.py 的同名说明）：
      - `sight` 是从**相机指向被摄物**的单位视线；
      - 射线写作 `p(s) = origins + s * sight`，`s` 是沿视线的距离，**必须为正**；
      - 求交用「粗采样 + 二分细化」，取**最小的正根**（离相机最近）。

    两处必须说明的工程决策（都来自实测）：
      1. **先用外接椭球做粗筛**。超椭球完全包含在「半径乘 √3」的椭球里，
         因此 `((o-dot)/3r)² > 1` 的射线不可能相交，可以整批剔掉——这一步把
         768×1024×2² ≈ 310 万条射线压到几十万条。
      2. **分块处理**。第一版直接为全部射线建 (N, T+1, 3) 的采样数组，
         实测要 36 GiB，直接 MemoryError。现在按内存预算分块，与输入规模无关。

    返回 `(hit, points, normals)`：未命中处为 nan。
    """
    if rotation is None:
        rotation = np.eye(3)
    count = origins.shape[0]
    hit = np.zeros(count, dtype=bool)
    points = np.full((count, 3), np.nan, dtype=np.float64)
    normals = np.full((count, 3), np.nan, dtype=np.float64)
    if count == 0:
        return hit, points, normals

    o_all = origins @ rotation
    d = rotation.T @ sight
    # 外接椭球：超椭球 ⊂ 半径×√3 的椭球（p≥1）；再加一点余量
    outer = shape.radii * (3.0 ** 0.5) * 1.05
    inv_outer2 = 1.0 / (outer ** 2)
    a_t = float(np.sum((d ** 2) * inv_outer2))
    b_t = 2.0 * ((o_all * inv_outer2) @ d)
    c_t = np.sum((o_all ** 2) * inv_outer2, axis=1) - 1.0
    outer_disc = b_t ** 2 - 4 * a_t * c_t
    candidates = np.nonzero(outer_disc >= 0.0)[0]
    if candidates.size == 0:
        return hit, points, normals

    span = float(np.sqrt(np.maximum(outer_disc[candidates], 0.0)).max() / a_t) + 2e-6
    s_center = -b_t[candidates] / (2 * a_t)
    per_ray_bytes = (tessellation + 1) * 3 * 8 * 4      # 采样点 + 粗筛 + 符号差 + 临时量
    chunk = max(1, min(candidates.size, memory_budget_bytes // max(per_ray_bytes, 1)))

    ts_template = np.arange(tessellation + 1, dtype=np.float64) / tessellation
    for start in range(0, candidates.size, chunk):
        block = candidates[start:start + chunk]
        s_lo = s_center[start:start + chunk] - span * 0.5
        o = o_all[block]
        ts = np.maximum(s_lo[:, None] + span * ts_template[None, :], 0.0)
        pts = o[:, None, :] + ts[..., None] * d[None, None, :]
        f = implicit(pts, shape)
        # **进入**交点 = f 由 ≥0 变 <0（相机在体外，先进入物体内部）。
        # 不能写成「由内到外」——那取到的是**远侧**表面，法线朝向相机反方向，
        # 于是背面剔除会把全部命中丢掉（实测：纹理命中数恒为 0，渲染全空）。
        entering = (f[:, :-1] >= 0.0) & (f[:, 1:] < 0.0)
        has_root = entering.any(axis=1)
        if not has_root.any():
            continue
        first = np.argmax(entering, axis=1)
        lo = ts[np.arange(block.size), first]          # 体外
        hi = ts[np.arange(block.size), first + 1]      # 体内
        for _ in range(32):                            # 二分细化
            mid = 0.5 * (lo + hi)
            inside = implicit(o + mid[:, None] * d, shape) < 0.0
            lo = np.where(inside, lo, mid)
            hi = np.where(inside, hi, mid)
        s = np.where(has_root, 0.5 * (lo + hi), np.nan)
        local = o + s[:, None] * d
        n = surface_normal(np.nan_to_num(local, nan=0.0), shape)
        p_world = local @ rotation.T
        n_world = n @ rotation.T
        good = has_root & np.isfinite(s)
        target = block[good]
        hit[target] = True
        points[target] = p_world[good]
        normals[target] = n_world[good]
    return hit, points, normals


def grid_directions(columns: int, rows: int) -> np.ndarray:
    """网格中心的单位球方向 (rows, columns, 3)；与曲面参数化同一套半格偏移约定。"""
    theta = (np.arange(columns) + 0.5) / columns * 2 * np.pi - np.pi
    phi = (np.arange(rows) + 0.5) / rows * np.pi
    return np.stack([
        np.sin(phi)[:, None] * np.cos(theta)[None, :],
        np.sin(phi)[:, None] * np.sin(theta)[None, :],
        np.cos(phi)[:, None] * np.ones((1, columns)),
    ], axis=-1)
