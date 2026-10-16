package com.zhiyu.app.domain.planar

import org.junit.Assert.*
import org.junit.Test

class AutomaticFaceRegionRulesTest {
    private val face = DetectedFaceRegion(160.0, 125.0, 40.0, .93)

    @Test fun aFaceTooSmallForVisibleIdleMotionRequiresManualMarking() {
        assertNull(AutomaticFaceRegionRules.suggest(320, 480, listOf(face.copy(eyesDistance = 12.0))))
        assertNotNull(AutomaticFaceRegionRules.suggest(320, 480, listOf(face.copy(eyesDistance = 18.0))))
    }

    @Test fun knownFrontalFaceGetsOnlyALocalHeadWithoutInventedEyeOrMouthActions() {
        val draft = requireNotNull(AutomaticFaceRegionRules.suggest(320, 480, listOf(face)))
        draft.validate(320, 480)
        assertEquals(PlanarFeatureVisibility(false, false, false), draft.featureVisibility)
        assertEquals(1, draft.parts.size)
        val part = draft.parts.single()
        assertEquals("face", part.role)
        assertTrue(part.polygon.all { it.x in 108.0..212.0 && it.y in 73.0..197.0 })
        assertFalse(draft.suggestionsConfirmed)
    }

    @Test fun noFaceAmbiguousFaceSideFaceAndUncertainDetectionAllRequireManualMarking() {
        listOf(emptyList(), listOf(face, face.copy(midpointX = 260.0)), listOf(face.copy(confidence = .55)),
            listOf(face.copy(yaw = 32.0)), listOf(face.copy(roll = -28.0)),
            listOf(face.copy(eyesDistance = Double.NaN)), listOf(face.copy(midpointY = -1.0)),
            listOf(face.copy(eyesDistance = 3.0))).forEach { faces ->
            assertNull(AutomaticFaceRegionRules.suggest(320, 480, faces))
        }
    }

    @Test fun edgeClippedOrExtremelyLargeFaceDoesNotMarkTheBackgroundAsACharacter() {
        assertNull(AutomaticFaceRegionRules.suggest(320, 480, listOf(face.copy(midpointX = 12.0))))
        assertNull(AutomaticFaceRegionRules.suggest(320, 480, listOf(face.copy(eyesDistance = 180.0))))
    }
}
