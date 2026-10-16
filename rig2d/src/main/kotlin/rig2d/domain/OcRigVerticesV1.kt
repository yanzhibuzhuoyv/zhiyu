package rig2d.domain

import kotlin.math.exp
import kotlin.math.pow

/*
 * v1 网格生成的冻结副本：对应 android/app/src/main/java/com/zhiyu/app/domain/OcRig.kt
 * 的 `OcRigRules.vertices`。
 *
 * 为什么单独成文件：
 *   计划 §4.1 明确要求阶段 2 复用**这条**网格生成路径（「已产出 (columns+1)*(rows+1)*2 的
 *   FloatArray」），只把位移来源从纯 2D 高斯权重换成代理驱动的 3D yaw 投影。
 *   因此这段代码是 v2 的**被复用方**，必须与 v1 逐位一致，单独放便于比对与移植。
 *
 * 逐位一致性纪律：
 *   - 累加顺序不能改（浮点加法不满足结合律，改顺序就会改结果）；
 *   - 常量不能「顺手简化」（exp(-2.2f * ...) 里的 2.2f、0.035f、0.9f、1.8f、0.075f、0.04f）；
 *   - `coerceAtLeast(1f)` 的兜底不能删（重叠区域混合的除零保护）。
 */

/** OcRigRules.vertices 的冻结副本。 */
object OcRigVerticesV1 {
    fun vertices(rig: OcRigV1, motion: OcMotionV1, width: Int, height: Int, columns: Int = 48, rows: Int = 64): FloatArray =
        vertices(columns, rows, width, height, rig, motion)

    fun vertices(columns: Int, rows: Int, width: Int, height: Int, rig: OcRigV1, motion: OcMotionV1): FloatArray {
        require(columns in 2..64 && rows in 2..64 && width in 1..4096 && height in 1..4096)
        OcRigRulesV1.check(rig)
        OcRigRulesV1.motion(motion)
        val result = FloatArray((columns + 1) * (rows + 1) * 2)
        for (row in 0..rows) for (column in 0..columns) {
            val x = column.toFloat() / columns
            val y = row.toFloat() / rows
            var dx = 0f
            var dy = 0f
            var activeWeight = 0f
            rig.regions.forEach { region ->
                if (x < region.x || x > region.x + region.width || y < region.y || y > region.y + region.height) return@forEach
                val centerX = region.x + region.width * region.pivotX
                val centerY = region.y + region.height * region.pivotY
                val nx = (x - centerX) / (region.width * .5f)
                val ny = (y - centerY) / (region.height * .5f)
                val weight = (exp(-2.2f * (nx.pow(2) + ny.pow(2))) * region.strength).toFloat()
                val active = when (region.kind) {
                    "head" -> motion.headTurn != 0f
                    "body" -> motion.breath != 0f
                    "eyes" -> motion.blink != 0f
                    "mouth" -> motion.mouthOpen != 0f
                    else -> motion.accessorySway != 0f
                }
                if (!active) return@forEach
                activeWeight += weight
                when (region.kind) {
                    "head" -> { dx += motion.headTurn * .035f * weight; dy -= motion.headTurn * (x - centerX) * .035f * weight }
                    "body" -> { dx += motion.breath * (x - centerX) * .015f * weight; dy -= motion.breath * .008f * weight }
                    "eyes" -> dy -= (y - centerY) * motion.blink * .9f * weight
                    "mouth" -> dy += (y - centerY) * motion.mouthOpen * 1.8f * weight
                    "accessory" -> { dx += motion.accessorySway * (y - centerY) * .075f * weight; dy -= motion.accessorySway * (x - centerX) * .04f * weight }
                }
            }
            // Overlapping user regions blend; they cannot turn 128 influences into a huge jump.
            val divisor = activeWeight.coerceAtLeast(1f)
            val at = (row * (columns + 1) + column) * 2
            result[at] = (x + dx / divisor) * width
            result[at + 1] = (y + dy / divisor) * height
        }
        return result
    }
}
