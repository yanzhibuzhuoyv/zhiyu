package rig2d.planar

import org.junit.Assert.*
import org.junit.Test

class PlanarAnimationTest {
    @Test fun pausedIdleWithNoVoiceOrGazeIsExactNeutral() {
        val contract = contractFixture()
        assertEquals(PlanarParameters(contract).toMap(), PlanarAnimation.sample(contract, 123.0, idleEnabled = false).toMap())
    }

    @Test fun animationIsDeterministicAndRemainsWithinSharedRanges() {
        val contract = contractFixture()
        for (seconds in listOf(0.0, .5, 4.3, 20.0, 123456.7, Double.MAX_VALUE)) {
            val first = PlanarAnimation.sample(contract, seconds, .75f, gazeX = 1f, gazeY = -1f)
            assertEquals(first.toMap(), PlanarAnimation.sample(contract, seconds, .75f, gazeX = 1f, gazeY = -1f).toMap())
            contract.parameters.forEach { (name, range) -> assertTrue(first[name].isFinite() && first[name] in range.minimum..range.maximum) }
        }
    }

    @Test fun voiceRmsControlsMouthEvenWhenIdlePaused() {
        val contract = contractFixture()
        assertEquals(contract.parameters.getValue("mouth").default,
            PlanarAnimation.sample(contract, 0.0, 0f, false)["mouth"], 0f)
        assertEquals(contract.parameters.getValue("mouth").maximum,
            PlanarAnimation.sample(contract, 0.0, 1f, false)["mouth"], 0f)
        assertTrue(PlanarAnimation.sample(contract, 0.0, .7f, false)["mouth"] >
            PlanarAnimation.sample(contract, 0.0, .1f, false)["mouth"])
    }

    @Test fun blinkPulseAndIndependentGazeCreateRealParameterMotion() {
        val contract = contractFixture()
        assertEquals(contract.parameters.getValue("blinkLeft").maximum,
            PlanarAnimation.sample(contract, 4.3)["blinkLeft"], .00001f)
        assertEquals(contract.parameters.getValue("blinkRight").maximum,
            PlanarAnimation.sample(contract, 4.3)["blinkRight"], .00001f)
        val left = PlanarAnimation.sample(contract, 0.0, idleEnabled = false, gazeX = -1f)
        val right = PlanarAnimation.sample(contract, 0.0, idleEnabled = false, gazeX = 1f)
        assertTrue(left["headX"] < 0f && right["headX"] > 0f)
        assertTrue(PlanarAnimation.sample(contract, 0.0)["breath"] >= 0f)
        assertNotEquals(PlanarAnimation.sample(contract, .5)["sway"], PlanarAnimation.sample(contract, 1.0)["sway"])
    }

    @Test fun invalidTimeRmsAndGazeRejected() {
        val contract = contractFixture()
        for (time in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { PlanarAnimation.sample(contract, time) }
        }
        for (value in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { PlanarAnimation.sample(contract, 0.0, value) }
        }
        for (value in listOf(-1.1f, 1.1f, Float.NaN)) {
            assertThrows(IllegalArgumentException::class.java) { PlanarAnimation.sample(contract, 0.0, gazeX = value) }
            assertThrows(IllegalArgumentException::class.java) { PlanarAnimation.sample(contract, 0.0, gazeY = value) }
        }
    }

    @Test fun animationRespectsCustomContractRangesInsteadOfHardCodedValues() {
        val original = contractFixture()
        val ranges = original.parameters.mapValues { (_, range) ->
            ParameterRange(range.minimum * .3f, range.maximum * .3f, range.default * .3f)
        }
        val contract = PlanarContract(original.maxSide, original.maxVertices, original.maxTriangles, ranges)
        val sample = PlanarAnimation.sample(contract, 4.3, 1f)
        assertEquals(.3f, sample["mouth"], 0f)
        assertEquals(.3f, sample["blinkLeft"], .00001f)
    }
}
