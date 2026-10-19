package com.zhiyu.app.data.planar

import rig2d.planar.PlanarMesh
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A manual contour need not contain its rectangle centre, or resemble the original ellipse. */
internal object PlanarMaskedHeadMotion {
    fun create(mesh: PlanarMesh, width: Int, height: Int, mask: ByteArray, strength: Double): PlanarMesh {
        require(width in 1..1536 && height in 1..1536 && mask.size == width * height) { "头部活动范围无效" }
        require(strength.isFinite() && strength in 0.0..1.0) { "头部微动强度超出范围" }
        val original = mesh.baseVertices()
        val left = original.filterIndexed { index, _ -> index % 2 == 0 }.min()
        val top = original.filterIndexed { index, _ -> index % 2 == 1 }.min()
        val right = original.filterIndexed { index, _ -> index % 2 == 0 }.max()
        val bottom = original.filterIndexed { index, _ -> index % 2 == 1 }.max()
        require(right - left == width.toFloat() && bottom - top == height.toFloat()) { "头部网格与活动范围不一致" }
        val side = minOf(33, sqrt(mesh.contract.maxVertices.toDouble()).toInt(),
            sqrt(mesh.contract.maxTriangles / 2.0).toInt() + 1)
        require(side >= 2) { "头部网格预算不足" }
        val base = FloatArray(side * side * 2)
        val uv = FloatArray(base.size)
        val deltas = mesh.contract.parameters.keys.associateWith { FloatArray(base.size) }
        val distances = distances(width, height, mask)
        // Every triangle incident to a moving vertex fits inside this clearance. Holes and the
        // original manually drawn boundary therefore stay fixed, including after brush edits.
        val safety = hypot(width.toFloat() / (side - 1), height.toFloat() / (side - 1))
        for (row in 0 until side) for (column in 0 until side) {
            val index = (row * side + column) * 2
            val u = column.toFloat() / (side - 1)
            val v = row.toFloat() / (side - 1)
            val x = u * width; val y = v * height
            base[index] = left + x; base[index + 1] = top + y
            uv[index] = u; uv[index + 1] = v
            val clearance = clearance(x, y, width, height, distances)
            val amount = min(20f, max(0f, clearance - safety) * .6f) * strength.toFloat()
            // The sum of all channel extrema remains below the available interior margin.
            deltas.getValue("headX")[index] = amount * .55f
            deltas.getValue("headY")[index + 1] = amount * .30f
            deltas.getValue("breath")[index + 1] = -amount * .15f
        }
        return PlanarMesh(mesh.contract, base, uv, indices(side), deltas)
    }

    /** Eight-neighbour unit distance is a conservative lower bound on Euclidean clearance. */
    private fun distances(width: Int, height: Int, mask: ByteArray): IntArray {
        val distance = IntArray(mask.size) { index ->
            if (mask[index] == 0.toByte()) 0 else minOf(index % width + 1, width - index % width,
                index / width + 1, height - index / width)
        }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (x > 0) distance[i] = min(distance[i], distance[i - 1] + 1)
            if (y > 0) {
                distance[i] = min(distance[i], distance[i - width] + 1)
                if (x > 0) distance[i] = min(distance[i], distance[i - width - 1] + 1)
                if (x + 1 < width) distance[i] = min(distance[i], distance[i - width + 1] + 1)
            }
        }
        for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) {
            val i = y * width + x
            if (x + 1 < width) distance[i] = min(distance[i], distance[i + 1] + 1)
            if (y + 1 < height) {
                distance[i] = min(distance[i], distance[i + width] + 1)
                if (x > 0) distance[i] = min(distance[i], distance[i + width - 1] + 1)
                if (x + 1 < width) distance[i] = min(distance[i], distance[i + width + 1] + 1)
            }
        }
        return distance
    }

    private fun clearance(x: Float, y: Float, width: Int, height: Int, distances: IntArray): Float {
        val px = floor(x).toInt(); val py = floor(y).toInt()
        if (px !in 0 until width || py !in 0 until height) return 0f
        return max(0f, distances[py * width + px] - .5f - max(abs(x - px - .5f), abs(y - py - .5f)))
    }

    private fun indices(side: Int): IntArray {
        val result = IntArray((side - 1) * (side - 1) * 6)
        var next = 0
        for (row in 0 until side - 1) for (column in 0 until side - 1) {
            val i = row * side + column
            for (value in intArrayOf(i, i + 1, i + side, i + 1, i + side + 1, i + side)) result[next++] = value
        }
        return result
    }
}
