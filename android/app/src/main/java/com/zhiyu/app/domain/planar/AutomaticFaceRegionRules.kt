package com.zhiyu.app.domain.planar

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class DetectedFaceRegion(val midpointX: Double, val midpointY: Double, val eyesDistance: Double,
    val confidence: Double, val yaw: Double = 0.0, val roll: Double = 0.0)

/** Only a reliable, single frontal detection may create a temporary local-motion suggestion. */
object AutomaticFaceRegionRules {
    fun suggest(width: Int, height: Int, faces: List<DetectedFaceRegion>): PlanarDraft? {
        require(width in 1..PlanarDraft.MAX_SIDE && height in 1..PlanarDraft.MAX_SIDE)
        val face = faces.singleOrNull() ?: return null
        val values = listOf(face.midpointX, face.midpointY, face.eyesDistance, face.confidence, face.yaw, face.roll)
        if (values.any { !it.isFinite() } || face.confidence !in .8..1.0 || abs(face.yaw) > 18 || abs(face.roll) > 18) return null
        val d = face.eyesDistance
        // Below this size even a confident detection cannot produce half-pixel idle motion safely.
        if (d < maxOf(18.0, minOf(width, height) * .025) || d > minOf(width * .35, height * .20)) return null
        val cx = face.midpointX; val cy = face.midpointY + d * .28
        // Stay inside the frontal face, rather than guessing a full head/hair/body silhouette.
        val rx = d * .95; val ry = d * 1.05
        if (cx - rx < 1 || cx + rx >= width - 1 || cy - ry < 1 || cy + ry >= height - 1) return null
        val initial = PlanarDraft.suggest(width, height)
        val anchors = initial.anchors.copy(head = PlanarPoint(cx, cy), neck = PlanarPoint(cx, cy + ry * .9))
        val ellipse = (0 until 24).map { index ->
            val radians = index * 2.0 * Math.PI / 24
            PlanarPoint(cx + cos(radians) * rx, cy + sin(radians) * ry)
        }
        return initial.copy(anchors = anchors, featureVisibility = PlanarFeatureVisibility(false, false, false),
            parts = listOf(PlanarPart("auto_face", "人物局部动作建议", "face", 20.0, ellipse, anchors.head)))
    }
}
