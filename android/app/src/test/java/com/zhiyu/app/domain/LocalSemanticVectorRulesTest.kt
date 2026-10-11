package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

/** Tests vector math and validation only; these are not semantic accuracy measurements. */
class LocalSemanticVectorRulesTest {
    @Test fun realFloatOutputsAreNormalizedWithoutChangingDirection() {
        val normalized = LocalSemanticVectorRules.normalized(floatArrayOf(3f,4f),2)
        assertArrayEquals(floatArrayOf(0.6f,0.8f),normalized,0.00001f)
        val bytes = LocalSemanticVectorRules.encode(floatArrayOf(3f,4f),2)
        assertEquals(1.0,LocalSemanticVectorRules.score(bytes,listOf(normalized),2),0.00001)
        assertEquals(0.0,LocalSemanticVectorRules.score(bytes,listOf(floatArrayOf(-0.8f,0.6f)),2),0.00001)
    }
    @Test fun independentQueryChunksUseTheHighestSimilarityRatherThanAnAverage() {
        val bytes = LocalSemanticVectorRules.encode(floatArrayOf(1f,0f),2)
        assertEquals(1.0,LocalSemanticVectorRules.score(bytes,listOf(floatArrayOf(0f,1f),floatArrayOf(1f,0f)),2),0.00001)
    }
    @Test fun invalidDimensionsNonfiniteValuesAndZeroVectorsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { LocalSemanticVectorRules.encode(floatArrayOf(Float.NaN,1f),2) }
        assertThrows(IllegalArgumentException::class.java) { LocalSemanticVectorRules.encode(floatArrayOf(0f,0f),2) }
        assertThrows(IllegalArgumentException::class.java) { LocalSemanticVectorRules.encode(floatArrayOf(1f),2) }
        assertThrows(IllegalArgumentException::class.java) { LocalSemanticVectorRules.score(ByteArray(8),listOf(floatArrayOf(1f,0f)),2) }
    }
}
