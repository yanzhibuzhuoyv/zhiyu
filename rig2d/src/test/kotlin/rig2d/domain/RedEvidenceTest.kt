package rig2d.domain

import org.junit.Test

/**
 * RED 证据复现：把**初版锚点选择**在本文件内独立复刻（这是当时真实编译进产物的实现），
 * 打印它在四个交叉点上的输出，保存进 artifacts/stage0-red-2026-10-12.log。
 *
 * 为什么要在测试里复刻而不是「引用历史提交」：本仓库第一阶段没有把错误版本留在历史里，
 * 而「RED 日志保留」的要求（计划 §10.1）需要可复跑的物证，不能只靠一段叙述。
 * 复刻的代码逐行等价于最初的 `anchorIndex`：取第一个 `center ≤ yaw` 的下标。
 */
class RedEvidenceTest {

    private val bands = Fixtures.CANONICAL_BANDS

    /** 初版实现（有缺陷）：第一个 `center ≤ yaw` 的下标。 */
    private fun legacyAnchorIndex(sorted: List<ViewBand>, yaw: Float): Int {
        sorted.forEachIndexed { index, band ->
            if (band.center <= yaw + 1e-3f) return index
        }
        return sorted.lastIndex
    }

    private fun legacyBlend(yaw: Float): List<String> {
        val sorted = bands.sortedByDescending { it.center }
        val index = legacyAnchorIndex(sorted, yaw)
        val left = sorted[index]
        val right = sorted.getOrNull(index + 1)
        if (right == null || kotlin.math.abs(yaw - left.center) <= 1e-3f) return listOf("${left.viewId}=1.0")
        val span = left.center - right.center
        val fade = OcViewBlendRules.smoothstep(((left.center - yaw) / span).coerceIn(0f, 1f))
        return listOf("${left.viewId}=${1f - fade}", "${right.viewId}=$fade")
    }

    @Test
    fun `复现初版锚点缺陷并对照现版`() {
        println("=== RED 证据：初版锚点选择 vs 现版 ===")
        listOf(-90f, -67.5f, -22.5f, 0f, 22.5f, 67.5f, 90f).forEach { yaw ->
            val legacy = legacyBlend(yaw)
            val current = OcViewBlendRules.blend(bands, yaw).map { "${it.viewId}=${it.weight}" }
            val expected = expectedPair(yaw)
            println("yaw=$yaw legacy=$legacy current=$current expected=$expected")
        }
        println("=== END RED 证据 ===")
    }

    /**
     * 计划 §5.1 的视角带声明：交叉点两侧各 0.5，端点为单视角。
     * 顺序按**主轴在前**（主轴 = 权重更大者；交叉点按「取中心更大的一侧」定主轴）。
     */
    private fun expectedPair(yaw: Float): List<String> = when (yaw) {
        -90f -> listOf("left90=1.0")
        -67.5f -> listOf("left45=0.5", "left90=0.5")
        -22.5f -> listOf("front=0.5", "left45=0.5")
        0f -> listOf("front=1.0")
        22.5f -> listOf("right45=0.5", "front=0.5")
        67.5f -> listOf("right90=0.5", "right45=0.5")
        else -> listOf("right90=1.0")
    }

    @Test
    fun `现版在全部交叉点与端点都与视角带声明一致`() {
        listOf(-90f, -67.5f, -22.5f, 0f, 22.5f, 67.5f, 90f).forEach { yaw ->
            val current = OcViewBlendRules.blend(bands, yaw).map { "${it.viewId}=${it.weight}" }
            org.junit.Assert.assertEquals("yaw=$yaw", expectedPair(yaw), current)
        }
    }
}
