package com.zhiyu.app.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProactiveDeliveryTest {
    private class State : ProactiveWorkState {
        var enabled = true
        var pending: String? = null
        val notified = mutableSetOf<String>()
        val records = mutableListOf<ProactiveRunRecord>()
        override fun enabled(conversationId: String) = enabled
        override fun requestId(conversationId: String): String = pending ?: "stable-request".also { pending = it }
        override fun complete(conversationId: String, requestId: String) { if (pending == requestId) pending = null }
        override fun claimMessage(messageId: String) = notified.add(messageId)
        override fun recordRun(conversationId: String, record: ProactiveRunRecord) { records.add(record) }
    }

    private class Service : ProactiveService {
        val requests = mutableListOf<String>()
        var result = ProactiveCheck(true, Message("m1", "assistant", "今天过得怎么样？", emptyList(), "now"), null, null)
        var failure: Exception? = null
        var onCall: () -> Unit = {}
        override suspend fun preferences(id: String) = ProactivePreferences()
        override suspend fun savePreferences(id: String, preferences: ProactivePreferences) = preferences
        override suspend fun check(id: String, requestId: String): ProactiveCheck {
            requests.add(requestId)
            onCall()
            failure?.let { throw it }
            return result
        }
    }

    @Test fun foregroundAndDeniedPermissionNeverGenerateAMessage() = runTest {
        val service = Service()
        val state = State()
        val engine = ProactiveDelivery(service, state, { true }, { true }) { error("Unexpected notification") }
        assertEquals(ProactiveWorkOutcome.COMPLETE, engine.run("chat"))
        val denied = ProactiveDelivery(service, state, { false }, { false }) { error("Unexpected notification") }
        assertEquals(ProactiveWorkOutcome.COMPLETE, denied.run("chat"))
        assertTrue(service.requests.isEmpty())
        assertNull(state.pending)
    }

    @Test fun disabledContactNeverCallsTheBackend() = runTest {
        val service = Service()
        val state = State().apply { enabled = false }
        assertEquals(ProactiveWorkOutcome.COMPLETE, ProactiveDelivery(service, state, { true }, { false }) {}.run("chat"))
        assertTrue(service.requests.isEmpty())
    }

    @Test fun processingAndNetworkRetryReuseTheSameRequestAndNotifyOnce() = runTest {
        val service = Service()
        val state = State()
        val notifications = mutableListOf<String>()
        val engine = ProactiveDelivery(service, state, { true }, { false }) { notifications.add(it.id) }
        service.result = ProactiveCheck(false, null, null, "processing")
        assertEquals(ProactiveWorkOutcome.RETRY, engine.run("chat"))
        service.failure = ApiFailure(0, "Connection interrupted")
        assertEquals(ProactiveWorkOutcome.RETRY, engine.run("chat"))
        service.failure = null
        service.result = ProactiveCheck(true, Message("m1", "assistant", "我想你了。", emptyList(), "now"), null, null)
        assertEquals(ProactiveWorkOutcome.COMPLETE, engine.run("chat"))
        assertEquals(listOf("stable-request", "stable-request", "stable-request"), service.requests)
        assertEquals(listOf("m1"), notifications)
        assertNull(state.pending)
        engine.run("chat")
        assertEquals(listOf("m1"), notifications)
    }

    @Test fun returningToForegroundDuringGenerationSuppressesTheNotification() = runTest {
        var foreground = false
        val service = Service().apply { onCall = { foreground = true } }
        val state = State()
        assertEquals(ProactiveWorkOutcome.COMPLETE, ProactiveDelivery(service, state, { true }, { foreground }) { error("Unexpected notification") }.run("chat"))
        assertNull(state.pending)
    }

    @Test fun terminalMissingCredentialsAreNotRetried() = runTest {
        val service = Service().apply { failure = ApiFailure(401, "Reconnect") }
        val state = State()
        assertEquals(ProactiveWorkOutcome.STOP, ProactiveDelivery(service, state, { true }, { false }) {}.run("chat"))
        assertNull(state.pending)
    }

    @Test fun aMessageIsClaimedBeforePostingSoARetryCannotAlertTwice() = runTest {
        val service = Service()
        val state = State()
        val engine = ProactiveDelivery(service, state, { true }, { false }) { throw SecurityException("Permission revoked") }
        assertEquals(ProactiveWorkOutcome.COMPLETE, engine.run("chat"))
        assertEquals(setOf("m1"), state.notified)
        assertNull(state.pending)
    }

    @Test fun socketFailureKeepsThePendingRequestForTheNextBackgroundAttempt() = runTest {
        val service = Service().apply { failure = IOException("Synthetic socket reset") }
        val state = State()
        val engine = ProactiveDelivery(service, state, { true }, { false }) {}
        assertEquals(ProactiveWorkOutcome.RETRY, engine.run("chat"))
        assertEquals("stable-request", state.pending)
        service.failure = null
        assertEquals(ProactiveWorkOutcome.COMPLETE, engine.run("chat"))
        assertEquals(listOf("stable-request", "stable-request"), service.requests)
    }

    @Test fun aFuturePolicyDueIsRecordedWithoutProviderTextOrKey() = runTest {
        val state = State()
        val service = Service().apply { result = ProactiveCheck(false, null, "2026-10-13T00:00:00Z", "spacing") }
        assertEquals(ProactiveWorkOutcome.COMPLETE, ProactiveDelivery(service, state, { true }, { false }) {}.run("chat"))
        assertEquals("not_due", state.records.single().outcome)
        assertEquals(java.time.Instant.parse("2026-10-13T00:00:00Z"), state.records.single().nextDue)
        assertNull(state.pending)
    }

    @Test fun notificationPreviewBoundsCodePointsWithoutBreakingEmoji() {
        val content = "🍎".repeat(160)
        val preview = ProactiveRules.notificationPreview(content)
        assertEquals(121, preview.codePointCount(0, preview.length))
        assertTrue(preview.endsWith("…"))
        assertEquals("今天 想你了", ProactiveRules.notificationPreview(" 今天\n想你了 "))
    }
}
