package rig2d.domain

/*
 * schema v2 契约：分层多视角资产。
 *
 * 依据：实施计划 §2.1 / 设计文档 §4 阶段 0（建议形态）。
 * 与设计文档的差异（如实记录，不隐藏）：
 *   1. 设计文档写 `proxy: OcAssetRef?`；本实现把代理从「单个文件引用」升级为
 *      `OcProxy`（网格 + 对称平面 + 深度/法线），因为阶段 2 的三样产出
 *      （对称平面 / 深度+法线 / 变形网格，计划 §4.1）必须一起被校验，
 *      否则「对称平面翻转」只能到运行期才发现。
 *   2. 设计文档写 `mesh: OcAssetRef`；mesh 的顶点数契约
 *      `(columns+1)*(rows+1)` 无法从一个不透明文件引用里校验，
 *      故引入 `OcViewMesh(columns, rows, asset, vertexCount)`，并新增
 *      `OcMeshRules` 做顶点数与顶点有限性校验（对应计划 §2.1 最后一条）。
 *   3. 新增 `OcAssetRefV2.visualVersion`：对齐 FrozenBudget 的既有
 *      `expectedVisualVersion` 概念（zhiyu OcPendingPublication），迁移用。
 *
 * 全部校验 fail closed，沿用 zhiyu 现有 `require` + 中文消息风格。
 *
 * 不得宣称：契约就位**不等于**「支持 ±90°」。契约只是让后续阶段有可能（计划 §2.5）。
 */

// ---------------------------------------------------------------- 常量

object OcViewConstants {
    /** 计划 §5.1 锁定的 5 视角：front(0) / ±45 / ±90。 */
    const val FRONT = "front"
    const val RIGHT45 = "right45"
    const val RIGHT90 = "right90"
    const val LEFT45 = "left45"
    const val LEFT90 = "left90"

    /** 规范顺序：+90 → +45 → 0 → -45 → -90。任何资产都按此顺序归一化。 */
    val ORDER = listOf(RIGHT90, RIGHT45, FRONT, LEFT45, LEFT90)

    val REQUIRED_IDS: Set<String> = ORDER.toSet()

    fun centerOf(viewId: String): Float = when (viewId) {
        RIGHT90 -> 90f
        RIGHT45 -> 45f
        FRONT -> 0f
        LEFT45 -> -45f
        LEFT90 -> -90f
        else -> throw IllegalArgumentException("未知视角：$viewId")
    }

    fun idOf(center: Float): String = when (center) {
        90f -> RIGHT90; 45f -> RIGHT45; 0f -> FRONT; -45f -> LEFT45; -90f -> LEFT90
        else -> throw IllegalArgumentException("未知视角中心：$center")
    }
}

object OcRigAssetRules {
    const val SCHEMA_VERSION = 2
    const val MAX_VIEWS = 8
    const val MAX_LAYERS_PER_VIEW = 64
    const val MAX_REGIONS_PER_VIEW = 128
    const val MAX_LAYER_KINDS = 64
    const val MIN_VIEWS = 1
    const val YAW_MIN = -90f
    const val YAW_MAX = 90f
    const val YAW_HALF_MIN = 5f
    const val YAW_HALF_MAX = 45f
    /** 计划 §5.3：层冗余允许 [0,1]，1 表示「与原区域等宽」。 */
    const val MARGIN_MIN = 0f
    const val MARGIN_MAX = 1f
    /** depth 是代理深度，归一化到 -1..1。 */
    const val DEPTH_MIN = -1f
    const val DEPTH_MAX = 1f
    const val Z_MIN = -128
    const val Z_MAX = 128
    const val BOUNDS_EPSILON = 1.001f

    /** 设计文档 §6.1 的部件层类别（BiSeNet 类别 → 绑定层）。 */
    val layerKinds: Set<String> = setOf(
        "back_hair", "body", "arm_l", "arm_r", "neck", "face",
        "ear_l", "ear_r", "front_hair", "brow_l", "brow_r",
        "eye_white_l", "eye_white_r", "iris_l", "iris_r", "lid_l", "lid_r",
        "mouth", "accessory",
    )

    private val idPattern = Regex("[a-zA-Z0-9_-]{1,80}")
    private val sha = Regex("[a-f0-9]{64}")
    private val viewIdPattern = Regex("[a-z0-9_]{1,24}")

    fun check(asset: OcRigAsset): OcRigAsset {
        require(asset.schemaVersion == SCHEMA_VERSION) { "分层资产的 schema 版本不是 v2" }
        require(sha.matches(asset.sourceSha)) { "源图校验值无效" }
        require(asset.approvedAt >= 0L) { "资产批准时间无效" }
        require(asset.views.size in MIN_VIEWS..MAX_VIEWS) { "视角数量超出 1..$MAX_VIEWS" }
        require(asset.views.map { it.viewId }.distinct().size == asset.views.size) { "视角编号重复" }
        val checkedViews = asset.views.map(::checkView)
        val checkedProxy = asset.proxy?.let(::checkProxy)
        val sorted = checkedViews.sortedByDescending { it.yawCenter }
        OcViewBlendRules.validateBands(sorted.map { it.yawCenter to it.yawHalfWidth })
        return asset.copy(views = sorted, proxy = checkedProxy)
    }

    fun isValid(asset: OcRigAsset): Boolean = try {
        check(asset); true
    } catch (_: IllegalArgumentException) {
        false
    }

