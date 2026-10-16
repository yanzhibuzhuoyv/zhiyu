package rig2d.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 计划 §5.6 的 MarginAdequacyTest（提前到阶段 0 落地，因为它是 §5.3 的硬门禁）。
 *
 * 覆盖：视角带 + 偏转 → 所需冗余；不足即拒绝；边界值。
 *
 * 折算公式（计划 §5.3）：`margin ≥ d / layerWidth`，其中 d 是「重叠张角对应的层间视差位移」。
 * 本实现把 d 归一化成 `|depth| × 重叠角度 × (1/90)`：层宽在归一化坐标里恒为 1，
 * 因此所需 margin 就是归一化后的 d。1/90 的系数含义是「yaw 每转 1°，深度 ±1 的层
 * 相对前后平面横向位移 1/90 层宽」。该系数是**本实现引入的显式建模假设**，
 * 因此它必须是可核对、可替换的常量，而不是埋在公式里的魔数。
 */
class MarginAdequacyTest {

    // ------------------------------------------------ 系数本身

    @Test
    fun `视差系数是显式常量而非魔数`() {
        assertEquals(1f / 90f, OcViewBlendRules.PIXELS_PER_DEGREE_PER_DEPTH, 0f)
        assertEquals(0.10f, OcViewBlendRules.MARGIN_TARGET_MIN, 0f)
        assertEquals(0.15f, OcViewBlendRules.MARGIN_TARGET_MAX, 0f)
        assertEquals(15f, OcViewBlendRules.CROSSFADE_DEGREES, 0f)
    }

    @Test
    fun `深度 1 的层在 15 度重叠下需要 六分之一 层宽的冗余`() {
        assertEquals(1f / 6f, OcViewBlendRules.parallaxPerBand(1f), 1e-6f)
        assertEquals(1f / 12f, OcViewBlendRules.parallaxPerBand(0.5f), 1e-6f)
        assertEquals(0f, OcViewBlendRules.parallaxPerBand(0f), 0f)
    }

    @Test
    fun `视差随张角线性增长`() {
        assertEquals(1f / 9f, OcViewBlendRules.parallaxPerBand(1f, 10f), 1e-6f)
        assertEquals(1f / 3f, OcViewBlendRules.parallaxPerBand(1f, 30f), 1e-6f)
    }

