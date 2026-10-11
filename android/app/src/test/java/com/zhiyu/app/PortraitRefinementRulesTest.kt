package com.zhiyu.app

import com.zhiyu.app.domain.PortraitPoint
import com.zhiyu.app.domain.PortraitStroke
import com.zhiyu.app.domain.PortraitRefinementRules
import org.junit.Assert.*
import org.junit.Test

class PortraitRefinementRulesTest {
    @Test fun permitsManySeparateWingsAndWeaponsWithoutAnatomyRules() {
        val strokes = (0..13).map { PortraitStroke("keep", listOf(PortraitPoint(it / 14f, .5f)), .02f) }
        assertEquals(14, PortraitRefinementRules.check(strokes).size)
    }
    @Test fun preservesStrokeOrderAndDoesNotChangeCallerList() {
        val input = listOf(PortraitStroke("keep", listOf(PortraitPoint(0f, 1f)), .002f),
            PortraitStroke("remove", listOf(PortraitPoint(1f, 0f)), .15f))
        assertEquals(input, PortraitRefinementRules.check(input))
        assertNotSame(input, PortraitRefinementRules.check(input))
    }
    @Test fun rejectsInvalidActionAndNonFiniteCoordinates() {
        for (stroke in listOf(PortraitStroke("crop", listOf(PortraitPoint(.5f, .5f)), .02f),
            PortraitStroke("keep", listOf(PortraitPoint(Float.NaN, .5f)), .02f),
            PortraitStroke("keep", listOf(PortraitPoint(.5f, Float.POSITIVE_INFINITY)), .02f),
            PortraitStroke("keep", listOf(PortraitPoint(-.01f, .5f)), .02f))) rejects(listOf(stroke))
    }
    @Test fun rejectsInvalidRadiusAndEmptyOrOversizedStroke() {
        for (radius in listOf(0f, .001f, .151f, Float.NaN, Float.NEGATIVE_INFINITY))
            rejects(listOf(PortraitStroke("keep", listOf(PortraitPoint(.5f, .5f)), radius)))
        rejects(listOf(PortraitStroke("keep", emptyList(), .02f)))
        rejects(listOf(PortraitStroke("keep", List(1025) { PortraitPoint(.5f, .5f) }, .02f)))
        rejects(List(129) { PortraitStroke("keep", listOf(PortraitPoint(.5f, .5f)), .02f) })
        rejects(emptyList())
    }
    @Test fun permitsBoundsAndExactSafetyLimits() {
        assertEquals(128, PortraitRefinementRules.check(List(128) {
            PortraitStroke("remove", List(1024) { PortraitPoint(1f, 0f) }, .15f)
        }).size)
    }
    private fun rejects(strokes: List<PortraitStroke>) {
        try { PortraitRefinementRules.check(strokes); fail("should reject") }
        catch (_: IllegalArgumentException) { }
    }
}
