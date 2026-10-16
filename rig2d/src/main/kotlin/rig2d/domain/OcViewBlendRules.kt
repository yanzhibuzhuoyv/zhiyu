package rig2d.domain

import kotlin.math.abs

/*
 * 视角混合规则（计划 §2.1 新增文件之一）+ margin 充足性（计划 §5.3 硬门禁）。
 *
 * 视角带（计划 §5.1，已锁定）：
 *   front  0    -30..+30
 *   right45 +45 +15..+75
 *   right90 +90 +60..+90
 *   left45  -45 -75..-15
 *   left90  -90 -90..-60
 * 相邻重叠 15° 用于交叉淡入。
 *
 * 三条规则必须成立，测试逐条覆盖（计划 §2.3）：
 *   1. 在 0/±45/±90 退化为单视角（权重恰为 1，另一侧不参与）；
 *   2. 在交叉点两权重之和为 1（任何 yaw 都是 1，这是不变量）；
 *   3. 权重对 yaw 单调、且连续（C1：用 smoothstep 而非线性，避免交叉点折角）；
 *   4. 带宽外拒绝（yaw 超出全部视角带的并集 → 拒绝，不静默外推）。
 */

/** 单个视角的混合权重。yaw 恰好落在视角中心时 weight == 1。 */
data class ViewWeight(val viewId: String, val weight: Float)

/** 视角带定义（视角中心 + 有效半宽）。 */
data class ViewBand(val viewId: String, val center: Float, val halfWidth: Float)

object OcViewBlendRules {
    /** 5 视角规范带宽，计划 §5.1。 */
    const val REQUIRED_HALF_WIDTH = 30f

    /** 相邻视角交叉淡入重叠，计划 §5.1 / §7.1。 */
    const val CROSSFADE_DEGREES = 15f

    /** 计划 §5.3：每层补全后可见区向外多留 10–15% 边距，冗余也参与补全。 */
    const val MARGIN_TARGET_MIN = 0.10f
    const val MARGIN_TARGET_MAX = 0.15f

    /** 用于浮点比较的 1° 容差（视角中心都是整数度，容差只吃浮点误差）。 */
    const val YAW_EPSILON = 1e-3f

    private const val MIN_HALF_WIDTH = 5f
    private const val MAX_HALF_WIDTH = 45f

    /** 与 yaw 相关的视图范围检查。 */
    fun inRange(yaw: Float): Boolean = yaw.isFinite() && yaw >= OcRigAssetRules.YAW_MIN && yaw <= OcRigAssetRules.YAW_MAX

    /**
     * 带宽校验：fail closed。
     *
     * 拒绝条件（计划 §2.3「视角带不重叠 → 全部拒绝」）：
     *   - 半宽超出 5..45；
     *   - 中心重复（同一角度两个视角，混合无定义）；
     *   - 存在 > 容差的空隙（漏角比糊更糟：设计文档 §4 阶段 0）。
     *
     * 允许的**不是**错误的情况：视角按中心降序时不覆盖 ±90 两端仍然通过——
     * 「只做 ±45」是计划 §1.4 的让步选项之一，是否可接受由调用方判定，不由本函数替用户决定。
     */
    fun validateBands(bands: List<Pair<Float, Float>>) {
        require(bands.isNotEmpty()) { "视角带不能为空" }
        bands.forEach { (center, halfWidth) ->
            require(center.isFinite() && center in OcRigAssetRules.YAW_MIN..OcRigAssetRules.YAW_MAX) { "视角中心超出 ±90" }
            require(halfWidth.isFinite() && halfWidth in MIN_HALF_WIDTH..MAX_HALF_WIDTH) { "视角带宽超出 5..45" }
        }
        val sorted = bands.sortedBy { it.first }
        sorted.zipWithNext().forEach { (left, right) ->
            require(right.first - left.first > YAW_EPSILON) { "视角中心重复：${left.first}" }
            val overlap = (left.first + left.second) - (right.first - right.second)
            require(overlap >= -YAW_EPSILON) { "视角带不重叠，存在漏角：${left.first} 与 ${right.first} 之间" }
        }
    }

    /** 5 视角是否为计划锁定的形状（顺序 + 中心 + 带宽）。 */
    fun isCanonicalFive(bands: List<Pair<Float, Float>>): Boolean {
        if (bands.size != OcViewConstants.ORDER.size) return false
        validateBands(bands)
        return bands.all { (center, halfWidth) ->
            abs(halfWidth - REQUIRED_HALF_WIDTH) <= YAW_EPSILON && OcViewConstants.ORDER.any { abs(OcViewConstants.centerOf(it) - center) <= YAW_EPSILON }
        }
    }

