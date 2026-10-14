package com.zhiyu.app.domain.planar

import org.junit.Assert.*
import org.junit.Test
import rig2d.planar.ParameterRange
import rig2d.planar.PlanarContract
import rig2d.planar.PlanarMixer
import rig2d.planar.PlanarParameters

class PlanarBackgroundBasisTest {
    @Test fun sceneGeometryRemainsFixedAtEveryChannelEndpoint() {
        val draft = PlanarDraft.suggest(128, 192)
        val contract = contract()
        val fixed = PlanarBasisFactory.create(intArrayOf(0, 0, 128, 192), "base", draft.anchors,
            draft.anchors.neck, 1.0, 128, 192, contract, PlanarMotionMode.STATIC)
        val expected = fixed.baseVertices()
        contract.parameters.forEach { (channel, range) -> listOf(range.minimum, range.maximum).forEach { value ->
            assertArrayEquals(expected, PlanarMixer.vertices(fixed, PlanarParameters(contract, mapOf(channel to value))), 0f)
        } }
        assertTrue(fixed.deltas().values.all { delta -> delta.all { it == 0f } })
    }

    @Test fun anUnattachedEyeOnlyHasLocalExpressionMotion() {
        val draft = PlanarDraft.suggest(128, 192)
        val contract = contract()
        val eye = PlanarBasisFactory.create(intArrayOf(44, 41, 15, 10), "eye_left", draft.anchors,
            draft.anchors.head, 1.0, 128, 192, contract, PlanarMotionMode.EXPRESSION)
        assertTrue(eye.deltas().getValue("blinkLeft").any { it != 0f })
        listOf("headX", "headY", "tilt", "breath", "sway").forEach { channel ->
            assertArrayEquals(eye.baseVertices(), PlanarMixer.vertices(eye, PlanarParameters(contract, mapOf(channel to 1f))), 0f)
        }
    }

    private fun contract() = PlanarContract(1536, 1089, 2048, linkedMapOf(
        "headX" to ParameterRange(-1f, 1f, 0f), "headY" to ParameterRange(-1f, 1f, 0f),
        "tilt" to ParameterRange(-1f, 1f, 0f), "blinkLeft" to ParameterRange(0f, 1f, 0f),
        "blinkRight" to ParameterRange(0f, 1f, 0f), "mouth" to ParameterRange(0f, 1f, 0f),
        "breath" to ParameterRange(0f, 1f, 0f), "sway" to ParameterRange(-1f, 1f, 0f)))
}
