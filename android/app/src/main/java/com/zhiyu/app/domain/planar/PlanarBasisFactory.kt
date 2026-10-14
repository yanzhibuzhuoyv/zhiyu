package com.zhiyu.app.domain.planar

import rig2d.planar.PlanarContract
import rig2d.planar.PlanarMesh
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Port of studio/mesh.py: all formulas evaluate as Double before one Float32 conversion. */
object PlanarBasisFactory {
    fun create(
        bounds: IntArray, role: String, anchors: PlanarAnchors, pivot: PlanarPoint,
        strength: Double, width: Int, height: Int, contract: PlanarContract,
        motionMode: PlanarMotionMode = PlanarMotionMode.CHARACTER,
    ): PlanarMesh {
        PlanarDraft(anchors).validate(width, height)
        require(bounds.size == 4 && bounds[0] >= 0 && bounds[1] >= 0 && bounds[2] > 0 && bounds[3] > 0 &&
            bounds[0].toLong() + bounds[2] <= width && bounds[1].toLong() + bounds[3] <= height &&
            max(width, height) <= contract.maxSide) { "网格边界无效" }
        require(role == "base" || role in PlanarDraft.PART_ROLES) { "网格角色无效" }
        require(strength.isFinite() && strength in 0.0..1.0 && pivot.x.isFinite() && pivot.y.isFinite() &&
            pivot.x in 0.0..(width - 1.0) && pivot.y in 0.0..(height - 1.0)) { "网格支点或强度无效" }
        val feature = role in setOf("eye_left", "eye_right", "mouth")
        val minimum = if (feature) 7 else 3
        val columns = min(17, max(minimum, ceil(bounds[2] / 48.0).toInt() + 1))
        val rows = min(17, max(minimum, ceil(bounds[3] / 48.0).toInt() + 1))
        val vertices = DoubleArray(columns * rows * 2)
        val uv = FloatArray(vertices.size)
        for (row in 0 until rows) for (column in 0 until columns) {
            val index = (row * columns + column) * 2
            val u = column.toDouble() / (columns - 1)
            val v = row.toDouble() / (rows - 1)
            vertices[index] = bounds[0] + u * bounds[2]
            vertices[index + 1] = bounds[1] + v * bounds[3]
            uv[index] = u.toFloat()
            uv[index + 1] = v.toFloat()
        }
        val indices = indices(columns, rows)
        val deltas = CHANNELS.associateWithTo(LinkedHashMap()) { FloatArray(vertices.size) }
        for (index in vertices.indices step 2) {
            val x = vertices[index]
            val y = vertices[index + 1]
            val evaluatedY = if (feature) bounds[1] + bounds[3] / 2.0 else y
            val transition = max(1.0, max(anchors.neck.y - anchors.head.y, max(width, height) * .20))
            val weight = max(0.0, min(1.0, (anchors.neck.y - evaluatedY) / transition))
            put(deltas, "headX", index, width * .022 * weight, 0.0)
            put(deltas, "headY", index, 0.0, height * .013 * weight)
            val angle = .045 * weight
            put(deltas, "tilt", index, -(y - anchors.head.y) * angle, (x - anchors.head.x) * angle)
            if (role == "eye_left" || role == "eye_right") {
                val delta = featureDelta(role, x, y, bounds)
                put(deltas, if (role == "eye_left") "blinkLeft" else "blinkRight", index, delta.first, delta.second)
            }
            if (role == "mouth") {
                val delta = featureDelta(role, x, y, bounds)
                put(deltas, "mouth", index, delta.first, delta.second)
            }
            put(deltas, "breath", index, 0.0, -(height - y) * .004 * (1 - weight * .6))
            if (role == "hair" || role == "accessory") {
                val scale = .06 * strength
                put(deltas, "sway", index, -(y - pivot.y) * scale, (x - pivot.x) * scale)
            }
        }
        val active = when (motionMode) {
            PlanarMotionMode.STATIC -> emptySet()
            PlanarMotionMode.EXPRESSION -> setOf("blinkLeft", "blinkRight", "mouth")
            PlanarMotionMode.CHARACTER -> CHANNELS.toSet()
        }
        if (motionMode != PlanarMotionMode.CHARACTER) deltas.forEach { (name, values) -> if (name !in active) values.fill(0f) }
        return PlanarMesh(contract, FloatArray(vertices.size) { vertices[it].toFloat() }, uv, indices, deltas)
    }

    private fun indices(columns: Int, rows: Int): IntArray {
        val result = IntArray((columns - 1) * (rows - 1) * 6)
        var next = 0
        for (row in 0 until rows - 1) for (column in 0 until columns - 1) {
            val i = row * columns + column
            for (value in intArrayOf(i, i + 1, i + columns, i + 1, i + columns + 1, i + columns)) result[next++] = value
        }
        return result
    }

    private fun featureDelta(role: String, x: Double, y: Double, bounds: IntArray): Pair<Double, Double> {
        val dx = x - bounds[0] - bounds[2] / 2.0
        val dy = y - bounds[1] - bounds[3] / 2.0
        var envelope = max(0.0, 1 - abs(2 * dx / bounds[2])) * max(0.0, 1 - abs(2 * dy / bounds[3]))
        if (role == "eye_left" || role == "eye_right") {
            envelope = padded(abs(2 * dx / bounds[2]), .8) * padded(abs(2 * dy / bounds[3]), .6)
            return 0.0 to -dy * .90 * envelope
        }
        return dx * .10 * envelope to dy * .45 * envelope
    }

    private fun padded(distance: Double, inner: Double): Double =
        if (distance <= inner) 1.0 else max(0.0, (1 - distance) / (1 - inner))

    private fun put(deltas: Map<String, FloatArray>, key: String, index: Int, x: Double, y: Double) {
        deltas.getValue(key)[index] = x.toFloat()
        deltas.getValue(key)[index + 1] = y.toFloat()
    }

    private val CHANNELS = listOf("headX", "headY", "tilt", "blinkLeft", "blinkRight", "mouth", "breath", "sway")
}