    /**
     * yaw → 最多两个相邻视角的混合权重。
     *
     * 权重来源是**与 yaw 相邻的两个视角中心之间的 smoothstep** `S(t) = t²(3-2t)`：
     *   - 在视角中心：退化为单视角，权重恰为 1（t=0 → S=0，且判等用容差，保证精确 1）；
     *   - 在相邻中心的中点（即交叉点 ±22.5 / ±67.5）：两权重恰为 0.5/0.5，和为 1；
     *   - 全域权重和恒为 1，且 C1 连续（线性插值会在交叉点留折角）。
     *
     * 为什么不用「当前带内 + 邻带」的局部插值：按视角视野逐带插值会在带边界出现折角，
     * 且「加性混合两带」在重叠区之外没有定义。
     *
     * 带宽只用于**覆盖判定**（yaw 必须落在某个视角带内，否则拒绝静默外推），
     * 不参与求值——因此带可以比半间距宽（重叠）或窄（漏角），后者被 validateBands 拒绝。
     *
     * 回归证据：第一版用「取第一个中心 ≥ yaw 的带」做锚点，在 yaw=-67.5 时锚点错选 left90、
     * 在 ±90 端点又把 ±45 一起带进来——两个都是真 bug，由交叉点与连续性测试抓到
     * （见 artifacts/stage0-red-2026-10-12.log）。
     *
     * @throws IllegalArgumentException yaw 非有限数 / 超出 ±90 / 不落在任何视角带内 / 视角中心重复。
     */
    fun blend(bands: List<ViewBand>, yaw: Float): List<ViewWeight> {
        val (left, right) = pair(bands, yaw)
        // 视角中心处退化为单视角（权重恰为 1），不把零权重的邻视角塞进结果。
        if (right == null || abs(yaw - left.center) <= YAW_EPSILON) return listOf(ViewWeight(left.viewId, 1f))
        val span = right.center - left.center
        require(span > YAW_EPSILON) { "视角中心重复，混合无定义" }
        // t: 0 → 完全取 left（正好在 left 中心），1 → 完全取 right（正好在 right 中心）。
        // 交叉点（两侧等距）时 t 必须**恰好** 0.5：否则 1-t 与 t 会有末位差，
        // 使「主轴 = 权重更大者」这条不变量在交叉点失效（maxByOrNull 只按数值比较）。
        // 判等要带容差：调用方按 0.25° 步进累加出的 yaw 在交叉点附近会有 float 末位误差。
        val distanceLeft = abs(yaw - left.center)
        val distanceRight = abs(yaw - right.center)
        val tie = abs(distanceLeft - distanceRight) <= YAW_EPSILON
        val t = if (tie) 0.5f else smoothstep(((yaw - left.center) / span).coerceIn(0f, 1f))
        // 单精度下两者相加未必恰为 1f，故第二个权重取补集，保证权重和恰好为 1。
        val weights = listOf(left to (1f - t), right to t)
        // **按权重降序返回**：调用方（渲染批处理、主轴判定）都依赖「第一个即主轴」，
        // 不能让这个不变量依赖 maxByOrNull 的并列取舍规则。
        val ordered = weights.sortedWith(compareByDescending<Pair<ViewBand, Float>> { it.second }
            .thenByDescending { it.first.center })
        return ordered.map { (band, weight) -> ViewWeight(band.viewId, weight) }
    }

    /**
     * 夹住 yaw 的两个相邻视角：`left` = 中心 ≤ yaw 中最大者（不存在时退化为最小中心视角），
     * `right` = 中心 > left 中最小者（yaw 在最右端时为 null）。
     * **唯一权威定义**，[blend] 与 [primary] 都由它派生，保证两者永远一致。
     */
    fun pair(bands: List<ViewBand>, yaw: Float): Pair<ViewBand, ViewBand?> {
        require(inRange(yaw)) { "yaw 必须有限且在 ±90 以内" }
        require(bands.isNotEmpty()) { "没有可用视角" }
        validateBands(bands.map { it.center to it.halfWidth })
        require(bands.any { abs(yaw - it.center) <= it.halfWidth + YAW_EPSILON }) {
            "yaw=$yaw 不在任何视角带内，拒绝静默外推"
        }
        val left = bands.filter { it.center <= yaw + YAW_EPSILON }.maxByOrNull { it.center }
            ?: bands.minByOrNull { it.center }!!
        val right = bands.filter { it.center > left.center + YAW_EPSILON }.minByOrNull { it.center }
        return left to right
    }

