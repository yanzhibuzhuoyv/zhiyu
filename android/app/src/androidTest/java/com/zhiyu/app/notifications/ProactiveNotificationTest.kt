package com.zhiyu.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real platform notification delivery, with synthetic messages and UUID-scoped test preferences only. */
class ProactiveNotificationTest {
    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val suffix = UUID.randomUUID().toString()
        val chat = "notification_test_${suffix.replace("-", "") }"
        val message = Message("notification-test-$suffix", "assistant", "这是合成通知测试消息🍎，今天过得怎么样？", emptyList(), "2026-10-08T10:00:00Z")
        val preferenceName = "notification_test_$suffix"
        val state = ProactiveWorkStore(context, preferenceName)
        private val originalForeground = ProactiveForeground.isVisible()

        fun prepare(background: Boolean) {
            ProactiveNotifications.createChannel(context)
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            assertTrue("Test device must permit the companion notification channel", ProactiveNotifications.allowed(context))
            state.setEnabled(chat, true)
            ProactiveForeground.setVisible(!background)
        }

        fun active(): List<StatusBarNotification> = manager.activeNotifications.filter { it.tag == message.id && it.id == 1 }

        fun awaitNotification(): StatusBarNotification {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                active().firstOrNull()?.let { return it }
                SystemClock.sleep(25)
            }
            throw AssertionError("Synthetic notification was not posted")
        }

        override fun close() {
            manager.cancel(message.id, 1)
            context.deleteSharedPreferences(preferenceName)
            ProactiveForeground.setVisible(originalForeground)
            // Do not revoke runtime permission here: Android may kill this instrumentation process.
            // Keep it for the separate emulator demo; never change the user's opt-in preferences.
        }
    }

    private class SyntheticService(private val message: Message) : ProactiveService {
        var checks = 0
        override suspend fun preferences(id: String) = ProactivePreferences(enabled = true)
        override suspend fun savePreferences(id: String, preferences: ProactivePreferences) = preferences
        override suspend fun check(id: String, requestId: String): ProactiveCheck {
            checks++
            return ProactiveCheck(true, message, null, "sent")
        }
    }

    @Test fun backgroundDeliveryUsesOnePrivatePlatformNotificationAndAnActivityEntry() = runTest {
        Fixture().use { test ->
            test.prepare(background = true)
            val service = SyntheticService(test.message)
            var posts = 0
            fun engine(state: ProactiveWorkStore) = ProactiveDelivery(service, state,
                { ProactiveNotifications.allowed(test.context) }, ProactiveForeground::isVisible) {
                posts++
                ProactiveNotifications.post(test.context, test.chat, it, "合成恋人")
            }
            assertEquals(ProactiveWorkOutcome.COMPLETE, engine(test.state).run(test.chat))
            val posted = test.awaitNotification().notification
            assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
            assertEquals("合成恋人", posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals(ProactiveRules.notificationPreview(test.message.content), posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals("你收到一条新消息", posted.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals(test.context.packageName, posted.contentIntent.creatorPackage)
            if (Build.VERSION.SDK_INT >= 31) assertTrue(posted.contentIntent.isActivity)
            assertTrue(posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(ProactiveWorkOutcome.COMPLETE, engine(ProactiveWorkStore(test.context, test.preferenceName)).run(test.chat))
            assertEquals(1, posts)
            assertEquals(1, test.active().size)
            // Direct platform replacement with the same message ID also remains one notification.
            ProactiveNotifications.post(test.context, test.chat, test.message, "合成恋人")
            assertEquals(1, test.active().size)
        }
    }

    @Test fun foregroundRejectsDirectPostingAndDoesNotGenerateAContact() = runTest {
        Fixture().use { test ->
            test.prepare(background = false)
            ProactiveNotifications.post(test.context, test.chat, test.message, "合成恋人")
            val service = SyntheticService(test.message)
            val outcome = ProactiveDelivery(service, test.state, { ProactiveNotifications.allowed(test.context) }, ProactiveForeground::isVisible) {
                error("Foreground notification must not be posted")
            }.run(test.chat)
            assertEquals(ProactiveWorkOutcome.COMPLETE, outcome)
            assertEquals(0, service.checks)
            assertTrue(test.active().isEmpty())
        }
    }
}
