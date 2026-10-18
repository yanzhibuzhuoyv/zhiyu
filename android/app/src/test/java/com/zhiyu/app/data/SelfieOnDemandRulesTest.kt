package com.zhiyu.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfieOnDemandRulesTest {
    @Test fun generationNeedsAConfirmedOcBothKeysAndAReviewer() {
        assertTrue(SelfieOnDemandRules.shouldGenerate(true, true, true))
        assertFalse(SelfieOnDemandRules.shouldGenerate(false, true, true))
        assertFalse(SelfieOnDemandRules.shouldGenerate(true, false, true))
        assertFalse(SelfieOnDemandRules.shouldGenerate(true, true, false))
    }
}
