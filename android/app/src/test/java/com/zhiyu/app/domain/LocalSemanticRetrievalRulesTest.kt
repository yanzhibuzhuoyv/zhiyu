package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class LocalSemanticRetrievalRulesTest {
    @Test fun frozenCalibrationBoundariesKeepUncertainCandidatesForSupplementalSearch() {
        assertEquals(LocalSemanticRoute.LEXICAL,LocalSemanticRetrievalRules.route(.419999,true))
        assertEquals(LocalSemanticRoute.SUPPLEMENT,LocalSemanticRetrievalRules.route(.42,true))
        assertEquals(LocalSemanticRoute.SUPPLEMENT,LocalSemanticRetrievalRules.route(.559999,true))
        assertEquals(LocalSemanticRoute.SEMANTIC,LocalSemanticRetrievalRules.route(.56,true))
        assertEquals(LocalSemanticRoute.SUPPLEMENT,LocalSemanticRetrievalRules.route(.99,false))
        assertEquals(LocalSemanticRoute.LEXICAL,LocalSemanticRetrievalRules.route(null,true))
        assertTrue(LocalSemanticRetrievalRules.CALIBRATION_SHA256.matches(Regex("[a-f0-9]{64}")))
    }
    @Test fun invalidCosineAndReversedThresholdsAreRejected() {
        for (score in listOf(Double.NaN,Double.POSITIVE_INFINITY,1.01,-1.01)) {
            try { LocalSemanticRetrievalRules.route(score,true); fail("invalid score accepted") }
            catch (_:IllegalArgumentException) { }
        }
        try { LocalSemanticRetrievalRules.route(.5,true,.4,.6); fail("reversed thresholds accepted") }
        catch (_:IllegalArgumentException) { }
    }
}
