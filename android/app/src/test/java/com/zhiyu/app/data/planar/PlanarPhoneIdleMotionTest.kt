package com.zhiyu.app.data.planar

import org.junit.Assert.*
import org.junit.Test
import rig2d.planar.ParameterRange
import rig2d.planar.PlanarAnimation
import rig2d.planar.PlanarContract

class PlanarPhoneIdleMotionTest {
    private val contract = PlanarPackageReader(PlanarPackageFixtures.contract).contract

    @Test fun aVisibleHeadTravelsBothWaysWithinTheFirstFourSecondsWithoutChangingExpressionInputs() {
        val samples = (0..80).map { tick -> PlanarPhoneIdleMotion.sample(contract, tick / 20.0, .7f, true, true) }
        assertTrue(samples.maxOf { it["headX"] } > .7f)
        assertTrue(samples.minOf { it["headX"] } < -.7f)
        samples.forEachIndexed { tick, actual ->
            val original = PlanarAnimation.sample(contract, tick / 20.0, .7f, true)
            for (name in contract.parameters.keys - setOf("headX", "headY"))
                assertEquals("$name must retain its original drive", original[name], actual[name], 0f)
        }
    }

    @Test fun hiddenIdleAndPackagesWithoutAHeadKeepThePortablePoseExactly() {
        for (seconds in listOf(0.0, 1.5, 4.3, 63.0)) {
            assertEquals(PlanarAnimation.sample(contract, seconds, .6f, false).toMap(),
                PlanarPhoneIdleMotion.sample(contract, seconds, .6f, false, true).toMap())
            assertEquals(PlanarAnimation.sample(contract, seconds, .6f, true).toMap(),
                PlanarPhoneIdleMotion.sample(contract, seconds, .6f, true, false).toMap())
        }
    }

    @Test fun phoneMotionRespectsCustomRangesAndDoesNotMutateTheContract() {
        val ranges = contract.parameters + ("headX" to ParameterRange(-.2f, .8f, .1f))
        val custom = PlanarContract(contract.maxSide, contract.maxVertices, contract.maxTriangles, ranges)
        for (tick in 0..200) {
            val pose = PlanarPhoneIdleMotion.sample(custom, tick / 10.0, .3f, true, true)
            custom.parameters.forEach { (name, range) -> assertTrue(pose[name] in range.minimum..range.maximum) }
        }
        assertEquals(ranges, custom.parameters)
    }

    @Test fun aLongOpenPreviewDoesNotJumpAtThePortableSixtySecondWrap() {
        val before = PlanarPhoneIdleMotion.sample(contract, 59.9999, 0f, true, true)
        val after = PlanarPhoneIdleMotion.sample(contract, 60.0001, 0f, true, true)
        for (channel in listOf("headX", "headY"))
            assertEquals("$channel must remain continuous while an actual call stays open", before[channel], after[channel], .001f)
    }
}
