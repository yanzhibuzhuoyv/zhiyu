package com.zhiyu.app

import com.zhiyu.app.data.SemanticVectorMath
import org.junit.Assert.*
import org.junit.Test

class SemanticVectorMathTest {
    @Test fun producesUnitVectorWithoutChangingInput() {
        val input = FloatArray(512).apply { this[0] = 3f; this[1] = 4f }
        val output = SemanticVectorMath.normalize(input)
        assertEquals(.6f, output[0], .00001f)
        assertEquals(.8f, output[1], .00001f)
        assertEquals(3f, input[0], 0f)
        assertEquals(1.0, output.sumOf { it.toDouble() * it }, .00001)
        assertNotSame(input, output)
    }
    @Test fun rejectsWrongDimensionsNonFiniteAndZeroVectors() {
        for (value in listOf(FloatArray(511), FloatArray(512), FloatArray(512).apply { this[0] = Float.NaN },
            FloatArray(512).apply { this[0] = Float.POSITIVE_INFINITY })) {
            try { SemanticVectorMath.normalize(value); fail("invalid vector accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
