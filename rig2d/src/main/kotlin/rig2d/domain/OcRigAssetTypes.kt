package rig2d.domain

/*
 * schema v2 数据结构：分层多视角资产。
 *
 * 依据：实施计划 §2.1 / 设计文档 §4 阶段 0（建议形态），并记录了两处必要偏离：
 *   1. `proxy` 从 `OcAssetRef?` 升级为 `OcProxy`（网格 + 对称平面 + 深度/法线）。
 *      理由：计划 §4.1 要求阶段 2 产出「对称平面 / 深度+法线 / 变形网格」三样，
 *      三者必须一起校验，否则「不同图之间对称平面翻转」（计划 §4.4）只能到运行期才发现。
 *   2. `mesh` 从 `OcAssetRef` 升级为 `OcViewMesh`，因为「顶点数 = (columns+1)*(rows+1)」
 *      这个契约无法从不透明文件引用里校验（计划 §2.1 最后一条要求它必须被拒）。
 *
 * v1 档案继续可读（见 OcIdentityV1.kt 与 LegacyRigAdapter）；v2 是新路线，不是替换。
 *
 * 不得宣称：契约就位**不等于**「支持 ±90°」（计划 §2.5）。
 */

/** 带外观版本的资源引用。visualVersion 对齐 zhiyu 既有 `OcPendingPublication.expectedVisualVersion`。 */
data class OcAssetRefV2(val id: String, val sha256: String, val visualVersion: Int)

/** 3D 头部代理。范围边界见计划 §4.3：代理只覆盖脸；头发是壳层；身体不在代理内。 */
data class OcProxy(
    val mesh: OcAssetRefV2,
    /** 对称平面 (nx, ny, nz, d)，法线必须已归一化；A 级补全据此从启发式变成几何推导。 */
    val symmetryPlane: List<Float>,
    val depth: OcAssetRefV2,
    val normal: OcAssetRefV2? = null,
    val version: Int = 1,
)

/** 单层资产。z 越大越靠前；depth 是代理深度，用于层序校验（计划 §4.2 LayerOrderRules）。 */
data class OcLayerAsset(
    val id: String,
    /** 层类别，见 OcRigAssetRules.layerKinds（设计文档 §6.1 部件类别）。 */
    val kind: String,
    val z: Int,
    val depth: Float,
    val asset: OcAssetRefV2,
    /** 归一化 x, y, w, h。 */
    val bounds: List<Float>,
    /** 边界外扩冗余（0..1，相对层宽）。露缝的根源，硬门禁项：计划 §5.3。 */
    val margin: Float,
)

/** 一个视角的完整层栈。`rig` **必须每视角独立**（计划 §7.2）：±90° 侧脸眨眼 ≠ 正面眨眼。 */
data class OcViewLayers(
    /** "front" | "right45" | "right90" | "left45" | "left90"，也允许后续新增自有视角。 */
    val viewId: String,
    val yawCenter: Float,
    /** 该视角有效半宽，计划 §5.1 建议 30。 */
    val yawHalfWidth: Float,
    val layers: List<OcLayerAsset>,
    val rig: OcRigV1,
    val mesh: OcViewMesh,
)

/**
 * 分层多视角资产（schema v2）。
 *
 * `approvedAt` 是导入期人工修整完成的时刻（计划 §6：自动补全不足以无人值守，
 * 未人工确认的资产不得参与发布）。0 表示尚未人工确认。
 */
data class OcRigAsset(
    val views: List<OcViewLayers>,
    val sourceSha: String,
    val approvedAt: Long,
    val proxy: OcProxy? = null,
    val schemaVersion: Int = OcRigAssetRules.SCHEMA_VERSION,
)
