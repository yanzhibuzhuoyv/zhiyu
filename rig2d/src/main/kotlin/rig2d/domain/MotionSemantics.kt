package rig2d.domain

/*
 * headTurn 语义的 v1/v2 分界（计划 §2.1 / 设计文档 §8.3）。
 *
 * v1：`motion.headTurn * 3.5f` 度旋转 + `* 13f` 像素平移 —— 纯装饰性横向摆动。
 * v2：`motion.headTurn * 90f` 度真实 yaw —— 走视角混合 + 代理视差。
 *
 * 两者都在 -1..1 内，**数值合法但含义不同**。因此：
 *   - 校验逻辑（OcRigRules.motion）完全不变，v1 与 v2 共用；
 *   - 解释这一层必须显式分派，v1 档案必须走 v1 路径，否则老角色的画面会变化。
 *
 * 这些常量是 v1 冻结值，禁止修改。v2 的 90 是设计目标值，也在这里集中，避免散落在渲染代码里。
 */
object MotionSemantics {
    /** v1 冻结常量：OcFlatRenderer.kt:91。 */
    const val V1_HEAD_TURN_DEGREES = 3.5f
    const val V1_HEAD_TURN_TRANSLATE_PX = 13f
    /** v1 冻结常量：呼吸、眨眼、口型的换算系数。 */
    const val V1_BREATH_SCALE_X = 0.008f
    const val V1_BREATH_SCALE_Y = 0.004f
    const val V1_BREATH_TRANSLATE_PX = 2f
    const val V1_BLINK_DEGENERATE_AT = 0.8f
    const val V1_BLINK_HEIGHT_FACTOR = 0.94f
    const val V1_MOUTH_HEIGHT_PX = 13f
    const val V1_MOUTH_BASE_PX = 3f
    /** v2 目标：headTurn ∈ -1..1 映射到 ±90°。 */
    const val V2_HEAD_TURN_DEGREES = 90f

    fun v1RotationDegrees(headTurn: Float): Float = headTurn * V1_HEAD_TURN_DEGREES

    fun v1TranslatePixels(headTurn: Float): Float = headTurn * V1_HEAD_TURN_TRANSLATE_PX

    fun v2YawDegrees(headTurn: Float): Float = headTurn * V2_HEAD_TURN_DEGREES

    /** 显式分派：只有调用方说明档案版本，才允许解释 headTurn。 */
    fun yawDegrees(schemaVersion: Int, headTurn: Float): Float = when (OcSchemas.dispatch(schemaVersion)) {
        OcSchemas.V1 -> v1RotationDegrees(headTurn)
        OcSchemas.V2 -> v2YawDegrees(headTurn)
        else -> throw IllegalArgumentException("不支持的角色档案版本：$schemaVersion")
    }

    /** v2 下 headTurn 必须落在 ±90 的混合域内（失败即拒绝，不外推）。 */
    fun v2YawChecked(headTurn: Float): Float {
        OcRigRulesV1.motion(OcMotionV1(headTurn = headTurn))
        val yaw = v2YawDegrees(headTurn)
        require(OcViewBlendRules.inRange(yaw)) { "headTurn 映射出的 yaw 超出 ±90" }
        return yaw
    }
}