    @Test
    fun `深度越界即拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { OcViewBlendRules.parallaxPerBand(1.5f) }
        assertThrows(IllegalArgumentException::class.java) { OcViewBlendRules.parallaxPerBand(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { OcViewBlendRules.parallaxPerBand(0f, 0f) }
    }

    // ------------------------------------------------ adequacy 判定

    @Test
    fun `12 百分比冗余的 5 视角资产判定通过`() {
        val result = OcViewBlendRules.adequacy(Fixtures.fiveViewAsset(margin = 0.12f))
        assertTrue("12% 冗余应满足 front 基线与 ±45 的 1/12 视差: ${result.gaps}", result.passed)
        assertEquals(5, result.views.size)
        assertTrue(result.views.all { it.passed })
    }

    @Test
    fun `冗余不足即拒绝并给出缺口明细`() {
        val asset = Fixtures.fiveViewAsset(margin = 0.08f)
        val result = OcViewBlendRules.adequacy(asset)
        assertFalse("8% 冗余不足以覆盖 front 的 10% 基线", result.passed)
        assertTrue("缺口必须给出视角与层编号", result.gaps.isNotEmpty())
        assertTrue(result.gaps.all { it.requiredMargin > it.actualMargin })
        assertTrue(result.gaps.any { it.viewId == OcViewConstants.FRONT })
    }

    @Test
    fun `边界值——恰好等于所需冗余时通过`() {
        // 夹具下最大逐层需求 = front 基线 0.10。
        val exact = OcViewBlendRules.adequacy(Fixtures.fiveViewAsset(margin = 0.10f))
        assertTrue("恰好 0.10 应通过：${exact.gaps}", exact.passed)
        val short = OcViewBlendRules.adequacy(Fixtures.fiveViewAsset(margin = 0.09f))
        assertFalse("0.09 差一档，必须被拒", short.passed)
    }

    @Test
    fun `front 视角的基线来自 10-15 百分比设计目标`() {
        val result = OcViewBlendRules.adequacy(Fixtures.fiveViewAsset(margin = 0.15f))
        val front = result.views.first { it.viewId == OcViewConstants.FRONT }
        assertEquals("front 需要 10% 基线", 0.10f, front.requiredMargin, 1e-6f)
        assertTrue(front.passed)
    }

    @Test
    fun `层越深需要的冗余越多——逐层模型的核心行为`() {
        // 把 ±90 视角的层加深，需求必须随之上升；front 没有视差项，因此 anchhor 基线 0.10 是全局下限。
        val shallow = Fixtures.fiveViewAsset(margin = 1f)
        val deeper = shallow.copy(
            views = shallow.views.map { view ->
                if (view.viewId == OcViewConstants.RIGHT90) {
                    view.copy(layers = view.layers.map { it.copy(depth = it.depth * 1.5f) })
                } else {
                    view
                }
            },
        )
        val front = OcViewBlendRules.adequacy(shallow).views.first { it.viewId == OcViewConstants.FRONT }
        val right90Shallow = OcViewBlendRules.adequacy(shallow).views.first { it.viewId == OcViewConstants.RIGHT90 }
        val right90Deeper = OcViewBlendRules.adequacy(deeper).views.first { it.viewId == OcViewConstants.RIGHT90 }
        assertTrue("front 无视差项，但锚点基线 0.10 应高于 ±90 的视差需求", front.requiredMargin > right90Shallow.requiredMargin)
        assertTrue("层加深后所需冗余必须上升", right90Deeper.requiredMargin > right90Shallow.requiredMargin)
    }

    @Test
    fun `逐层模型下 ±90 的需求等于 层深 乘 边界权重 乘 重叠张角比`() {
        val asset = Fixtures.fiveViewAsset(margin = 1f)
        val right90 = OcViewBlendRules.adequacy(asset).views.first { it.viewId == OcViewConstants.RIGHT90 }
        val view = asset.views.first { it.viewId == OcViewConstants.RIGHT90 }
        // 从权重出发重算期望值，而不是抄一个常数——否则混合规则一改，
        // 测试要么变成魔数校验，要么失去意义。
        val bands = asset.views.map { ViewBand(it.viewId, it.yawCenter, it.yawHalfWidth) }
        val boundaryYaws = listOf(view.yawCenter - view.yawHalfWidth + 1f, view.yawCenter + view.yawHalfWidth - 1f)
            .filter { OcViewBlendRules.inRange(it) }
        val peakOtherWeight = boundaryYaws.maxOf { yaw ->
            OcViewBlendRules.blend(bands, yaw).filter { it.viewId != view.viewId }.maxOfOrNull { it.weight } ?: 0f
        }
        val deepest = view.layers.maxOf { kotlin.math.abs(it.depth) }
        assertEquals(
            "±90 需求应等于 最深|深度| × 边界处来自 right45 的权重 × (15/90)",
            deepest * peakOtherWeight * (OcViewBlendRules.CROSSFADE_DEGREES / 90f),
            right90.requiredMargin,
            1e-5f,
        )
        // 记录实测值：0.6 × 0.7100 × (15/90) ≈ 0.0711（不是 0.6×0.7407 的 0.0741——
        // 采样点内缩 1° 得 yaw=61，smoothstep((61-45)/45)=S(0.35556)=0.70995）。
        assertEquals(0.0711f, right90.requiredMargin, 5e-4f)
    }

    @Test
    fun `空隙数值与公式一致——可人工核对`() {
        val asset = Fixtures.fiveViewAsset(margin = 0.05f)
        val result = OcViewBlendRules.adequacy(asset)
        val gap = result.gaps.first()
        // front 的 face 层深度 0 → 只需基线 0.10。
        val required = result.views.first { it.viewId == gap.viewId }.requiredMargin
        assertEquals(required, gap.requiredMargin, 1e-6f)
        assertEquals(0.05f, gap.actualMargin, 1e-6f)
    }

    @Test
    fun `单视角资产没有视差需求——只有基线`() {
        val onlyFront = OcRigAsset(
            views = listOf(Fixtures.view("front", 0f, 0f, margin = 0.10f)),
            sourceSha = Fixtures.hex("src"),
            approvedAt = 1L,
        )
        val result = OcViewBlendRules.adequacy(onlyFront)
        assertTrue("单视角 front 只需 10% 基线: ${result.gaps}", result.passed)
        assertEquals(0.10f, result.views.single().requiredMargin, 1e-6f)
    }

    @Test
    fun `不足的资产即使 schema 合法也要被 adequacy 拒掉——两道门分工明确`() {
        val thin = Fixtures.fiveViewAsset(margin = 0.01f)
        // 第一道门（schema）：形状合法，不拒绝。
        assertTrue(OcRigAssetRules.isValid(thin))
        // 第二道门（margin）：拒绝。
        assertFalse(OcViewBlendRules.adequacy(thin).passed)
    }

    // ------------------------------------------------ 修复用的写回值

    @Test
    fun `requiredMargins 给出的写回值能让该资产通过 adequacy`() {
        val thin = Fixtures.fiveViewAsset(margin = 0.01f)
        val required = OcViewBlendRules.requiredMargins(thin)
        val repaired = thin.copy(
            views = thin.views.map { view ->
                val table = required.getValue(view.viewId)
                view.copy(layers = view.layers.map { it.copy(margin = table.getValue(it.id)) })
            },
        )
        val result = OcViewBlendRules.adequacy(repaired)
        assertTrue("按 requiredMargins 写回后仍不通过：${result.gaps}", result.passed)
    }

    @Test
    fun `requiredMargins 不超过 1 的上限`() {
        val deep = Fixtures.fiveViewAsset(margin = 0.01f).copy(
            views = Fixtures.fiveViewAsset().views.map { view ->
                view.copy(layers = view.layers.map { it.copy(depth = if (it.depth < 0f) -1f else 1f) })
            },
        )
        OcViewBlendRules.requiredMargins(deep).values.forEach { table ->
            table.values.forEach { assertTrue("写回值必须落在 0..1", it in 0f..1f) }
        }
    }

    // ------------------------------------------------ 运行时视差

    @Test
    fun `运行时视差偏移随 yaw 与深度符号正确`() {
        assertEquals(0f, OcViewBlendRules.layerParallaxOffset(0f, 0.5f), 1e-6f)
        val right = OcViewBlendRules.layerParallaxOffset(90f, 0.5f)
        val left = OcViewBlendRules.layerParallaxOffset(-90f, 0.5f)
        // 符号约定：yaw>0（向右转）时画面内容向左移 → 偏移为负。
        assertEquals("yaw=+90 时应向左移 0.5 层宽", -0.5f, right, 1e-6f)
        assertEquals("左转与右转的视差应反号", -right, left, 1e-6f)
        // 深度越大（越靠镜头）位移越大；深度 0 不产生视差。
        assertEquals(0f, OcViewBlendRules.layerParallaxOffset(90f, 0f), 1e-6f)
        assertEquals("深度 1 的层在 +90° 处位移满量程", -1f, OcViewBlendRules.layerParallaxOffset(90f, 1f), 1e-6f)
        assertEquals(-0.25f, OcViewBlendRules.layerParallaxOffset(45f, 0.5f), 1e-6f)
        // 非有限 / 越界入参拒绝。
        assertThrows(IllegalArgumentException::class.java) { OcViewBlendRules.layerParallaxOffset(91f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { OcViewBlendRules.layerParallaxOffset(0f, 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { OcViewBlendRules.layerParallaxOffset(Float.NaN, 0.5f) }
    }
}