    /** 严格判定（fail closed）。需要调用方给出拒绝原因时用这个。 */
    fun requireValid(asset: OcRigAsset) {
        check(asset)
    }

    private fun checkView(view: OcViewLayers): OcViewLayers {
        require(viewIdPattern.matches(view.viewId)) { "视角编号无效" }
        require(view.yawCenter.isFinite() && view.yawCenter in YAW_MIN..YAW_MAX) { "视角 yaw 超出 ±90" }
        require(view.yawHalfWidth.isFinite() && view.yawHalfWidth in YAW_HALF_MIN..YAW_HALF_MAX) { "视角带宽超出 5..45" }
        require(view.layers.size in 1..MAX_LAYERS_PER_VIEW) { "视角层数超出 1..$MAX_LAYERS_PER_VIEW" }
        require(view.layers.map { it.id }.distinct().size == view.layers.size) { "层编号重复" }
        require(view.layers.map { it.z }.distinct().size == view.layers.size) { "层序 z 冲突：同一视角内 z 必须唯一" }
        view.layers.forEach(::checkLayer)
        // 每视角独立 rig（计划 §7.2）：区域数上限与 v1 一致，区域本体复用 v1 校验。
        require(view.rig.regions.size <= MAX_REGIONS_PER_VIEW) { "视角动画区域过多" }
        val checkedRig = OcRigRulesV1.check(view.rig)
        checkMesh(view.mesh)
        return view.copy(rig = checkedRig, layers = view.layers.map { it.copy(bounds = it.bounds.toList()) })
    }

    private fun checkLayer(layer: OcLayerAsset) {
        require(idPattern.matches(layer.id)) { "层编号无效" }
        require(layer.kind in layerKinds) { "未知层类别：${layer.kind}" }
        require(layer.z in Z_MIN..Z_MAX) { "层序超出 $Z_MIN..$Z_MAX" }
        require(layer.depth.isFinite() && layer.depth in DEPTH_MIN..DEPTH_MAX) { "代理深度超出 -1..1" }
        checkReference(layer.asset)
        require(layer.bounds.size == 4 && layer.bounds.all { it.isFinite() }) { "层范围必须是 4 个有限数" }
        val (x, y, w, h) = layer.bounds
        require(x >= 0f && y >= 0f && w > 0f && h > 0f) { "层范围必须为非负起点与正尺寸" }
        require(x + w <= BOUNDS_EPSILON && y + h <= BOUNDS_EPSILON) { "层范围超出图片" }
        require(layer.margin.isFinite() && layer.margin in MARGIN_MIN..MARGIN_MAX) { "层冗余超出 0..1" }
    }

    private fun checkMesh(mesh: OcViewMesh) = OcMeshRules.check(mesh)

    internal fun checkReference(ref: OcAssetRefV2) {
        require(idPattern.matches(ref.id)) { "资源编号无效" }
        require(sha.matches(ref.sha256)) { "资源校验值无效" }
        require(ref.visualVersion >= 0) { "资源外观版本无效" }
    }

    private fun checkProxy(proxy: OcProxy): OcProxy {
        require(proxy.version == 1) { "不支持的代理版本" }
        listOfNotNull(proxy.mesh, proxy.depth, proxy.normal).forEach(::checkReference)
        val plane = proxy.symmetryPlane.toList()
        require(plane.size == 4 && plane.all { it.isFinite() }) { "对称平面必须是 4 个有限数（nx, ny, nz, d）" }
        val (nx, ny, nz) = plane.take(3)
        val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        // 单位法线：避免「对称平面退化成点」这种在运行期才表现为镜像乱码的错误。
        require(kotlin.math.abs(length - 1f) <= 1e-3f) { "对称平面法线未归一化" }
        require(plane[3].isFinite()) { "对称平面偏移无效" }
        return proxy.copy(symmetryPlane = plane)
    }
}

/**
 * 变形网格。顶点数契约来自计划 §2.1：
 * `mesh 顶点数 = (columns+1)*(rows+1)`，`columns/rows in 2..64`（复用 OcRigRules.vertices 的上限）。
 *
 * 这里用显式 `vertexCount` 表达「资源里到底有多少顶点」，因为签名过的资源是字节，
 * 校验时必须能**不加载图片**就拒绝顶点数不符的资产。
 */
data class OcViewMesh(
    val columns: Int,
    val rows: Int,
    val asset: OcAssetRefV2,
    val vertexCount: Int = (columns + 1) * (rows + 1),
)

object OcMeshRules {
    fun check(mesh: OcViewMesh) {
        require(mesh.columns in 2..64 && mesh.rows in 2..64) { "网格尺寸超出 2..64" }
        val expected = (mesh.columns + 1) * (mesh.rows + 1)
        require(mesh.vertexCount == expected) { "网格顶点数必须是 (columns+1)*(rows+1)=$expected，实际 ${mesh.vertexCount}" }
        OcRigAssetRules.checkReference(mesh.asset)
    }

    /** 顶点缓冲实数校验：任何非有限数（NaN/Inf）立即拒绝，不进入渲染。 */
    fun checkVertices(vertices: FloatArray, mesh: OcViewMesh) {
        check(mesh)
        require(vertices.size == mesh.vertexCount * 2) { "顶点缓冲长度必须是 vertexCount*2=${mesh.vertexCount * 2}" }
        require(vertices.all { it.isFinite() }) { "顶点缓冲含非有限数" }
    }
}
