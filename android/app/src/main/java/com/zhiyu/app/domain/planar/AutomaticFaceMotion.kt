package com.zhiyu.app.domain.planar

import rig2d.planar.PlanarMesh

/** Compact support keeps the cut-out face contour, not just the texture rectangle, stationary. */
object AutomaticFaceMotion {
    fun create(mesh: PlanarMesh): PlanarMesh {
        val original = mesh.baseVertices()
        val xs = original.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = original.filterIndexed { index, _ -> index % 2 == 1 }
        val left = xs.min(); val top = ys.min()
        val width = xs.max() - left; val height = ys.max() - top
        require(width > 0 && height > 0) { "人物局部范围无效" }
        // At least nine samples per axis bound a triangle's normalized diagonal to sqrt(2)/4.
        // The .55-radius support plus that diagonal remains inside the 24-sided face contour.
        val columns = maxOf(9, xs.distinct().size); val rows = maxOf(9, ys.distinct().size)
        val base = FloatArray(columns * rows * 2)
        val uv = FloatArray(base.size)
        for (row in 0 until rows) for (column in 0 until columns) {
            val index = (row * columns + column) * 2
            val u = column.toFloat() / (columns - 1); val v = row.toFloat() / (rows - 1)
            base[index] = left + u * width; base[index + 1] = top + v * height
            uv[index] = u; uv[index + 1] = v
        }
        val deltas = mesh.deltas().mapValues { FloatArray(base.size) }
        for (index in base.indices step 2) {
            val x = 2f * (base[index] - left) / width - 1f
            val y = 2f * (base[index + 1] - top) / height - 1f
            val distance = (x * x + y * y) / (.55f * .55f)
            val envelope = if (distance >= 1f) 0f else (1f - distance) * (1f - distance)
            deltas.getValue("headX")[index] = minOf(12f, width * .1f) * envelope
            deltas.getValue("headY")[index + 1] = minOf(8f, height * .06f) * envelope
            deltas.getValue("breath")[index + 1] = -minOf(5f, height * .05f) * envelope
        }
        return PlanarMesh(mesh.contract, base, uv, indices(columns, rows), deltas)
    }

    private fun indices(columns: Int, rows: Int): IntArray {
        val result = IntArray((columns - 1) * (rows - 1) * 6)
        var next = 0
        for (row in 0 until rows - 1) for (column in 0 until columns - 1) {
            val index = row * columns + column
            for (vertex in intArrayOf(index, index + 1, index + columns, index + 1, index + columns + 1, index + columns))
                result[next++] = vertex
        }
        return result
    }
}
