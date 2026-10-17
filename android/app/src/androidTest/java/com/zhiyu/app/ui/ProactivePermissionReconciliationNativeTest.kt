package com.zhiyu.app.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Grant, user preference and a conversational pause must remain distinct across screens. */
class ProactivePermissionReconciliationNativeTest {
    private class Fixture : AutoCloseable {
        private val instrument = InstrumentationRegistry.getInstrumentation()
        private val isolated = CareTestContext(instrument.targetContext)
        var grant = true
        val context = object : ContextWrapper(isolated) {
            override fun getApplicationContext(): Context = this
            override fun checkSelfPermission(permission: String) = if (permission == Manifest.permission.POST_NOTIFICATIONS)
                if (grant) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            else super.checkSelfPermission(permission)
        }
        val chat = "synthetic_permission"
        private val credentials = object : CredentialStore {
            override fun read(): SavedConnection? = error("No Key")
            override fun readBinding(): CompanionBinding? = error("No binding")
            override fun write(connection: SavedConnection) = error("No mutation")
            override fun writeBinding(binding: CompanionBinding) = error("No mutation")
            override fun clear() = error("No mutation")
        }
        val service = LocalProactiveService(context, credentials, object : LocalProactiveGateway {
            override suspend fun conversations() = listOf(Conversation(chat, "合成", "synthetic", true,
                Instant.EPOCH.toString(), Instant.EPOCH.toString()))
            override suspend fun result(id: String, requestId: String): Message? = error("No check")
            override suspend fun history(id: String): List<Message> = error("No check")
            override suspend fun send(id: String, requestId: String, event: LocalNightEvent?): Message = error("No check")
        })
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fun controller() = ProactiveController(context, chat, scope, service)
        init { ProactiveNotificationsFixture.prepare(instrument.targetContext) }
        override fun close() { scope.cancel(); com.zhiyu.app.notifications.ProactiveScheduler.cancel(context); isolated.cleanup() }
    }
    private object ProactiveNotificationsFixture {
        fun prepare(context: Context) {
            val instrument = InstrumentationRegistry.getInstrumentation()
            if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                instrument.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            com.zhiyu.app.notifications.ProactiveNotifications.createChannel(context)
        }
    }

    @Test fun anExistingGrantMustNotSilentlyEnableAnExplicitlyDisabledContact() = runBlocking {
        Fixture().use { test ->
            test.service.savePreferences(test.chat, ProactivePreferences(enabled = false))
            val controller = test.controller()
            controller.restore().join()
            controller.reconcileNotificationPermission(granted = true).join()
            assertFalse(controller.preferences.enabled)
            assertFalse(test.service.preferences(test.chat).enabled)
        }
    }

    @Test fun theFirstExistingGrantWithNoSavedChoiceEnablesContact() = runBlocking {
        Fixture().use { test ->
            val controller = test.controller()
            controller.restore().join()
            controller.reconcileNotificationPermission(granted = true).join()
            assertTrue(controller.preferences.enabled)
            assertTrue(test.service.preferences(test.chat).enabled)
            assertTrue(ProactiveWorkStore(test.context).enabled(test.chat))
        }
    }

    @Test fun revocationPausesSchedulingWithoutDestroyingTheSavedOptIn() = runBlocking {
        Fixture().use { test ->
            test.service.savePreferences(test.chat, ProactivePreferences(enabled = true))
            test.grant = false
            val controller = test.controller()
            controller.restore().join()
            assertTrue("The saved choice must remain distinct from a denied Android permission", controller.preferences.enabled)
            controller.reconcileNotificationPermission(granted = false).join()
            assertTrue(test.service.preferences(test.chat).enabled)
            assertTrue(ProactiveWorkStore(test.context).enabled(test.chat))
        }
    }

    @Test fun aFreshSystemGrantMayEnableContactButMustNeverClearAConversationPause() = runBlocking {
        Fixture().use { test ->
            test.service.savePreferences(test.chat, ProactivePreferences(enabled = false))
            val pauses = ContactPauseStore(test.context)
            val expected = pauses.apply(test.chat, ContactPauseCommand.PAUSE, "synthetic_pause")
            val controller = test.controller()
            controller.restore().join()
            controller.reconcileNotificationPermission(granted = true, freshGrant = true).join()
            assertTrue(controller.preferences.enabled)
            assertEquals(expected, pauses.read(test.chat))
        }
    }
}
