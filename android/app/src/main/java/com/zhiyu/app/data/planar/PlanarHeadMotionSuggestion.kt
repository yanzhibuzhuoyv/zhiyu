package com.zhiyu.app.data.planar

import com.zhiyu.app.domain.planar.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** An editable local region from the user's landmarks, never a segmentation or a published model. */
internal object PlanarHeadMotionSuggestion {
    const val ID = "auto_face"
    fun available(draft: PlanarDraft) = draft.parts.size < PlanarDraft.MAX_PARTS &&
        draft.parts.none { it.id == ID || it.role == "face" || it.role == "body" }

    fun create(draft: PlanarDraft, width: Int, height: Int): PlanarPart {
        draft.validate(width, height, allowIncompleteParts = true)
        require(available(draft)) { "已有头部或身体活动层，请直接调整原有轮廓" }
        val a = draft.anchors
        val eyeDistance = abs((a.eyeLeft.left + a.eyeLeft.right - a.eyeRight.left - a.eyeRight.right) / 2)
        val headToNeck = hypot(a.neck.x - a.head.x, a.neck.y - a.head.y)
        val extent = if (draft.featureVisibility.eyeLeft && draft.featureVisibility.eyeRight)
            maxOf(eyeDistance, headToNeck * .65) else headToNeck
        val rx = minOf(maxOf(4.0, extent * .95), a.head.x - 1, width - 2.0 - a.head.x)
        val ry = minOf(maxOf(4.0, extent * 1.05), a.head.y - 1, height - 2.0 - a.head.y)
        require(rx >= 2 && ry >= 2) { "头部标记太靠边，请先把头部中心移到人物脸内" }
        val polygon = (0 until 24).map { index ->
            val radians = index * 2.0 * Math.PI / 24
            PlanarPoint(a.head.x + cos(radians) * rx, a.head.y + sin(radians) * ry)
        }
        return PlanarPart(ID, "头部微动建议（可调整）", "face", 20.0, polygon, a.head)
    }
}

internal object PlanarMotionStatus {
    private val idleChannels = setOf("headX", "headY", "tilt", "breath", "sway")
    fun expressionOnly(document: PlanarDocument): Boolean = document.layers.none { layer ->
        layer.id != "base" && layer.mesh.deltas().any { (name, values) -> name in idleChannels && values.any { it != 0f } }
    }
    fun hasExpression(document: PlanarDocument): Boolean = document.layers.any { layer ->
        layer.mesh.deltas().any { (name, values) -> name in setOf("blinkLeft", "blinkRight", "mouth") && values.any { it != 0f } }
    }
}
