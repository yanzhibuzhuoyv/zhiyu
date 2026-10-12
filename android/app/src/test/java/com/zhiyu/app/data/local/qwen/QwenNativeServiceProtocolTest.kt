package com.zhiyu.app.data.local.qwen

import org.junit.Assert.*
import org.junit.Test

class QwenNativeServiceProtocolTest {
    @Test fun stageRoutesAndBudgetsAreClosedAndCannotAcceptArbitraryCommands() {
        assertEquals(":qwen_text", QwenNativeServiceProtocol.suffix("text"))
        listOf("sample", "decode", "inspect").forEach { assertEquals(":qwen_image", QwenNativeServiceProtocol.suffix(it)) }
        assertEquals(3_900_000L, QwenNativeServiceProtocol.budgetMs("sample"))
        listOf("shell", "sample; rm", "", "TEXT").forEach { rejected { QwenNativeServiceProtocol.requireStage(it) } }
    }
    @Test fun statesRequireCompletedInputStagesAndInspectionNeedsASavedCheckpoint() {
        QwenNativeServiceProtocol.requireState("text", "prepared")
        QwenNativeServiceProtocol.requireState("sample", "encoded")
        QwenNativeServiceProtocol.requireState("decode", "sampled")
        QwenNativeServiceProtocol.requireState("inspect", "approved")
        QwenNativeServiceProtocol.requireState("inspect", "encoded") // Persisted sampler output may precede state commit.
        listOf("prepared", "decoded", "stale").forEach { rejected { QwenNativeServiceProtocol.requireState("sample", it) } }
        rejected { QwenNativeServiceProtocol.requireState("inspect", "prepared") }
    }
    @Test fun requestAndJobIdentitiesAreCanonicalAndBounded() {
        val id="73d8e81d-0ee6-4468-b99e-e31419501ca4"
        assertEquals(id, QwenNativeServiceProtocol.request(id))
        assertEquals("a".repeat(64), QwenNativeServiceProtocol.jobId("a".repeat(64)))
        listOf("1-1-1-1-1", id.uppercase(), "", id+"a").forEach { rejected { QwenNativeServiceProtocol.request(it) } }
        listOf("../private", "A".repeat(64), "a".repeat(63)).forEach { rejected { QwenNativeServiceProtocol.jobId(it) } }
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("External protocol input was accepted") } catch (_: IllegalArgumentException) { }
    }
}