    /**
     * 主轴视角（权重 ≥ 0.5 的那一个）。
     *
     * 规则：夹住 yaw 的两个视角里，**离 yaw 更近**的那个；两侧等距（交叉点 ±22.5 / ±67.5）时
     * 统一取**中心更大的一侧**（偏向 yaw 正方向）。
     *
     * 这条规则必须与 [blend] 由同一个 [pair] 派生：早先 `nearest` 按列表顺序取最近、
     * `blend` 按数值取锚点，交叉点上两者给出不同答案——由「权重分配与最近视角一致」测试抓到。
     */
    fun primary(bands: List<ViewBand>, yaw: Float): ViewBand {
        val (left, right) = pair(bands, yaw)
        if (right == null) return left
        return if (abs(yaw - right.center) <= abs(yaw - left.center) + YAW_EPSILON) right else left
    }
    /** 断言权重和恰为 1（不变量，供测试与运行时降级判断复用）。 */
    fun totalWeight(weights: List<ViewWeight>): Float = weights.fold(0f) { sum, w -> sum + w.weight }

    fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * 与给定 yaw 最接近的视角。**与 [blend] 的主轴共用同一条规则**（见 [primary]），
     * 否则交叉点上两者会给出不同答案——这是测试实际抓到过的缺陷。
     */
    fun nearest(bands: List<ViewBand>, yaw: Float): String = primary(bands, yaw).viewId

    /** 计划 §5.3 的视差位移模型：层间视差像素位移 ∝ 层深度 × 视角带重叠张角。 */
    const val PIXELS_PER_DEGREE_PER_DEPTH = 1f / 90f

    /** 15° 重叠 → 深度 ±1 层的视差位移 d（层宽归一化）。 */
    fun parallaxPerBand(depth: Float, crossfadeDegrees: Float = CROSSFADE_DEGREES): Float {
        require(depth.isFinite() && depth in OcRigAssetRules.DEPTH_MIN..OcRigAssetRules.DEPTH_MAX) { "代理深度超出 -1..1" }
        require(crossfadeDegrees.isFinite() && crossfadeDegrees > 0f) { "交叉淡入张角无效" }
        return abs(depth) * crossfadeDegrees * PIXELS_PER_DEGREE_PER_DEPTH
    }

    /**
     * 计划 §5.3 的折算：`margin ≥ d / layerWidth`。
     *
     * 在归一化坐标里层宽恒为 1，因此所需 margin 就是归一化的视差位移 `d`。
     * 锚点视角的基线是设计文档 §6.3 的 10–15% 边距，其余视角按自身深度叠加视差项。
     */
    fun requiredMargin(depth: Float, isAnchor: Boolean = false): Float =
        (if (isAnchor) MARGIN_TARGET_MIN else 0f) + parallaxPerBand(depth)

    /**
     * 计算「本资产每个视角每层应该持有的 margin」，供导入期写回与测试修复使用。
     *
     * 语义澄清（避免把 10–15% 当成硬上限）：设计文档 §6.3 的 10–15% 是**锚点视角的基线**；
     * 当视差折算值更大时以视差为准，即 `margin = max(基线, 视差折算)`。
     * 因此层深度的绝对值越大、视角带宽越宽，所需冗余越高——这正是 ±90° 资产体积变大的原因。
     *
     * 返回值直接取 [adequacy] 的逐层需求，因此**写回后必然通过 adequacy**（有测试保证）。
     */
    fun requiredMargins(asset: OcRigAsset): Map<String, Map<String, Float>> = adequacy(asset).required.let { table ->
        asset.views.associate { view ->
            val perLayer = table[view.viewId].orEmpty()
            view.viewId to view.layers.associate { layer ->
                layer.id to minOf(OcRigAssetRules.MARGIN_MAX, perLayer[layer.id] ?: MARGIN_TARGET_MIN)
            }
        }
    }

