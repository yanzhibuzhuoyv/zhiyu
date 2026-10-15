package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class OcFramingStockRulesTest {
    private val policy = OcFramingPolicy(OcAssetRef("a".repeat(32),"b".repeat(64),3),OcFraming.BUST,true,1,3)
    @Test fun choosingPolicyRejectsLegacyPeopleButDoesNotHideSceneryOrStickers() {
        assertFalse(OcFramingStockRules.permits(policy,setOf("daily_stock","contains_person"),true))
        assertTrue(OcFramingStockRules.permits(policy,setOf("daily_stock"),false))
        assertTrue(OcFramingStockRules.permits(null,setOf("contains_person"),true))
    }
    @Test fun currentProofBindsCompleteHashAndFitsMediaTagLimits() {
        val tags=OcFramingStockRules.tags(policy)
        assertTrue(tags.all {it.length<=40});assertTrue(OcFramingStockRules.permits(policy,tags.toSet(),true))
        assertTrue(tags.contains("frame_crop"));assertFalse(tags.contains("frame_full"))
        assertEquals(OcFramingRules.hash(policy),tags.first {it.startsWith("frame0_")}.removePrefix("frame0_")+
            tags.first {it.startsWith("frame1_")}.removePrefix("frame1_"))
    }
    @Test fun changingConsentOrOriginalInvalidatesEveryOldPersonStockProof() {
        val old=OcFramingStockRules.tags(policy).toSet()
        assertFalse(OcFramingStockRules.permits(policy.copy(revision=2,allowExpansion=false),old,true))
        assertFalse(OcFramingStockRules.permits(policy.copy(original=policy.original.copy(sha256="c".repeat(64))),old,true))
    }
    @Test fun fullReferenceProofCannotBeUsedAfterOptOutOrBeforeExplicitAcceptance() {
        val accepted=policy.copy(accepted=OcAssetRef("d".repeat(32),"e".repeat(64),3))
        val full=OcFramingStockRules.tags(accepted).toSet();assertTrue(full.contains("frame_full"))
        assertFalse(OcFramingStockRules.permits(policy,full,true))
        assertFalse(OcFramingStockRules.permits(policy.copy(allowExpansion=false,revision=2),full,true))
    }
    @Test fun malformedOrDuplicateProofSegmentsFailClosed() {
        val tags=OcFramingStockRules.tags(policy).toSet()
        assertFalse(OcFramingStockRules.permits(policy,tags+"frame0_${"f".repeat(32)}",true))
        assertFalse(OcFramingStockRules.permits(policy,tags.filterNot {it.startsWith("frame1_")}.toSet(),true))
    }
}
