package rig2d.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/*
 * 计划 §2.3 第二个测试文件：OcViewBlendRulesTest
 *
 * 覆盖：0/±45/±90 退化为单视角；交叉点两权重和为 1；单调性；带宽外拒绝。
 */
class OcViewBlendRulesTest {

    private val bands = Fixtures.CANONICAL_BANDS

    private fun rejected(yaw: Float, why: String) {
        val failure = assertThrows(IllegalArgumentException::class.java) { OcViewBlendRules.blend(bands, yaw) }
        assertTrue("$why：拒绝原因不能为空", !failure.message.isNullOrBlank())
    }

    // ------------------------------------------------ 0 / ±45 / ±90 退化

    @Test
    fun `五个视角中心处退化为单视角且权重恰为 1`() {
        listOf(0f, 45f, 90f, -45f, -90f).forEach { yaw ->
            val weights = OcViewBlendRules.blend(bands, yaw)
            assertEquals("yaw=$yaw 应退化为单视角", 1, weights.size)
            assertEquals("yaw=$yaw 权重应为 1", 1f, weights.single().weight, 0f)
            assertEquals(OcViewConstants.idOf(yaw), weights.single().viewId)
        }
    }

    @Test
    fun `中心附近的单视角退化仍然给出正确视角`() {
        assertEquals(OcViewConstants.FRONT, OcViewBlendRules.blend(bands, 0.0005f).single().viewId)
        assertEquals(OcViewConstants.RIGHT90, OcViewBlendRules.blend(bands, 90f).single().viewId)
        assertEquals(OcViewConstants.LEFT90, OcViewBlendRules.blend(bands, -90f).single().viewId)
    }

    // ------------------------------------------------ 交叉点

    @Test
    fun `交叉点两权重相等且和为 1`() {
        // 相邻视角中心的中点就是交叉点：±22.5 / ±67.5。
        listOf(-67.5f, -22.5f, 22.5f, 67.5f).forEach { yaw ->
            val weights = OcViewBlendRules.blend(bands, yaw)
            assertEquals("yaw=$yaw 应是两视角混合", 2, weights.size)
            assertEquals("yaw=$yaw 交叉点两权重应相等", weights[0].weight, weights[1].weight, 1e-6f)
            assertEquals("yaw=$yaw 权重和应为 1", 1f, OcViewBlendRules.totalWeight(weights), 1e-6f)
            assertEquals("yaw=$yaw 交叉点两权重应各为 0.5", 0.5f, weights[0].weight, 1e-6f)
        }
    }

    @Test
    fun `全域扫描权重和恒为 1 且视角数不超过 2`() {
        var yaw = -90f
        while (yaw <= 90f) {
            val weights = OcViewBlendRules.blend(bands, yaw)
            assertTrue("yaw=$yaw 视角数超过 2", weights.size <= 2)
            assertEquals("yaw=$yaw 权重和不为 1", 1f, OcViewBlendRules.totalWeight(weights), 1e-6f)
            assertTrue("yaw=$yaw 出现非正权重之外的负数", weights.all { it.weight in 0f..1f })
            weights.forEach { assertTrue("yaw=$yaw 视角不在视角集合内", it.viewId in OcViewConstants.REQUIRED_IDS) }
            yaw += 0.25f
        }
    }

    // ------------------------------------------------ 单调性

    @Test
    fun `每个视角的权重随 yaw 靠近其中心而单调不减`() {
        OcViewConstants.REQUIRED_IDS.forEach { viewId ->
            val center = OcViewConstants.centerOf(viewId)
            // 从 -90 向中心推进：权重应单调不减。
            val rising = generateSequence(-90f) { it + 0.5f }.takeWhile { it <= center }.toList()
            val values = rising.map { yaw -> weightOf(viewId, yaw) }
            values.zipWithNext().forEach { (before, after) ->
                assertTrue("视角 $viewId 在靠近中心时权重下降：$before -> $after", after >= before - 1e-6f)
            }
            assertEquals("视角 $viewId 在中心处权重应为 1", 1f, values.last(), 1e-6f)
        }
    }

    @Test
    fun `权重分配与最近视角一致`() {
        var yaw = -89.75f
        while (yaw <= 89.75f) {
            val weights = OcViewBlendRules.blend(bands, yaw)
            val dominant = weights.maxByOrNull { it.weight }!!
            assertEquals("yaw=$yaw 主导视角应有最大权重", OcViewBlendRules.nearest(bands, yaw), dominant.viewId)
            yaw += 0.25f
        }
    }

    @Test
    fun `权重连续——相邻采样点之间不出现跳变`() {
        var yaw = -89.9f
        var previous = OcViewBlendRules.blend(bands, yaw).associate { it.viewId to it.weight }
        while (yaw <= 89.9f) {
            yaw += 0.05f
            val current = OcViewBlendRules.blend(bands, yaw).associate { it.viewId to it.weight }
            (previous.keys + current.keys).forEach { viewId ->
                val delta = abs((current[viewId] ?: 0f) - (previous[viewId] ?: 0f))
                assertTrue("视角 $viewId 在 yaw=$yaw 处权重跳变 $delta", delta < 0.02f)
            }
            previous = current
        }
    }

