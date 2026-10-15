package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class OcFramingRulesTest {
    private val source = OcAssetRef("a".repeat(32), "b".repeat(64), 3)
    private val partial = OcFramingPolicy(source, OcFraming.BUST, true, 1, 3)
    @Test fun partialNeedsConfirmedExtensionEvenWhenUserAllowsGeneration() {
        assertTrue(OcFramingRules.canExpand(partial)); assertFalse(OcFramingRules.permits(partial, true))
        assertEquals(source, OcFramingRules.reference(partial))
    }
    @Test fun optedOutAndUnknownCropNeverExpandOrPermitFullBody() {
        listOf(partial.copy(allowExpansion = false), partial.copy(framing = OcFraming.ORIGINAL)).forEach {
            assertFalse(OcFramingRules.canExpand(it)); assertFalse(OcFramingRules.permits(it, true))
            assertTrue(OcFramingRules.permits(it, false))
        }
    }
    @Test fun existingFullReferenceNeedsNoExpansionAndCanStillShowFullBody() {
        val full = partial.copy(framing = OcFraming.FULL, allowExpansion = false)
        assertFalse(OcFramingRules.canExpand(full)); assertTrue(OcFramingRules.permits(full, true))
    }
    @Test fun acceptedReferenceIsAdditiveAndRevokedByOptOut() {
        val full = partial.copy(accepted = OcAssetRef("c".repeat(32), "d".repeat(64), 4), visualVersion = 4)
        assertEquals(source, full.original); assertTrue(OcFramingRules.permits(full, true))
        assertEquals(full.accepted, OcFramingRules.reference(full))
        assertThrows(IllegalArgumentException::class.java) { OcFramingRules.check(full.copy(allowExpansion = false)) }
    }
    @Test fun metadataChangesInvalidateHashAndExternalInputsAreValidated() {
        assertNotEquals(OcFramingRules.hash(partial), OcFramingRules.hash(partial.copy(revision = 2)))
        assertNotEquals(OcFramingRules.hash(partial), OcFramingRules.hash(partial.copy(framing = OcFraming.HEAD)))
        assertThrows(IllegalArgumentException::class.java) { OcFramingRules.check(partial.copy(revision = -1)) }
        assertThrows(IllegalArgumentException::class.java) { OcFramingRules.check(partial.copy(original = source.copy(sha256 = "bad"))) }
    }
}
