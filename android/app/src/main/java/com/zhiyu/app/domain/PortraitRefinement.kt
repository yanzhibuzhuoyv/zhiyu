package com.zhiyu.app.domain

data class PortraitPoint(val x: Float, val y: Float)
data class PortraitStroke(val action: String, val points: List<PortraitPoint>, val radius: Float)

object PortraitRefinementRules {
    const val MAX_STROKES = 128
    const val MAX_POINTS = 1024
    fun check(strokes: List<PortraitStroke>): List<PortraitStroke> {
        require(strokes.size in 1..MAX_STROKES) { "请绘制 1 至 128 笔后再应用" }
        return strokes.map { stroke ->
            require(stroke.action in setOf("keep", "remove")) { "笔刷类型无效" }
            require(stroke.radius.isFinite() && stroke.radius in .002f.. .15f) { "笔刷大小无效" }
            require(stroke.points.size in 1..MAX_POINTS) { "单笔过长，请分成几笔绘制" }
            require(stroke.points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }) { "笔刷坐标超出图片" }
            stroke.copy(points = stroke.points.toList())
        }
    }
}