    // ------------------------------------------------ 带宽外拒绝

    @Test
    fun `yaw 超出 ±90 即拒绝`() {
        rejected(90.5f, "超过 +90")
        rejected(-90.5f, "低于 -90")
        rejected(Float.NaN, "NaN")
        rejected(Float.POSITIVE_INFINITY, "Inf")
    }

    @Test
    fun `yaw 落在视角带并集之外即拒绝而不是外推`() {
        // 去掉 ±90 视角后，±45 的带只到 ±75，80° 处没有任何视角覆盖。
        val fourViews = bands.filter { abs(it.center) != 90f }
        val failure = assertThrows(IllegalArgumentException::class.java) { OcViewBlendRules.blend(fourViews, 80f) }
        assertTrue(failure.message!!.contains("不在任何视角带内"))
    }

    @Test
    fun `带宽不足导致漏角时 validateBands 拒绝`() {
        val gapped = listOf(
            ViewBand(OcViewConstants.FRONT, 0f, 15f),
            ViewBand(OcViewConstants.RIGHT45, 45f, 15f),
        )
        val failure = assertThrows(IllegalArgumentException::class.java) { OcViewBlendRules.validateBands(gapped.map { it.center to it.halfWidth }) }
        assertTrue(failure.message!!.contains("漏角"))
    }

    @Test
    fun `半宽越界时 validateBands 拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            OcViewBlendRules.validateBands(listOf(0f to 4f, 45f to 30f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcViewBlendRules.validateBands(listOf(0f to 46f, 45f to 30f))
        }
    }

    @Test
    fun `视角中心重复时 validateBands 拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            OcViewBlendRules.validateBands(listOf(0f to 30f, 0f to 30f))
        }
    }

    // ------------------------------------------------ 视角带形状

    @Test
    fun `规范 5 视角被识别为 canonical`() {
        assertTrue(OcViewBlendRules.isCanonicalFive(bands.map { it.center to it.halfWidth }))
        assertTrue(!OcViewBlendRules.isCanonicalFive(listOf(0f to 30f, 45f to 30f)))
        assertTrue(!OcViewBlendRules.isCanonicalFive(bands.drop(1).map { it.center to it.halfWidth }))
    }

    @Test
    fun `3 视角方案（只到 ±45）不是 canonical 但仍然可混合`() {
        val three = listOf(
            ViewBand(OcViewConstants.FRONT, 0f, 30f),
            ViewBand(OcViewConstants.RIGHT45, 45f, 30f),
            ViewBand(OcViewConstants.LEFT45, -45f, 30f),
        )
        assertTrue(!OcViewBlendRules.isCanonicalFive(three.map { it.center to it.halfWidth }))
        // 在与 ±90 交界的那一侧退化为单视角（没有 ±90 可混），另一侧仍能两视角混合。
        assertEquals(1, OcViewBlendRules.blend(three, 45f).size)
        assertEquals(OcViewConstants.RIGHT45, OcViewBlendRules.blend(three, 45f).single().viewId)
        assertEquals(2, OcViewBlendRules.blend(three, 22.5f).size)
        assertThrows(IllegalArgumentException::class.java) { OcViewBlendRules.blend(three, 80f) }
    }

    // ------------------------------------------------ smoothstep 本体

    @Test
    fun `smoothstep 端点与中点取值正确`() {
        assertEquals(0f, OcViewBlendRules.smoothstep(0f), 0f)
        assertEquals(1f, OcViewBlendRules.smoothstep(1f), 0f)
        assertEquals(0.5f, OcViewBlendRules.smoothstep(0.5f), 1e-7f)
        assertEquals(0f, OcViewBlendRules.smoothstep(-3f), 0f)
        assertEquals(1f, OcViewBlendRules.smoothstep(7f), 0f)
    }

    private fun weightOf(viewId: String, yaw: Float): Float =
        OcViewBlendRules.blend(bands, yaw).firstOrNull { it.viewId == viewId }?.weight ?: 0f

    @Test
    fun `单精度下权重和不是靠巧合——每个采样点都在 1 的 1e-6 内`() {
        var yaw = -90f
        var samples = 0
        while (yaw <= 90f) {
            assertEquals(1f, OcViewBlendRules.totalWeight(OcViewBlendRules.blend(bands, yaw)), 1e-6f)
            samples++
            yaw += 1f
        }
        assertEquals("应扫描 181 个整度采样点", 181, samples)
    }

    @Test
    fun `不相关视角的权重为 0 而不是缺失`() {
        val weights = OcViewBlendRules.blend(bands, 0f)
        assertNotEquals(0, weights.size)
        assertEquals(OcViewConstants.FRONT, weights.single().viewId)
    }
}
