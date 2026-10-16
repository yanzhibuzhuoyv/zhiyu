package rig2d.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * 混合权重的定点诊断（保留，作为回归证据的一部分）。
 *
 * 这不是「临时打印」，而是把 RED 阶段暴露出来的三个关键角度固定成可复跑的断言：
 *   - yaw=-67.5 必须混合 left45 与 left90（第一版错选 left90 单视角）
 *   - yaw=+22.5 必须混合 front 与 right45（第一版错选 front 与 left45）
 *   - yaw=0/±45/±90 必须退化为单视角（第一版在 ±90 端点带出了 ±45）
 *
 * 见 artifacts/stage0-red-2026-10-12.log 的对应失败输出。
 */
class BlendAnchorRegressionTest {

    private val bands = Fixtures.CANONICAL_BANDS

    private fun blendAt(yaw: Float): Map<String, Float> =
        OcViewBlendRules.blend(bands, yaw).associate { it.viewId to it.weight }

    @Test
    fun `负向交叉点 67-5 混合 left45 与 left90`() {
        val weights = blendAt(-67.5f)
        assertEquals(setOf(OcViewConstants.LEFT45, OcViewConstants.LEFT90), weights.keys)
        assertEquals(0.5f, weights.getValue(OcViewConstants.LEFT45), 1e-6f)
        assertEquals(0.5f, weights.getValue(OcViewConstants.LEFT90), 1e-6f)
    }

    @Test
    fun `正向交叉点 22-5 混合 front 与 right45`() {
        val weights = blendAt(22.5f)
        assertEquals(setOf(OcViewConstants.FRONT, OcViewConstants.RIGHT45), weights.keys)
        assertEquals(0.5f, weights.getValue(OcViewConstants.FRONT), 1e-6f)
        assertEquals(0.5f, weights.getValue(OcViewConstants.RIGHT45), 1e-6f)
    }

    @Test
    fun `另外两个交叉点也对称`() {
        assertEquals(setOf(OcViewConstants.LEFT45, OcViewConstants.FRONT), blendAt(-22.5f).keys)
        assertEquals(setOf(OcViewConstants.RIGHT45, OcViewConstants.RIGHT90), blendAt(67.5f).keys)
    }

    @Test
    fun `四个端点是单视角`() {
        listOf(-90f, -45f, 0f, 45f, 90f).forEach { yaw ->
            assertEquals("yaw=$yaw 应为单视角", 1, OcViewBlendRules.blend(bands, yaw).size)
        }
    }

    @Test
    fun `端点附近立刻出现第二个视角——±90 不是死区`() {
        assertEquals(2, OcViewBlendRules.blend(bands, -89.5f).size)
        assertEquals(2, OcViewBlendRules.blend(bands, 89.5f).size)
        // 靠近 left90 的一侧，主导者必须是 left90。
        assertEquals(OcViewConstants.LEFT90, OcViewBlendRules.blend(bands, -89.5f).maxByOrNull { it.weight }!!.viewId)
        assertEquals(OcViewConstants.RIGHT90, OcViewBlendRules.blend(bands, 89.5f).maxByOrNull { it.weight }!!.viewId)
    }

    @Test
    fun `权重在四个交叉点两侧都单调过渡`() {
        listOf(-22.5f, 22.5f, -67.5f, 67.5f).forEach { cross ->
            val before = OcViewBlendRules.blend(bands, cross - 3f).maxByOrNull { it.weight }!!.viewId
            val after = OcViewBlendRules.blend(bands, cross + 3f).maxByOrNull { it.weight }!!.viewId
            assertEquals("交叉点 $cross 两侧的主导视角应不同", false, before == after)
        }
    }
}
