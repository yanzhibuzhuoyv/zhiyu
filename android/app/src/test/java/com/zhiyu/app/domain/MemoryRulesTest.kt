package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class MemoryRulesTest {
    private fun entry() = MemoryEntry("m-1", "user", "想吃苹果🍎\u0000e\u0301", "2026-10-06T09:00:00Z", 11,
        "mid", "active", 2, false, "default", null, true)

    @Test fun originalExcerptPreservesUnicodeAndNull() {
        val value = entry()
        assertSame(value, MemoryRules.entry(value))
        assertEquals("想吃苹果🍎\u0000e\u0301", value.text)
    }
    @Test fun boundedExcerptCountsCodePointsInsteadOfUtf16Units() {
        val text = "🍎".repeat(768)
        assertEquals(text, MemoryRules.entry(entry().copy(text = text, charLength = 1000)).text)
        rejects { MemoryRules.entry(entry().copy(text = text + "a", charLength = 1000)) }
    }
    @Test fun metadataRejectsUnknownTierRoleStateAndNegativeRevision() {
        listOf(entry().copy(tier = "new"), entry().copy(role = "system"), entry().copy(indexState = "deleted"),
            entry().copy(revision = -1), entry().copy(charLength = 1), entry().copy(manualTier = "auto"))
            .forEach { value -> rejects { MemoryRules.entry(value) } }
    }
    @Test fun pageRequiresAnAdvanceableCursorAndAtMostFiftyEntries() {
        rejects { MemoryRules.page(MemoryPage(listOf(entry()), true, null, true)) }
        rejects { MemoryRules.page(MemoryPage(List(51) { entry().copy(messageId = "m-$it") }, false, null, false)) }
        rejects { MemoryRules.page(MemoryPage(listOf(entry(), entry()), false, null, false)) }
        assertFalse(MemoryRules.page(MemoryPage(listOf(entry()), false, null, false)).hasMore)
    }
    @Test fun pageRejectsUnchangingCursorInsteadOfLooping() {
        rejects { MemoryRules.page(MemoryPage(listOf(entry()), true, "same", true), "same") }
    }
    @Test fun revisionCannotBeOmittedOrOverwrittenByClientIncrement() {
        val change = MemoryRules.change(entry(), tier = "permanent")
        assertEquals(2, change.expectedRevision)
        assertEquals("permanent", change.tier)
        assertNull(change.indexState)
    }
    @Test fun autoRestoresPolicyWithoutDiscardingOriginal() {
        val change = MemoryRules.change(entry(), tier = "auto")
        assertEquals("auto", change.tier)
        assertEquals(2, change.expectedRevision)
    }
    @Test fun archiveAndRestoreCarryRevisionAndNeverSendDelete() {
        assertEquals("cold", MemoryRules.change(entry(), indexState = "cold").indexState)
        assertEquals("active", MemoryRules.change(entry().copy(indexState = "cold"), indexState = "active").indexState)
        rejects { MemoryRules.change(entry(), indexState = "deleted") }
    }
    @Test fun independentConfirmationRequiresServerEligibilityAndUserOriginal() {
        assertTrue(MemoryRules.change(entry(), confirmIndependent = true).confirmIndependent)
        rejects { MemoryRules.change(entry().copy(canConfirmIndependent = false), confirmIndependent = true) }
        rejects { MemoryRules.change(entry().copy(role = "assistant"), confirmIndependent = true) }
    }
    @Test fun emptyAndUnknownChangesAreRejected() {
        rejects { MemoryRules.change(entry()) }
        rejects { MemoryRules.change(entry(), tier = "short") }
    }
    @Test fun legacyMemoryDefaultsToUnrecognizedWithoutInventingSemanticSuccess() {
        val value = MemoryRules.entry(entry())
        assertEquals("none", value.recognitionMode)
        assertEquals("unavailable", value.recognitionStatus)
    }
    @Test fun semanticFailureAndPartialOutcomesPreserveOriginalAndManualTier() {
        listOf("ok", "partial", "failed", "unavailable", "skipped_large", "disabled").forEach { status ->
            val value = MemoryRules.entry(entry().copy(recognitionMode = "semantic", recognitionStatus = status, manualTier = "mid"))
            assertEquals(entry().text, value.text)
            assertEquals("mid", value.manualTier)
            assertEquals(status, value.recognitionStatus)
        }
        assertEquals("literal", MemoryRules.entry(entry().copy(recognitionMode = "literal", recognitionStatus = "literal")).recognitionMode)
    }
    @Test fun unknownRecognitionValuesAreRejectedInsteadOfGuessed() {
        rejects { MemoryRules.entry(entry().copy(recognitionMode = "engram")) }
        rejects { MemoryRules.entry(entry().copy(recognitionStatus = "success")) }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid memory metadata/change") } catch (_: IllegalArgumentException) { }
    }
}
