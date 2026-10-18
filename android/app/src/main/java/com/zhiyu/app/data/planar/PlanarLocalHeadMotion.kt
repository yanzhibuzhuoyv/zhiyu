package com.zhiyu.app.data.planar

import com.zhiyu.app.domain.planar.AutomaticFaceMotion
import rig2d.planar.PlanarMesh
import kotlin.math.floor

/** The face outline stays still; an owned eye or mouth samples that same interior motion field. */
internal object PlanarLocalHeadMotion {
    fun create(mesh: PlanarMesh, strength: Double = 1.0): PlanarMesh {
        require(strength.isFinite() && strength in 0.0..1.0) { "头部微动强度超出范围" }
        // PlanarMesh permits sparse bases; AutomaticFaceMotion expects every contract channel.
        val base = mesh.baseVertices()
        val supplied = mesh.deltas()
        val complete = mesh.contract.parameters.keys.associateWith { supplied[it] ?: FloatArray(base.size) }
        val local = AutomaticFaceMotion.create(PlanarMesh(mesh.contract, base, mesh.uv(), mesh.indices(), complete))
        if (strength == 1.0) return local
        val deltas = local.deltas().mapValues { (_, values) ->
            FloatArray(values.size) { (values[it] * strength).toFloat() }
        }
        return PlanarMesh(local.contract, local.baseVertices(), local.uv(), local.indices(), deltas)
    }

    fun inherit(child: PlanarMesh, parent: PlanarMesh): PlanarMesh {
        require(child.contract == parent.contract)
        val parentBase = parent.baseVertices()
        val xs = parentBase.filterIndexed { index, _ -> index % 2 == 0 }.distinct().sorted()
        val ys = parentBase.filterIndexed { index, _ -> index % 2 == 1 }.distinct().sorted()
        val columns = xs.size
        val parentDeltas = parent.deltas()
        val base = child.baseVertices()
        val deltas = child.deltas().mapValues { (_, values) -> values.copyOf() }.toMutableMap()
        for (channel in listOf("headX", "headY", "tilt", "breath", "sway")) {
            val field = parentDeltas.getValue(channel)
            deltas[channel] = FloatArray(base.size).also { values ->
                for (index in base.indices step 2) {
                    values[index] = sample(field, 0, base[index], base[index + 1], xs, ys, columns)
                    values[index + 1] = sample(field, 1, base[index], base[index + 1], xs, ys, columns)
                }
            }
        }
        return PlanarMesh(child.contract, base, child.uv(), child.indices(), deltas)
    }

    private fun sample(field: FloatArray, axis: Int, x: Float, y: Float,
        xs: List<Float>, ys: List<Float>, columns: Int): Float {
        if (x < xs.first() || x > xs.last() || y < ys.first() || y > ys.last()) return 0f
        val column = floor((x - xs.first()) / (xs.last() - xs.first()) * (columns - 1)).toInt().coerceIn(0, columns - 2)
        val row = floor((y - ys.first()) / (ys.last() - ys.first()) * (ys.size - 1)).toInt().coerceIn(0, ys.size - 2)
        val u = ((x - xs[column]) / (xs[column + 1] - xs[column])).coerceIn(0f, 1f)
        val v = ((y - ys[row]) / (ys[row + 1] - ys[row])).coerceIn(0f, 1f)
        fun at(dx: Int, dy: Int) = field[((row + dy) * columns + column + dx) * 2 + axis]
        return if (u + v <= 1f) at(0, 0) * (1 - u - v) + at(1, 0) * u + at(0, 1) * v
            else at(1, 1) * (u + v - 1) + at(0, 1) * (1 - u) + at(1, 0) * (1 - v)
    }
}
