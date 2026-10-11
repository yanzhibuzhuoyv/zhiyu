package com.zhiyu.app.domain

import kotlin.math.exp
import kotlin.math.pow

data class OcRig(val regions: List<OcRigRegion> = emptyList(), val version: Int = 1)
data class OcRigRegion(val id: String, val kind: String, val x: Float = .3f, val y: Float = .1f,
    val width: Float = .4f, val height: Float = .3f, val pivotX: Float = .5f, val pivotY: Float = .5f,
    val strength: Float = 1f, val label: String = "")

/** A bounded bitmap mesh with user placed local influences, not inferred layers or Cubism assets. */
object OcRigRules {
    fun defaultRig() = OcRig(listOf(OcRigRegion("head", "head"),
        OcRigRegion("body", "body", .25f, .4f, .5f, .55f),
        OcRigRegion("eyes", "eyes", .32f, .19f, .36f, .08f),
        OcRigRegion("mouth", "mouth", .42f, .29f, .16f, .07f)))
    fun check(value: OcRig): OcRig {
        require(value.version == 1 && value.regions.size <= 128 && value.regions.map { it.id }.distinct().size == value.regions.size) { "动画区域过多或重复" }
        value.regions.forEach {
            require(Regex("[a-zA-Z0-9_-]{1,80}").matches(it.id) && it.kind in setOf("head", "body", "eyes", "mouth", "accessory")) { "动画区域无效" }
            require(listOf(it.x, it.y, it.width, it.height, it.pivotX, it.pivotY, it.strength).all { n -> n.isFinite() } &&
                it.x in 0f..1f && it.y in 0f..1f && it.width in .02f..1f && it.height in .02f..1f &&
                it.x + it.width <= 1.001f && it.y + it.height <= 1.001f && it.pivotX in 0f..1f && it.pivotY in 0f..1f &&
                it.strength in 0f..2f && it.label.length <= 80) { "动画区域超出图片" }
        }
        return value.copy(regions = value.regions.map { it.copy() })
    }
    fun motion(value: OcMotion): OcMotion {
        require(listOf(value.headTurn, value.breath, value.blink, value.mouthOpen, value.accessorySway).all { it.isFinite() && it in -1f..1f }) { "动画参数无效" }
        require(value.blink >= 0f && value.mouthOpen >= 0f) { "表情参数无效" }
        return value
    }
    fun vertices(columns: Int, rows: Int, width: Int, height: Int, rig: OcRig, motion: OcMotion): FloatArray {
        require(columns in 2..64 && rows in 2..64 && width in 1..4096 && height in 1..4096)
        check(rig); motion(motion)
        val result = FloatArray((columns + 1) * (rows + 1) * 2)
        for (row in 0..rows) for (column in 0..columns) {
            val x = column.toFloat() / columns; val y = row.toFloat() / rows
            var dx = 0f; var dy = 0f; var activeWeight = 0f
            rig.regions.forEach { region ->
                if (x < region.x || x > region.x + region.width || y < region.y || y > region.y + region.height) return@forEach
                val centerX = region.x + region.width * region.pivotX
                val centerY = region.y + region.height * region.pivotY
                val nx = (x - centerX) / (region.width * .5f); val ny = (y - centerY) / (region.height * .5f)
                val weight = (exp(-2.2f * (nx.pow(2) + ny.pow(2))) * region.strength).toFloat()
                val active = when (region.kind) {
                    "head" -> motion.headTurn != 0f; "body" -> motion.breath != 0f
                    "eyes" -> motion.blink != 0f; "mouth" -> motion.mouthOpen != 0f
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
            result[at] = (x + dx / divisor) * width; result[at + 1] = (y + dy / divisor) * height
        }
        return result
    }
}
