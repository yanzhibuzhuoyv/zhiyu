package com.zhiyu.app.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.CareTestContext
import com.zhiyu.app.data.ContactPauseStore
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Network absence cannot invoke a provider, allocate a request or supersede permission/quiet gates. */
class ProactiveOfflineGateNativeTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = CareTestContext(instrumentation.targetContext)
        val chat = "synthetic_offline"
        val state = ProactiveWorkStore(context)
        private val priorForeground = ProactiveForeground.isVisible()
        var networkReads = 0
        val credentials = object : CredentialStore {
            override fun read(): SavedConnection? = error("Offline checks must not read a Key")
            override fun readBinding(): CompanionBinding? = error("Offline checks must not read a binding")
            override fun write(connection: SavedConnection) = error("No mutation")
            override fun writeBinding(binding: CompanionBinding) = error("No mutation")
            override fun clear() = error("No mutation")
        }
        val service = object : ProactiveService {
            override suspend fun preferences(id: String): ProactivePreferences = error("No service check")
            override suspend fun savePreferences(id: String, preferences: ProactivePreferences): ProactivePreferences = error("No settings change")
            override suspend fun check(id: String, requestId: String): ProactiveCheck = error("Offline checks must not generate")
        }
        init {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            ProactiveNotifications.createChannel(context)
            state.setEnabled(chat, true)
            ProactiveForeground.setVisible(false)
        }
        fun runner() = ProactiveWorkRunner(context, credentials, service, networkAvailable = { networkReads++; false })
        fun pending() = context.getSharedPreferences("proactive_delivery", 0).getString("pending_$chat", null)
        override fun close() {
            ProactiveScheduler.cancel(context); context.cleanup(); ProactiveForeground.setVisible(priorForeground)
        }
    }

    @Test fun offlineReturnsRetryWithoutARequestIdentityServiceCallOrCredentialRead() = runBlocking {
        Fixture().use { test ->
            assertNull(test.pending())
            assertEquals(ProactiveWorkOutcome.RETRY, test.runner().run(test.chat))
            assertEquals(1, test.networkReads)
            assertEquals("retry", test.state.lastRun(test.chat)?.outcome)
            assertNull(test.pending())
        }
    }

    @Test fun aFuturePersonalityDueIsPreservedWithoutEvenCheckingTheNetwork() = runBlocking {
        Fixture().use { test ->
            val record = ProactiveRunRecord(Instant.now(), "not_due", Instant.now().plusSeconds(4 * 3600))
            test.state.recordRun(test.chat, record)
            assertEquals(ProactiveWorkOutcome.COMPLETE, test.runner().run(test.chat))
            assertEquals(record, test.state.lastRun(test.chat))
            assertEquals(0, test.networkReads)
            assertNull(test.pending())
        }
    }

    @Test fun conversationalPauseTakesPriorityOverAnOfflineRetry() = runBlocking {
        Fixture().use { test ->
            ContactPauseStore(test.context).apply(test.chat, ContactPauseCommand.PAUSE, "synthetic_stop")
            assertEquals(ProactiveWorkOutcome.COMPLETE, test.runner().run(test.chat))
            assertEquals("permission", test.state.lastRun(test.chat)?.outcome)
            assertEquals(0, test.networkReads)
            assertNull(test.pending())
        }
    }

    @Test fun foregroundAndDisabledContactNeverBecomeOfflineProviderRetries() = runBlocking {
        Fixture().use { test ->
            ProactiveForeground.setVisible(true)
            assertEquals(ProactiveWorkOutcome.COMPLETE, test.runner().run(test.chat))
            assertEquals("foreground", test.state.lastRun(test.chat)?.outcome)
            ProactiveForeground.setVisible(false); test.state.setEnabled(test.chat, false)
            assertEquals(ProactiveWorkOutcome.COMPLETE, test.runner().run(test.chat))
            assertEquals("disabled", test.state.lastRun(test.chat)?.outcome)
            assertEquals(0, test.networkReads)
            assertNull(test.pending())
        }
    }
}
