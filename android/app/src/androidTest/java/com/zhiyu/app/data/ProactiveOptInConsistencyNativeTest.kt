package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** One grant must not be split between service preferences and the background worker. */
class ProactiveOptInConsistencyNativeTest {
    private class Fixture : AutoCloseable {
        val context = CareTestContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val chat = "synthetic_optin"
        val work = ProactiveWorkStore(context)
        val credentials = object : CredentialStore {
            override fun read(): SavedConnection? = error("No credential should be read")
            override fun readBinding(): CompanionBinding? = error("No binding should be read")
            override fun write(connection: SavedConnection) = error("No credential mutation")
            override fun writeBinding(binding: CompanionBinding) = error("No binding mutation")
            override fun clear() = error("No credential mutation")
        }
        val gateway = object : LocalProactiveGateway {
            override suspend fun conversations() = listOf(Conversation(chat, "合成", "synthetic", true,
                Instant.EPOCH.toString(), Instant.EPOCH.toString()))
            override suspend fun result(id: String, requestId: String): Message? = error("No check")
            override suspend fun history(id: String): List<Message> = error("No check")
            override suspend fun send(id: String, requestId: String, event: LocalNightEvent?): Message = error("No check")
        }
        fun service() = LocalProactiveService(context, credentials, gateway)
        fun legacy(enabled: Boolean) {
            val json = JSONObject().put("enabled", enabled).put("start", 22).put("end", 8).put("zone", "Asia/Shanghai")
            check(context.getSharedPreferences("local_proactive", 0).edit().putString(chat, json.toString()).commit())
        }
        override fun close() = context.cleanup()
    }

    @Test fun savingAServiceGrantImmediatelyEnablesTheBackgroundWorkerAfterRecreation() = runBlocking {
        Fixture().use { test ->
            test.service().savePreferences(test.chat, ProactivePreferences(enabled = true))
            assertTrue("The service grant and worker opt-in must be one durable write",
                ProactiveWorkStore(test.context).enabled(test.chat))
            assertTrue(test.service().preferences(test.chat).enabled)
        }
    }

    @Test fun disablingTheWorkerCannotLeaveTheServiceClaimingContactIsEnabled() = runBlocking {
        Fixture().use { test ->
            test.service().savePreferences(test.chat, ProactivePreferences(enabled = true))
            test.work.setEnabled(test.chat, false)
            assertFalse("An explicit disable must apply to the service too", test.service().preferences(test.chat).enabled)
            assertFalse(test.work.enabled(test.chat))
        }
    }

    @Test fun enablingTheWorkerCannotLeaveTheServicePermanentlyDisabled() = runBlocking {
        Fixture().use { test ->
            test.work.setEnabled(test.chat, true)
            assertTrue("A worker grant must be visible to the service", test.service().preferences(test.chat).enabled)
        }
    }

    @Test fun interruptedLegacyGrantWithOnlyServiceStateIsRecoveredWithoutNewProviderWork() = runBlocking {
        Fixture().use { test ->
            test.legacy(true)
            assertTrue("Recover the persisted grant left before the old second write", test.work.enabled(test.chat))
            assertTrue(test.service().preferences(test.chat).enabled)
            assertEquals("Asia/Shanghai", test.service().preferences(test.chat).timeZone)
        }
    }

    @Test fun legacyDisagreementNeverOverridesAnExplicitDisableOrContactPause() = runBlocking {
        Fixture().use { test ->
            test.legacy(true)
            check(test.context.getSharedPreferences("proactive_delivery", 0).edit()
                .putBoolean("enabled_${test.chat}", false).commit())
            val pause = ContactPauseStore(test.context)
            val expected = pause.apply(test.chat, ContactPauseCommand.PAUSE, "synthetic_stop")
            assertFalse(test.work.enabled(test.chat))
            assertFalse("Old conflicting states must converge safely", test.service().preferences(test.chat).enabled)
            assertEquals(expected, pause.read(test.chat))
        }
    }
}
