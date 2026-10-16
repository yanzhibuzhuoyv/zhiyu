package com.zhiyu.app.domain.planar

import org.junit.Assert.*
import org.junit.Test
import rig2d.planar.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AutomaticFaceMotionTest {
    @Test fun ellipticalFaceContourStaysFixedInsideItsRectangularTextureBounds() {
        val draft = PlanarDraft.suggest(640, 960)
        val face = AutomaticFaceMotion.create(PlanarBasisFactory.create(intArrayOf(200, 200, 240, 300),
            "face", draft.anchors, draft.anchors.head, 1.0, 640, 960, contract()))
        // A stationary texture rectangle does not by itself pin the ellipse cut out of that texture.
        for (angle in 0 until 360 step 5) {
            val radians = angle * Math.PI / 180
            val x = (320 + 120 * cos(radians)).toFloat()
            val y = (350 + 150 * sin(radians)).toFloat()
            face.deltas().forEach { (channel, delta) ->
                val offset = interpolated(face, delta, x, y)
                assertEquals("$channel moves the face contour at $angle degrees", 0f, offset.first, .0001f)
                assertEquals("$channel moves the face contour at $angle degrees", 0f, offset.second, .0001f)
            }
        }
    }

    private fun mesh(): PlanarMesh {
        val draft = PlanarDraft.suggest(640, 960)
        return PlanarBasisFactory.create(intArrayOf(200, 200, 120, 160), "face", draft.anchors,
            draft.anchors.head, 1.0, 640, 960, contract())
    }

    @Test fun everyBoundaryVertexRemainsExactlyFixedForEveryMotionChannel() {
        val mesh = AutomaticFaceMotion.create(mesh())
        val base = mesh.baseVertices()
        val left = base.filterIndexed { index, _ -> index % 2 == 0 }.min()
        val right = base.filterIndexed { index, _ -> index % 2 == 0 }.max()
        val top = base.filterIndexed { index, _ -> index % 2 == 1 }.min()
        val bottom = base.filterIndexed { index, _ -> index % 2 == 1 }.max()
        mesh.deltas().values.forEach { delta ->
            for (index in base.indices step 2) if (base[index] in setOf(left, right) || base[index + 1] in setOf(top, bottom)) {
                assertEquals(0f, delta[index], 0f); assertEquals(0f, delta[index + 1], 0f)
            }
        }
    }

    @Test fun actualIdleSamplesMoveTheInteriorByAtLeastHalfAPixelWithoutInventingEyesOrMouth() {
        val mesh = AutomaticFaceMotion.create(mesh()); val base = mesh.baseVertices()
        val moved = PlanarMixer.vertices(mesh, PlanarAnimation.sample(mesh.contract, 1.3, 1f, true))
        val largest = moved.indices.maxOf { abs(moved[it] - base[it]) }
        assertTrue("idle displacement=$largest", largest in .5f..3f)
        listOf("mouth", "blinkLeft", "blinkRight", "tilt", "sway").forEach { channel ->
            assertTrue(mesh.deltas().getValue(channel).all { it == 0f })
        }
    }

    @Test fun channelEndpointsAndSimultaneousCornerPosesNeverFoldTheLocalMesh() {
        val mesh = AutomaticFaceMotion.create(mesh())
        listOf(-1f, 1f).forEach { x -> listOf(-1f, 1f).forEach { y -> listOf(0f, 1f).forEach { breath ->
            val pose = PlanarParameters(mesh.contract, mapOf("headX" to x, "headY" to y, "breath" to breath))
            assertTrue(PlanarMixer.vertices(mesh, pose).all { it.isFinite() })
        } } }
        assertArrayEquals(mesh.baseVertices(), PlanarMixer.vertices(mesh, PlanarParameters(mesh.contract, emptyMap())), 0f)
    }

    private fun interpolated(mesh: PlanarMesh, delta: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val base = mesh.baseVertices(); val triangles = mesh.indices()
        for (triangle in triangles.indices step 3) {
            val a = triangles[triangle] * 2; val b = triangles[triangle + 1] * 2; val c = triangles[triangle + 2] * 2
            val denominator = (base[b + 1] - base[c + 1]) * (base[a] - base[c]) + (base[c] - base[b]) * (base[a + 1] - base[c + 1])
            val wa = ((base[b + 1] - base[c + 1]) * (x - base[c]) + (base[c] - base[b]) * (y - base[c + 1])) / denominator
            val wb = ((base[c + 1] - base[a + 1]) * (x - base[c]) + (base[a] - base[c]) * (y - base[c + 1])) / denominator
            val wc = 1f - wa - wb
            if (minOf(wa, wb, wc) < -.0001f) continue
            return (wa * delta[a] + wb * delta[b] + wc * delta[c]) to
                (wa * delta[a + 1] + wb * delta[b + 1] + wc * delta[c + 1])
        }
        error("Face contour left the source mesh")
    }

    private fun contract() = PlanarContract(1536, 1089, 2048, linkedMapOf(
        "headX" to ParameterRange(-1f, 1f, 0f), "headY" to ParameterRange(-1f, 1f, 0f),
        "tilt" to ParameterRange(-1f, 1f, 0f), "blinkLeft" to ParameterRange(0f, 1f, 0f),
        "blinkRight" to ParameterRange(0f, 1f, 0f), "mouth" to ParameterRange(0f, 1f, 0f),
        "breath" to ParameterRange(0f, 1f, 0f), "sway" to ParameterRange(-1f, 1f, 0f)))
}
