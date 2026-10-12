package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** UUID-scoped preferences plus synthetic metadata; no Key, provider, main archive, or scheduler execution. */
class LocalProactiveServiceTest {
    private class BindingStore(style: String) : CredentialStore {
        val binding = CompanionBinding("deepseek", "synthetic-model", CompanionProfile(style = style))
        var reads = 0
        override fun read(): SavedConnection? = error("This test must not read a Key")
        override fun readBinding(): CompanionBinding { reads++; return binding }
        override fun write(connection: SavedConnection) = error("Unexpected credential mutation")
        override fun clear() = error("Unexpected credential mutation")
        override fun writeBinding(binding: CompanionBinding) = error("Preset personality must remain fixed")
    }
    private class Gateway(private val clock: () -> Instant) : LocalProactiveGateway {
        val rows = mutableListOf<Message>()
        val completed = mutableMapOf<String, Message>()
        val events = mutableListOf<LocalNightEvent?>()
        val requestIds = mutableListOf<String>()
        var historyCalls = 0
        override suspend fun conversations() = listOf(Conversation("synthetic_oc", "合成", "synthetic-model", true, clock().toString(), clock().toString()))
        override suspend fun result(id: String, requestId: String) = completed[requestId]
        override suspend fun history(id: String): List<Message> { historyCalls++; return rows.toList() }
        override suspend fun send(id: String, requestId: String, event: LocalNightEvent?): Message {
            completed[requestId]?.let { return it }
            requestIds.add(requestId); events.add(event)
            val message = Message("synthetic-${requestIds.size}", "assistant", "合成 OC 原话", emptyList(), clock().toString(), origin = "proactive")
            rows.add(message); completed[requestId] = message
            return message
        }
    }
    private class Fixture(style: String = "anxious") : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val preferenceName = "local_proactive_test_${UUID.randomUUID()}"
        var clock = Instant.parse("2026-10-12T06:00:00Z")
        val store = BindingStore(style)
        val gateway = Gateway { clock }
        fun service() = LocalProactiveService(context, store, gateway, preferenceName) { clock }
        suspend fun enable() { service().savePreferences("synthetic_oc", ProactivePreferences(enabled = true)) }
        fun request() = UUID.randomUUID().toString()
        override fun close() { context.deleteSharedPreferences(preferenceName) }
    }
    private fun night(style: String = "anxious"): LocalNightEvent {
        val zone = ZoneId.of("Asia/Shanghai")
        return (0..364).firstNotNullOf { day -> LocalNightEventPolicy.plan("synthetic_oc", style,
            LocalDate.of(2026, 1, 1).plusDays(day.toLong()).atTime(23, 0).atZone(zone).toInstant()) }
    }

    @Test fun disabledPreferencesNeverReadABindingOrGenerate() = runBlocking {
        Fixture().use { test ->
            val result = test.service().check("synthetic_oc", test.request())
            assertFalse(result.emitted)
            assertEquals("disabled", result.reason)
            assertEquals(0, test.store.reads)
            assertEquals(0, test.gateway.historyCalls)
            assertTrue(test.gateway.requestIds.isEmpty())
        }
    }

    @Test fun recentConversationReturnsItsActualFutureDueWithoutGeneration() = runBlocking {
        Fixture().use { test ->
            test.enable()
            val user = Message("synthetic_user", "user", "", emptyList(), test.clock.minusSeconds(60).toString())
            test.gateway.rows.add(user)
            val result = test.service().check("synthetic_oc", test.request())
            assertFalse(result.emitted)
            assertEquals("recent_chat", result.reason)
            assertEquals(test.clock.plusSeconds(44 * 60).toString(), result.nextDueAt)
            assertTrue(test.gateway.requestIds.isEmpty())
        }
    }

    @Test fun wakeEventIsAnAssistantOriginalAndCannotRepeatAfterServiceRecreation() = runBlocking {
        Fixture().use { test ->
            val event = night()
            test.clock = event.due.plusSeconds(1)
            test.enable()
            val originalBinding = test.store.binding
            val first = test.service().check("synthetic_oc", test.request())
            assertTrue(first.emitted)
            assertEquals(listOf(event.requestId), test.gateway.requestIds)
            assertEquals(listOf(event), test.gateway.events)
            assertEquals(listOf("assistant"), test.gateway.rows.map { it.role })
            assertEquals("proactive", first.message!!.origin)
            assertNotNull(first.nextDueAt)
            val second = test.service().check("synthetic_oc", test.request())
            assertFalse(second.emitted)
            assertEquals(1, test.gateway.requestIds.size)
            assertEquals(originalBinding, test.store.binding)
        }
    }

    @Test fun retryAfterCommittedNormalMessageReusesItAndDoesNotCallGenerateAgain() = runBlocking {
        Fixture().use { test ->
            test.enable()
            val id = test.request()
            val first = test.service().check("synthetic_oc", id)
            val second = test.service().check("synthetic_oc", id)
            assertEquals(first.message, second.message)
            assertEquals(1, test.gateway.requestIds.size)
            assertEquals(listOf<LocalNightEvent?>(null), test.gateway.events)
            assertNotNull(second.nextDueAt)
        }
    }

    @Test fun committedWakeSurvivesAPendingRequestRetryAfterTheNightExpires() = runBlocking {
        Fixture().use { test ->
            val event = night()
            test.clock = event.due.plusSeconds(1)
            test.enable()
            val pending = test.request()
            val first = test.service().check("synthetic_oc", pending)
            test.clock = event.expires.plusSeconds(3 * 3600)
            val retry = test.service().check("synthetic_oc", pending)
            assertTrue(retry.emitted)
            assertEquals(first.message, retry.message)
            assertEquals(listOf(event.requestId), test.gateway.requestIds)
            assertEquals(1, test.gateway.rows.size)
        }
    }

    @Test fun deferredNightCheckAfterSevenNeverGeneratesAnExpiredWakeEvent() = runBlocking {
        Fixture().use { test ->
            test.clock = night().expires.plusSeconds(1)
            test.enable()
            assertTrue(test.service().check("synthetic_oc", test.request()).emitted)
            assertEquals(listOf<LocalNightEvent?>(null), test.gateway.events)
        }
    }

    @Test fun nightEventsNeverBypassDistantUnansweredLimit() = runBlocking {
        Fixture("distant").use { test ->
            test.clock = night("distant").due.plusSeconds(1)
            test.enable()
            test.gateway.rows.add(Message("user", "user", "", emptyList(), test.clock.minusSeconds(86400).toString()))
            test.gateway.rows.add(Message("contact", "assistant", "", emptyList(), test.clock.minusSeconds(3600).toString(), origin = "proactive"))
            val result = test.service().check("synthetic_oc", test.request())
            assertFalse(result.emitted)
            assertEquals("awaiting_reply", result.reason)
            assertTrue(test.gateway.requestIds.isEmpty())
        }
    }

    @Test fun aDailyShareDoesNotPretendTheUserSpokeOrResetOrdinaryContactSpacing() = runBlocking {
        Fixture().use { test ->
            test.enable()
            test.gateway.rows.add(Message("daily-share", "assistant", "合成分享", emptyList(),
                test.clock.minusSeconds(60).toString(), origin = "daily_share"))
            val result = test.service().check("synthetic_oc", test.request())
            assertTrue(result.emitted)
            assertEquals(listOf<LocalNightEvent?>(null), test.gateway.events)
        }
    }
}
