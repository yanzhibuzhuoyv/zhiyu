package com.zhiyu.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfieOnDemandRulesTest {
    @Test fun generationNeedsAConfirmedOcBothKeysAndAReviewer() {
        assertTrue(SelfieOnDemandRules.shouldGenerate(true, true, true))
        assertFalse(SelfieOnDemandRules.shouldGenerate(false, true, true))
        assertFalse(SelfieOnDemandRules.shouldGenerate(true, false, true))
        assertFalse(SelfieOnDemandRules.shouldGenerate(true, true, false))
    }
    @Test fun onlyKnownAuthenticationAndBalanceFailuresCanBecomeAnAppNotice() {
        listOf("image_auth_failed", "image_balance_insufficient", "provider_auth_failed", "provider_balance_insufficient")
            .forEach { assertEquals(it, SelfieOnDemandRules.safeFailureReason(it)) }
        listOf(null, "unknown", "review_unknown", "image_request_unknown", "provider_timeout", "stale", "rejected",
            "image_auth_failed secret-key", "synthetic-secret-body")
            .forEach { assertNull(it, SelfieOnDemandRules.safeFailureReason(it)) }
    }
}
