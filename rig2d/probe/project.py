"""P0 探针 — 纹理投影与多视角渲染（计划 §1.1 P0.3 / P0.4）。

方法（如实写明，因为整个探针的结论都建立在这套算术上）：

1. **代理**：中心在原点的椭球，半径 `radii`（headproxy.py）。
2. **源相机**：正交投影，视线方向 `d0 = +y`（从 -y 看向原点），up 轴 = `+z`。
   因此源图第 (row, col) 个像素对应世界点 `(x, 0, z)`，
   `x = (col + .5 - w/2) * s`、`z = -(row + .5 - h/2) * s`（图像 y 向下，世界 z 向上）。
3. **纹理反投影（P0.3）**：对每个源像素发一条 `p(t) = (x,0,z) + t*d0`（t>0）的射线，
   与椭球求最近的**朝相机**交点。命中则记下该点的局部方向 `u`（单位球坐标），
   并写入纹理网格 `texel(u) = 源图 RGB，valid = 源图 alpha 有值`。
   ——注意这里 `valid` 用的是**源图自身的 alpha**，不是「像不像脸」的启发式：
   探针要回答的是「源图里到底有没有这块像素」，任何启发式都会把结论做软。
4. **多视角渲染（P0.4）**：目标视角 yaw=θ 时，把几何整体绕 z 轴旋转 −θ，
   相机固定在 `d = -y`。于是屏幕像素 → 世界射线 → 与旋转后的椭球求交 → 取最近交点 → 查纹理。
   空洞 = 该像素**有几何**但纹理 `valid == False`。
5. **占比**：按**像素数**（而非格数）统计，分母是该视角下可见几何的像素数。
   面积加权在像素域自动成立（正交投影下像素面积恒定）。

确定性：全程无随机数；渲染分辨率与网格尺寸固定时结果逐位可复现。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import numpy as np

from geom import ProxyShape, raycast
from headproxy import HeadProxy

UP = np.array([0.0, 0.0, 1.0])
SOURCE_SIGHT = np.array([0.0, 1.0, 0.0])   # 源相机在 -y 侧，视线指向 +y（朝被摄物）


def rotation_z(degrees: float) -> np.ndarray:
    """绕 z 轴旋转。yaw>0 = 头部向右转（与 domain 侧约定一致）。"""
    a = np.deg2rad(degrees)
    c, s = np.cos(a), np.sin(a)
    return np.array([[c, -s, 0.0], [s, c, 0.0], [0.0, 0.0, 1.0]])


def raycast_ellipsoid(origins: np.ndarray, sight: np.ndarray, radii: np.ndarray,
                      rotation: np.ndarray | None = None,
                      exponent: float = 1.0) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """薄封装：把 (radii, exponent) 转成 ProxyShape 后交给 geom.raycast。

    保留这个函数名是因为调用点很多；真正的实现与全部说明在 **geom.py**。
    历史缺陷（务必保留记录）：早期版本用「从物体指向相机」的向量当射线方向、
    并允许参数为负，导致选中椭球**背面**，整个画面被剔空。
    现在参数语义唯一：`sight` 指向被摄物，距离 `s` 必须为正，取最小正根。
    """
    return raycast(origins, sight, ProxyShape(np.asarray(radii, dtype=np.float64), exponent), rotation)


def local_direction(points: np.ndarray, radii: np.ndarray) -> np.ndarray:
    """椭球上一点对应的**单位球方向** u（纹理参数化坐标，与代理网格同一套）。"""
    unit = points / radii
    norm = np.linalg.norm(unit, axis=1, keepdims=True)
    return np.divide(unit, norm, out=np.full_like(unit, np.nan), where=norm > 0)


def uv_index(direction: np.ndarray, columns: int, rows: int) -> tuple[np.ndarray, np.ndarray]:
    """单位球方向 → 纹理格索引（与 headproxy 的半格偏移约定一致）。

    非有限方向（未命中的射线）会被夹到边界格；调用方必须先按 `ok` 掩码过滤。
    """
    safe = np.where(np.isfinite(direction), direction, np.array([0.0, 0.0, 1.0]))
    theta = np.arctan2(safe[:, 1], safe[:, 0])
    phi = np.arccos(np.clip(safe[:, 2], -1.0, 1.0))
    col = np.floor((theta + np.pi) / (2 * np.pi) * columns).astype(np.int64)
    row = np.floor(phi / np.pi * rows).astype(np.int64)
    return np.clip(row, 0, rows - 1), np.clip(col, 0, columns - 1)


@dataclass
class TextureGrid:
    """反投影得到的纹理网格。`valid` 只反映「源图这一块有没有像素」。"""

    color: np.ndarray      # (rows, columns, 3) float
    valid: np.ndarray      # (rows, columns) bool
    hits: int
    duplicates: int

    def stats(self) -> dict[str, Any]:
        return {
            "hits": int(self.hits),
            "duplicates": int(self.duplicates),
            "valid_cells": int(self.valid.sum()),
            "coverage_of_grid": float(self.valid.mean()),
        }


def camera_basis(yaw: float) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """yaw 视角的相机基：视线方向 + 屏幕右方向 + 屏幕上方向。

    约定（必须写清楚，这一条曾经**写错并因此产生过假结论**）：
      - 源相机（正面）位于 -y，视线 `d = +y`；屏幕右 = `+x`（世界），屏幕上 = `+z`。
        因此世界 `+x` 是**观众视角的右侧**，也就是角色的**左侧**（正面对着观众）。
      - 头部向右转（yaw>0）时，相机要绕到角色的**左侧**去看：
        `d = (-sin yaw, cos yaw, 0)`，`right = (cos yaw, sin yaw, 0)`。
        此时屏幕右方向 = `+y`，而角色的鼻尖朝 `-y` —— 即**鼻尖出现在屏幕左侧**，
        这与「头右转 → 鼻子朝观众左边」的直觉一致。
      - 屏幕上看方向恒为世界 `+z`。

    历史缺陷：早先的实现固定屏幕平面为 x-z、把几何整体旋转 −yaw。
    那等价于**绕原点转动整个物体**，在 ±90° 时看到的仍是「原来的正面半球」，
    于是 ±90° 的纹理缺口被算成 ≈1%（真实值应为几十个百分点）。
    这个错误会让探针给出「±90° 几乎不缺纹理」的假结论，因此必须在代码里留档。
    """
    a = np.deg2rad(yaw)
    sight = np.array([-np.sin(a), np.cos(a), 0.0])   # 相机指向被摄物的单位视线
    right = np.array([np.cos(a), np.sin(a), 0.0])    # 屏幕右
    up = np.array([0.0, 0.0, 1.0])                   # 屏幕上
    return sight, right, up


@dataclass
class ViewRender:
    """单个视角的渲染结果。"""

    yaw: float
    color: np.ndarray      # (H, W, 3) float，未覆盖处为 0
    alpha: np.ndarray      # (H, W) bool：该像素有没有几何（头轮廓）
    filled: np.ndarray     # (H, W) bool：该像素的纹理是否 valid
    z: np.ndarray          # (H, W) float，最近交点深度（诊断用）
    world_per_pixel: float = 0.0   # 本帧的真实世界/像素比例（取景自适应，不能假定等于 pixel_scale）

    def hole_ratio(self) -> float:
        total = int(self.alpha.sum())
        if total == 0:
            return 0.0
        return float((self.alpha & ~self.filled).sum()) / float(total)

    def silhouette_bbox(self) -> list[int] | None:
        rows = np.nonzero(self.alpha.any(axis=1))[0]
        cols = np.nonzero(self.alpha.any(axis=0))[0]
        if rows.size == 0 or cols.size == 0:
            return None
        return [int(cols[0]), int(rows[0]), int(cols[-1]), int(rows[-1])]

    def asymmetry(self) -> float:
        """轮廓左右不对称度：|左半 - 右半| / 总面积。

        用来回答「屏幕上的鼻子究竟朝哪边」这类朝向问题——比肉眼看渲染图可靠：
        正视角（front）本身略有不对称（合成夹具故意如此），而 ±90° 的侧脸
        因为鼻尖凸向一侧，不对称度会明显更高。
        """
        total = int(self.alpha.sum())
        if total == 0:
            return 0.0
        mid = self.alpha.shape[1] // 2
        left = int(self.alpha[:, :mid].sum())
        right = int(self.alpha[:, mid:].sum())
        return abs(left - right) / float(total)

    def stats(self) -> dict[str, Any]:
        total = int(self.alpha.sum())
        holes = int((self.alpha & ~self.filled).sum())
        return {
            "yaw": float(self.yaw),
            "silhouette_pixels": total,
            "hole_pixels": holes,
            "hole_ratio": self.hole_ratio(),
            "silhouette_bbox": self.silhouette_bbox(),
            "asymmetry": self.asymmetry(),
        }


def project_source(proxy: HeadProxy, source_rgb: np.ndarray, source_alpha: np.ndarray,
                   pixel_scale: float, texture_rows: int = 256, texture_cols: int = 256,
                   supersample: int = 2, head_center: tuple[float, float] = (0.0, 0.0)) -> TextureGrid:
    """P0.3：把正面纹理反投影到代理纹理网格上。

    source_rgb    : (h, w, 3) float 0..1
    source_alpha  : (h, w) bool，True = 源图该像素属于角色
    pixel_scale   : 每个源像素对应的世界长度
    texture_*     : 纹理网格分辨率。**与代理网格分开**：代理格用于几何/面积统计，
                    纹理格用于采样，纹理格更细可以避免把「采样间距」误算成「纹理缺失」。
    supersample   : 每个源像素发射 supersample² 条射线。点采样会在纹理格上留下网格状空隙，
                    这些空隙会被渲染误判成「纹理缺失」，从而把采样密度问题伪装成遮挡问题。
    """
    h, w = source_alpha.shape
    if supersample < 1:
        raise ValueError("supersample 必须 ≥ 1")

    far = 10.0
    sub = (np.arange(supersample) + 0.5) / supersample
    col_px = (np.arange(w * supersample) // supersample) + np.tile(sub, w)      # 源像素列索引
    row_px = (np.arange(h * supersample) // supersample) + np.tile(sub, h)      # 源像素行索引

    # **几何在世界原点 = 头部中心**：源图像素 → 世界时先减头心偏移。
    # 诊断报告 D1：早先这里以图像中心为原点，而代理/渲染以头心为原点，
    # 于是采样窗口落在图像中心（合成夹具：行 254–770）而头在行 137–623 ——
    # 头顶 117 行从未被采样。纹理与可见面积双双错位，hole_ratio 无意义。
    world_x = (col_px - w / 2.0) * pixel_scale - head_center[0]
    world_z = -(row_px - h / 2.0) * pixel_scale - head_center[1]
    grid_x, grid_z = np.meshgrid(world_x, world_z)
    origins = np.stack([grid_x.ravel(), np.full(grid_x.size, -far), grid_z.ravel()], axis=1)

    ok, points, normals = raycast_ellipsoid(origins, SOURCE_SIGHT, proxy.radii, exponent=proxy.exponent)
    # 只保留源相机能看到的半球：朝相机的面满足 n·sight < 0（相机在 -y 侧）。
    facing = ok & ((normals @ SOURCE_SIGHT) < 0.0)
    direction = local_direction(points, proxy.radii)
    tex_row, tex_col = uv_index(direction, texture_cols, texture_rows)

    color = np.zeros((texture_rows, texture_cols, 3), dtype=np.float64)
    valid = np.zeros((texture_rows, texture_cols), dtype=bool)

    # 采样点 → 源像素（用于取色与 alpha 判定）
    src_col = (np.arange(w * supersample) // supersample)[None, :]
    src_row = (np.arange(h * supersample) // supersample)[:, None]
    src_col = np.broadcast_to(src_col, (h * supersample, w * supersample)).ravel()
    src_row = np.broadcast_to(src_row, (h * supersample, w * supersample)).ravel()
    src_index = src_row * w + src_col

    hits = 0
    duplicates = 0
    for index in np.nonzero(facing)[0]:
        source_pixel = src_index[index]
        if not source_alpha.reshape(-1)[source_pixel]:
            continue
        r, c = int(tex_row[index]), int(tex_col[index])
        hits += 1
        if valid[r, c]:
            duplicates += 1
            continue
        color[r, c] = source_rgb.reshape(-1, 3)[source_pixel]
        valid[r, c] = True
    return TextureGrid(color=color, valid=valid, hits=hits, duplicates=duplicates)


def symmetry_mirror(texture: TextureGrid, proxy: HeadProxy) -> TextureGrid:
    """A 级补全：按对称平面 x=0 镜像已有纹理（计划 §5.2 A）。

    复制的是**本角色自己的原像素**，没有任何生成；部位/笔画对应仍须单独验收。
    实现直接在世界系里翻 x 分量（`p.y` 不变、`p.x → -p.x`），
    而不是在角度域里推索引——角度域的绕序与符号最容易写反。
    镜像映射在**纹理网格**上计算（纹理格可能比代理格更细）。
    """
    from geom import grid_directions

    tex_rows, tex_cols = texture.valid.shape
    directions = grid_directions(tex_cols, tex_rows)
    mirrored = directions.copy()
    mirrored[..., 0] *= -1.0
    # 纹理格表示 project_source/render_view 使用的归一化局部方向，不是
    # 代理的 surface_point 参数。再变曲面并归一化世界坐标会在非均匀半径下错格。
    m_row, m_col = uv_index(mirrored.reshape(-1, 3), tex_cols, tex_rows)
    m_row = m_row.reshape(tex_rows, tex_cols)
    m_col = m_col.reshape(tex_rows, tex_cols)

    valid = texture.valid.copy()
    color = texture.color.copy()
    fill = (~valid) & texture.valid[m_row, m_col]
    color[fill] = texture.color[m_row, m_col][fill]
    valid |= fill
    return TextureGrid(color=color, valid=valid, hits=texture.hits, duplicates=texture.duplicates)


def dilate_texture(texture: TextureGrid, proxy: HeadProxy, iterations: int = 2) -> TextureGrid:
    """把已 valid 的格向外扩 `iterations` 格（A 级的一部分：**采样间距补齐**，不是补内容）。

    为什么必须有这一步：正向反投影时，源图约 3.6 个像素才落进一个纹理格（见 probe.json 的
    `source_pixels_per_texel`），格与格之间天然有空隙。渲染时这些空隙会被算成「纹理缺失」，
    于是把**采样密度**误报成**遮挡空洞**——那会让 ±45° 的残留被人为放大。
    本函数只搬运**本图已存在的像素**，不生成任何新内容，因此不引入身份风险。
    """
    valid = texture.valid.copy()
    color = texture.color.copy()
    for _ in range(iterations):
        # 经度左右环绕；纬度顶部/底部不是同一点，禁止北极直接绕到南极。
        # mask 与颜色必须采用同一个边界规则，否则会把无效边缘颜色标成有效。
        padded_valid = np.pad(np.pad(valid, ((0, 0), (1, 1)), mode="wrap"),
                              ((1, 1), (0, 0)), mode="constant")
        padded_color = np.pad(np.pad(color, ((0, 0), (1, 1), (0, 0)), mode="wrap"),
                              ((1, 1), (0, 0), (0, 0)), mode="constant")
        new_valid = valid.copy()
        new_color = color.copy()
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                if dr == 0 and dc == 0:
                    continue
                source_valid = padded_valid[1 + dr:1 + dr + valid.shape[0], 1 + dc:1 + dc + valid.shape[1]]
                source_color = padded_color[1 + dr:1 + dr + valid.shape[0], 1 + dc:1 + dc + valid.shape[1]]
                take = (~new_valid) & source_valid
                new_color[take] = source_color[take]
                new_valid |= take
        valid, color = new_valid, new_color
    return TextureGrid(color=color, valid=valid, hits=texture.hits, duplicates=texture.duplicates)


def render_view(proxy: HeadProxy, texture: TextureGrid, yaw: float, resolution: int,
                pixel_scale: float, center: tuple[float, float] = (0.0, 0.0),
                fit: float = 1.15) -> ViewRender:
    """P0.4：渲染指定 yaw 的视角。

    相机绕世界 z 轴环绕（几何**不动**，见 [camera_basis] 的约定与历史缺陷说明），
    正交投影：屏幕像素 (u, v) → 射线起点 `u*right + v*up - far*sight`，方向 `sight`。

    **取景由 `fit` 决定，而不是固定像素窗口**：取景半宽 = `fit × max(rx, 0.72·rz)`。
    早先用「640 像素宽的固定窗口」——那个窗口的垂直半幅由 pixel_scale 推出来，
    而头高（487px）超过它（384px）时，轮廓上下就被画面裁掉，
    `silhouette_matches_headbox` 与 `head_visible_in_frame` 会同时失败（实测比值 3.43）。
    固定窗口在「头多高」这件事上做了隐含假设，而头高恰恰是随输入变化的。
    """
    if fit <= 1.0:
        raise ValueError("fit 必须 > 1（要留出边距，否则轮廓会触边）")
    # 屏幕上的有效对象半宽：环绕到侧面时看到的是深度方向（ry = 0.72·rx），取两者较大者以包住全角度。
    object_half_x = max(float(proxy.radii[0]), float(proxy.radii[2]) * float(proxy.radii[1]) / float(proxy.radii[0]))
    object_half_z = float(proxy.radii[2])
    half_span = max(object_half_x, object_half_z) * fit
    step = 2.0 * half_span / resolution

    sight, right, up = camera_basis(yaw)
    far = 10.0
    half = resolution / 2.0
    us = (np.arange(resolution) + 0.5 - half) * step
    # 图像行号向下增加，世界 z 向上增加；与 project_source 使用同一符号。
    vs = -(np.arange(resolution) + 0.5 - half) * step
    uu, vv = np.meshgrid(us, vs)
    # 窗口**恒朝世界原点**（= 头心，见 project_source 的说明）。
    # `center` 保留在签名里只是为了向后兼容，正常调用应传 (0, 0)；
    # 早先的写法是「几何在原点、把窗口挪到头的位置」，那会把头推出画面
    # （实测真实回执的 front 轮廓 bbox 上界为 0，即只剩顶部一条）。
    base = np.outer(uu.ravel(), right) + np.outer(vv.ravel(), up)
    if center != (0.0, 0.0):
        base[:, 0] += center[0]
        base[:, 2] += center[1]
    origins = base - far * sight

    ok, points, normals = raycast_ellipsoid(origins, sight, proxy.radii, exponent=proxy.exponent)
    # 背面剔除：相机在 -far*sight 处，朝相机的面满足 n·sight < 0。
    # （取球面例子即可验证：相机在 -y 侧、sight=+y 时，朝向相机的表面在 y=-R，法线 n=(0,-1,0)，
    #  n·sight = -1 < 0。这正是**正面**表面——相机与法线在切平面两侧。）
    # 这个符号曾写反，使正面视角被判成 99.98% 空洞；run_probe.py 的硬自检不允许它再回归。
    ok &= (normals @ sight) < 0.0
    if not ok.any():
        # 空画面必须显式暴露：早先的空图被当成「空洞率 0」静默通过过。
        raise ValueError(
            f"yaw={yaw} 渲染为空：没有任何射线命中代理。"
            "通常是取景窗口与代理尺寸不匹配（见 render_view 的 fit 参数）或背面剔除写反。",
        )
    # 深度：沿视线的距离（越小越近）
    depth = (points - origins) @ sight

    color = np.zeros((resolution, resolution, 3), dtype=np.float64)
    alpha = np.zeros((resolution, resolution), dtype=bool)
    filled = np.zeros((resolution, resolution), dtype=bool)
    z = np.full((resolution, resolution), np.nan, dtype=np.float64)

    local = local_direction(points, proxy.radii)
    tex_row, tex_col = uv_index(local, texture.valid.shape[1], texture.valid.shape[0])

    for index in np.nonzero(ok)[0]:
        r = index // resolution
        c = index % resolution
        alpha[r, c] = True
        tr, tc = int(tex_row[index]), int(tex_col[index])
        if texture.valid[tr, tc]:
            color[r, c] = texture.color[tr, tc]
            filled[r, c] = True
        if not np.isfinite(z[r, c]) or depth[index] < z[r, c]:
            z[r, c] = depth[index]
    return ViewRender(yaw=yaw, color=color, alpha=alpha, filled=filled, z=z, world_per_pixel=step)