    /**
     * 逐视角的 margin 充足性判定。**这是 §5.3 的硬门禁实现**。
     *
     * 算法（可逐项核对，不含未说明的魔数）：
     *   1. 取该视角带内的两个最不利 yaw（两侧边界各内缩 1°，避免正好压在交叉点边界上）；
     *   2. 在这两个 yaw 上做视角混合，取「其他视角在本视角带宽内的最大权重 w」；
     *   3. 对本视角的**每一层**算所需冗余：
     *        `需要 = (锚点? 0.10 : 0) + w × |层深度| × 15° × (1/90)`
     *      逐层算而不是用层深均值——页面上真正会露的是**某一层**，均值会互相掩盖；
     *   4. `margin < 需要` 的层全部计入 gaps，因此不足即拒绝，且拒绝理由能指到层。
     *
     * 回归证据：第一版用「邻视角层深均值」，导致 ±90° 视角被要求 1/6 层宽的冗余——
     * 那个数值来自「最深层在交叉点附近移入」的想象，而不是该视角自身层深。
     * 改成逐层后 ±90° 的需求降到 0.40×(w×15/90)，与「深层需要更多冗余」的定性一致。
     */
    fun adequacy(asset: OcRigAsset): MarginAdequacy {
        val checked = OcRigAssetRules.check(asset)
        val bands = checked.views.map { ViewBand(it.viewId, it.yawCenter, it.yawHalfWidth) }
        val perView = mutableListOf<ViewMarginAdequacy>()
        val deficient = mutableListOf<MarginGap>()
        val requiredTable = mutableMapOf<String, Map<String, Float>>()
        checked.views.forEach { view ->
            val anchor = abs(view.yawCenter) <= YAW_EPSILON
            // 最不利 yaw：本视角带两端内缩 1°；退化到单视角时没有其他视角参与，权重为 0。
            val samples = listOf(view.yawCenter - view.yawHalfWidth + 1f, view.yawCenter + view.yawHalfWidth - 1f)
                .filter { inRange(it) }
            val otherPeak = samples.maxOf { yaw ->
                blend(bands, yaw).filter { it.viewId != view.viewId }.maxOfOrNull { it.weight } ?: 0f
            }
            val baseline = if (anchor) MARGIN_TARGET_MIN else 0f
            val layerRequired = view.layers.associate { layer ->
                layer.id to (baseline + otherPeak * parallaxPerBand(layer.depth))
            }
            val effective = layerRequired.values.maxOrNull() ?: baseline
            val actualMin = view.layers.minOf { it.margin }
            val enough = view.layers.all { layer -> layer.margin + 1e-6f >= layerRequired.getValue(layer.id) }
            requiredTable[view.viewId] = layerRequired
            perView.add(ViewMarginAdequacy(view.viewId, effective, actualMin, enough))
            if (!enough) {
                view.layers.forEach { layer ->
                    val required = layerRequired.getValue(layer.id)
                    if (layer.margin + 1e-6f < required) deficient.add(MarginGap(view.viewId, layer.id, required, layer.margin))
                }
            }
        }
        return MarginAdequacy(perView, deficient, requiredTable)
    }

    /**
     * 充足性判定结果。
     *
     * `required` 是逐视角逐层的所需冗余表；`gaps` 只列不自足的层。
     * `passed` 以 gaps 为准——它才是门禁的判定依据。
     */
    data class MarginAdequacy(
        val views: List<ViewMarginAdequacy>,
        val gaps: List<MarginGap>,
        val required: Map<String, Map<String, Float>>,
    ) {
        val passed: Boolean get() = gaps.isEmpty()
    }

    data class ViewMarginAdequacy(val viewId: String, val requiredMargin: Float, val actualMinMargin: Float, val passed: Boolean)

    data class MarginGap(val viewId: String, val layerId: String, val requiredMargin: Float, val actualMargin: Float)

    /**
     * 运行时视差 Z 偏移（计划 §7.2 的 billboard 用法）。
     *
     * 符号约定：`yaw > 0` 表示镜头/头部向右转，画面内容整体向左移，
     * 因此偏移为负；深度越大（越靠近镜头）位移越大。
     */
    fun layerParallaxOffset(yaw: Float, depth: Float): Float {
        require(inRange(yaw)) { "yaw 超出 ±90" }
        require(depth.isFinite() && depth in OcRigAssetRules.DEPTH_MIN..OcRigAssetRules.DEPTH_MAX) { "代理深度超出 -1..1" }
        return -yaw * depth * PIXELS_PER_DEGREE_PER_DEPTH
    }
}
