"""P0 探针 — 头部代理构造（计划 §1.1 P0.2）。

许可纪律（计划 §1.3 的「许可陷阱」一节，必须一起查）：

    候选方案                          依赖              许可风险
    DECA / EMOCA                     FLAME 模型        **非商业研究许可**，不可随商用品分发
    经典 3DMM                        BFM               **非商业**
    MediaPipe Face Mesh              自带规范脸模型     Apache-2.0，无上述问题
    Depth Anything V2 / MiDaS        深度估计          代码宽松；权重条款需单独核

本模块实现参数化后端，另记录一个尚未接入的候选后端：

1. `backend="parametric"`（默认，探针立即用）：
   参数化椭球头 + 同心壳头发。**不含任何第三方权重、不下载任何东西**，
   因此许可上无任何疑问。它不是「单图重建」，只是探针用的确定性几何代理；
   本探针要测的是「正面纹理反投影到代理后侧面缺多少面积」，
   这个量与所用代理的具体来源无关，只与代理的拓扑/曲率有关。

2. `backend="mediapipe"`（候选，尚未实现；显式拒绝）：
   用 MediaPipe Face Mesh 的 468 点规范脸几何作为代理。
   `license_report()` 里显式记录它是 Apache-2.0 且**不**依赖 FLAME / BFM，
   是计划 §1.3 提到的候选。探针未实现该接口，不应把安装依赖称为已可切换；
   如需接入，须先核实所用模型权重与资源的具体条款。

不得宣称：本模块产出的「对称平面 + 深度 + 网格」是**探针几何**，
不是产品能力；代理不覆盖头发与身体的边界同计划 §4.3。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np

from geom import DEFAULT_EXPONENT, ProxyShape, grid_directions, surface_normal, surface_point

# 规范脸代理的椭球半径（世界单位；世界单位由相机矩阵定义，见 camera.py）
DEFAULT_RADII = np.array([0.50, 0.46, 0.62], dtype=np.float64)

# 光头壳的厚度与覆盖范围：后脑与顶部（计划 §4.3「头发是壳层」）
HAIR_SHELL_THICKNESS = 0.10
HAIR_SHELL_BACK_COS = -0.20   # ny < 该值视为后脑
HAIR_SHELL_TOP_COS = 0.55     # nz > 该值视为顶部


@dataclass(frozen=True)
class HeadProxy:
    """头部代理：规则网格 + 逐格法线 + 对称平面。确定性构造，无随机。

    形状是**超椭球**（`shape.exponent = 2` 时退化为椭球），原因见 geom.py 的模块说明：
    纯椭球的正面投影是椭圆，盖不住接近圆角矩形的头部轮廓，会在正面制造假缺口。
    """

    columns: int
    rows: int
    centers: np.ndarray        # (rows, columns, 3) 世界坐标
    normals: np.ndarray        # (rows, columns, 3) 单位外法线
    symmetry_plane: np.ndarray  # (4,) nx, ny, nz, d —— 世界系里 x=0 平面
    radii: np.ndarray          # (3,)
    backend: str
    grid_theta: np.ndarray     # (columns,)  方位角采样
    grid_phi: np.ndarray       # (rows,)     极角采样
    exponent: float = DEFAULT_EXPONENT

    @property
    def shape(self) -> ProxyShape:
        return ProxyShape(self.radii, self.exponent)

    @property
    def cell_count(self) -> int:
        return self.rows * self.columns

    def cell_area(self) -> np.ndarray:
        """每格面积权（用于按**面积**而非按格数统计占比）。

        参数化非均匀：极区每格的实际面积更小。若按格数统计，
        极区会在比例里被高估，±90° 的空洞占比会被算错。
        """
        return np.abs(np.sin(self.grid_phi))[:, None] * np.ones((1, self.columns)) * (np.pi / self.rows) * (2 * np.pi / self.columns)

    def as_dict(self) -> dict[str, Any]:
        return {
            "columns": self.columns,
            "rows": self.rows,
            "cell_count": self.cell_count,
            "backend": self.backend,
            "radii": [float(v) for v in self.radii],
            "exponent": float(self.exponent),
            "shape": self.shape.as_dict()["shape"],
            "symmetry_plane": [float(v) for v in self.symmetry_plane],
        }


def build_proxy(columns: int = 96, rows: int = 96, radii: np.ndarray | None = None,
                backend: str = "parametric", exponent: float = DEFAULT_EXPONENT) -> HeadProxy:
    """构造头部代理。默认参数化椭球（许可干净、无权重、确定性）。"""
    if backend != "parametric":
        raise ValueError(
            f"后端 {backend!r} 未在探针中实现。MediaPipe 后端需要另行安装 mediapipe，"
            "并按 docs/PROBE_P0.md 的说明切换；探针默认不使用任何第三方权重。"
        )
    for label, value in (("columns", columns), ("rows", rows)):
        if not isinstance(value, int) or isinstance(value, bool) or not 2 <= value <= 256:
            raise ValueError(f"{label} 必须为 2..256 的整数（探针网格资源上限）")
    r = np.array(DEFAULT_RADII if radii is None else radii, dtype=np.float64)
    if r.shape != (3,) or not np.all(np.isfinite(r)) or np.any(r <= 0):
        raise ValueError("半径必须是三个正有限数")

    shape = ProxyShape(r, exponent)
    centers = surface_point(grid_directions(columns, rows), shape)
    normals = surface_normal(centers, shape)

    return HeadProxy(
        columns=columns,
        rows=rows,
        centers=centers,
        normals=normals,
        symmetry_plane=np.array([1.0, 0.0, 0.0, 0.0]),
        radii=r,
        backend=backend,
        grid_theta=(np.arange(columns) + 0.5) / columns * 2 * np.pi - np.pi,
        grid_phi=(np.arange(rows) + 0.5) / rows * np.pi,
        exponent=float(exponent),
    )


def mirror_cell_index(proxy: HeadProxy, cells: np.ndarray) -> np.ndarray:
    """按对称平面 (x=0) 求镜像格索引。用于 A 级对称镜像补全（计划 §5.2 A）。"""
    cols = proxy.columns
    # theta 均匀采样：镜像 = 关于 0 的方位角取反。
    # 网格是半格偏移的，故用最近邻在 theta 轴上定位。
    theta = proxy.grid_theta
    mirrored_theta = -theta
    order = np.argsort(theta)
    sorted_theta = theta[order]
    idx = np.searchsorted(sorted_theta, mirrored_theta)
    idx = np.clip(idx, 1, cols - 1)
    left = sorted_theta[idx - 1]
    right = sorted_theta[idx]
    choose_left = np.abs(mirrored_theta - left) <= np.abs(mirrored_theta - right)
    mirrored = np.where(choose_left, order[idx - 1], order[idx])
    return mirrored[cells[..., 1]]


def hair_shell_mask(proxy: HeadProxy) -> np.ndarray:
    """头发壳层候选区域（后脑 + 顶部）。探针只用来标注，不参与投影。"""
    ny = proxy.normals[..., 1]
    nz = proxy.normals[..., 2]
    return (ny < HAIR_SHELL_BACK_COS) | (nz > HAIR_SHELL_TOP_COS)


def license_report() -> dict[str, Any]:
    """许可核查回执（计划 §1.3 要求探针必须一起查，不得留到阶段 5）。"""
    return {
        "backend_used": "parametric",
        "third_party_weights": [],
        "network_access": False,
        "declared_risks": {
            "FLAME": "非商业研究许可；本探针未使用 DECA/EMOCA，故不涉及。",
            "BFM": "非商业；本探针未使用经典 3DMM，故不涉及。",
            "MediaPipe Face Mesh": "候选，尚未实现、未安装、未使用；所用资源条款须在接入时核实。",
            "Depth Anything V2 / MiDaS": "代码宽松，权重条款需单独核；本探针未使用深度估计。",
        },
        "conclusion": "本探针不含任何需要许可核查的权重，因此 P0 的结论不受许可阻塞。",
        "caveat": "阶段 1 的 BiSeNet 权重（CelebAMask-HQ 训练）仍需单独核实，见 docs/LICENSING.md。",
    }
