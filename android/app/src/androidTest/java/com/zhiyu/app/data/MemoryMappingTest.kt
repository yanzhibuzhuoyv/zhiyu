package com.zhiyu.app.data

import com.zhiyu.app.domain.MemoryChange
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Runs against Android's actual JSON implementation rather than mocked SDK stubs. */
class MemoryMappingTest {
    private fun entry() = JSONObject().put("message_id", "m-1").put("role", "user")
        .put("text", "苹果🍎\u0000e\u0301").put("created_at", "2026-10-06T09:00:00Z").put("char_length", 20)
        .put("tier", "mid").put("index_state", "active").put("revision", 4)
        .put("pinned", false).put("reason", "default").put("manual_tier", JSONObject.NULL)
        .put("can_confirm_independent", true).put("annotations", JSONArray())

    @Test fun unicodeOriginalAndAuthorArePreservedExactly() {
        val json = entry()
        val value = JSONObject(json.toString()).memoryEntry()
        assertEquals(json.getString("text"), value.text)
        assertEquals("user", value.role)
        assertEquals(4, value.revision)
        assertNull(value.manualTier)
        assertTrue(value.canConfirmIndependent)
    }
    @Test fun manualTierAndColdStateAreNotMistakenForOriginalDeletion() {
        val value = entry().put("manual_tier", "long").put("tier", "long").put("index_state", "cold").memoryEntry()
        assertEquals("long", value.manualTier)
        assertEquals("cold", value.indexState)
        assertEquals("苹果🍎\u0000e\u0301", value.text)
    }
    @Test fun numericStringsFractionsOverflowAndBooleansAreRejected() {
        listOf<Any>("4", 4.9, Long.MAX_VALUE, true).forEach { value ->
            rejects { entry().put("revision", value).memoryEntry() }
        }
    }
    @Test fun eligibilityAndPinnedFlagsMustBeJsonBooleans() {
        rejects { entry().put("can_confirm_independent", "true").memoryEntry() }
        rejects { entry().put("pinned", 1).memoryEntry() }
    }
    @Test fun missingEligibilityDefaultsToDisabled() {
        val json = entry(); json.remove("can_confirm_independent")
        assertFalse(json.memoryEntry().canConfirmIndependent)
    }
    @Test fun boundedPageUsesServerCursorAndAutomaticMode() {
        val page = JSONObject().put("messages", JSONArray().put(entry())).put("has_more", true)
            .put("next_before", "m-1").put("automatic_annotations", false).memoryPage(null)
        assertEquals("m-1", page.nextBefore)
        assertTrue(page.hasMore)
        assertFalse(page.automaticAnnotations)
        rejects { JSONObject().put("messages", JSONArray().put(entry())).put("has_more", true)
            .put("next_before", JSONObject.NULL).put("automatic_annotations", false).memoryPage(null) }
    }
    @Test fun partialMutationCarriesExactRevisionWithoutRawTextOrDelete() {
        val json = MemoryChange(7, tier = "auto").json()
        assertEquals(setOf("expected_revision", "tier"), json.keys().asSequence().toSet())
        assertEquals(7, json.getInt("expected_revision"))
        assertEquals("auto", json.getString("tier"))
        assertFalse(json.has("content"))
    }
    @Test fun independentConfirmationDoesNotChangeTierOrOriginal() {
        val json = MemoryChange(8, confirmIndependent = true).json()
        assertEquals(setOf("expected_revision", "confirm_independent"), json.keys().asSequence().toSet())
        assertTrue(json.getBoolean("confirm_independent"))
    }
    @Test fun missingRecognitionFieldsAreCompatibleWithLegacyBackend() {
        val value = entry().memoryEntry()
        assertEquals("none", value.recognitionMode)
        assertEquals("unavailable", value.recognitionStatus)
    }
    @Test fun semanticFailureIsExplicitAndKeepsUnicodeOriginal() {
        val value = entry().put("recognition_mode", "semantic").put("recognition_status", "failed").memoryEntry()
        assertEquals("semantic", value.recognitionMode)
        assertEquals("failed", value.recognitionStatus)
        assertEquals("苹果🍎\u0000e\u0301", value.text)
    }
    @Test fun ruleRecognitionCannotBeMistakenForSemanticRecognition() {
        val value = entry().put("recognition_mode", "literal").put("recognition_status", "literal").memoryEntry()
        assertEquals("literal", value.recognitionMode)
        assertEquals("literal", value.recognitionStatus)
    }
    @Test fun recognitionFieldsRejectUnknownEnums() {
        rejects { entry().put("recognition_mode", "unknown").memoryEntry() }
        rejects { entry().put("recognition_status", "success").memoryEntry() }
    }
    @Test fun recognitionFieldsRejectNullAndNonStringsInsteadOfUsingLegacyFallback() {
        listOf<Any>(JSONObject.NULL, true, 1).forEach { value ->
            rejects { entry().put("recognition_mode", value).memoryEntry() }
            rejects { entry().put("recognition_status", value).memoryEntry() }
        }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid JSON metadata") } catch (_: IllegalArgumentException) { }
    }
}
