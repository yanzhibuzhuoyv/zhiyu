package com.zhiyu.app.data

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.ContactPauseCommand
import com.zhiyu.app.domain.ContactPauseIntent
import com.zhiyu.app.domain.ContactPauseState
import com.zhiyu.app.domain.ContactDeliveryBlockedException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactPauseStoreNativeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun stores(test: (ContactPauseStore, () -> ContactPauseStore, String) -> Unit) {
        val name = "contact_pause_test_${UUID.randomUUID()}"
        try { test(ContactPauseStore(context, name), { ContactPauseStore(context, name) }, name) }
        finally { context.deleteSharedPreferences(name) }
    }
    @Test fun pauseAndExplicitResumePersistAcrossStoreRecreation() = stores { store, recreate, _ ->
        assertEquals(ContactPauseState(), store.read("chat"))
        val paused = store.apply("chat", ContactPauseCommand.PAUSE, "user-pause")
        assertTrue(paused.paused); assertEquals(1, paused.revision); assertEquals("user-pause", paused.sourceMessageId)
        assertEquals(paused, recreate().read("chat"))
        val resumed = recreate().apply("chat", ContactPauseCommand.RESUME, "user-resume")
        assertFalse(resumed.paused); assertEquals(2, resumed.revision)
        assertEquals(resumed, recreate().read("chat"))
    }
    @Test fun separateChatsAndDuplicateCommandRequestsDoNotChangeEachOther() = stores { store, recreate, _ ->
        val paused = store.apply("chat-a", ContactPauseCommand.PAUSE, "pause-a")
        assertEquals(ContactPauseState(), recreate().read("chat-b"))
        assertEquals(paused, recreate().apply("chat-a", ContactPauseCommand.PAUSE, "pause-a"))
        assertThrows(IllegalArgumentException::class.java) { store.apply("chat-a", ContactPauseCommand.RESUME, "pause-a") }
        assertEquals(paused, store.read("chat-a"))
    }
    @Test fun ordinaryUserMessagesAndRoleplayStopsNeverAutomaticallyClearPause() = stores { store, recreate, _ ->
        val paused = store.apply("chat", ContactPauseCommand.PAUSE, "user-pause")
        listOf("你好", "继续聊天", "停止这次角色扮演", "我们和好了").forEach { text ->
            ContactPauseIntent.parse(text)?.let { store.apply("chat", it, "ordinary-user") }
        }
        assertEquals(paused, recreate().read("chat"))
    }
    @Test fun invalidScopesAndMalformedStoredFlagsNeverImplicitlyResumeContact() = stores { store, recreate, name ->
        val paused = store.apply("chat", ContactPauseCommand.PAUSE, "user-pause")
        assertThrows(IllegalArgumentException::class.java) { store.read("../chat") }
        assertThrows(IllegalArgumentException::class.java) { store.apply("chat", ContactPauseCommand.RESUME, "\"bad-source\"") }
        assertEquals(paused, recreate().read("chat"))
        check(context.getSharedPreferences(name, 0).edit().putString("chat", "{\"schema\":1,\"paused\":\"false\",\"sourceMessageId\":\"user-pause\",\"revision\":1}").commit())
        assertThrows(IllegalStateException::class.java) { recreate().read("chat") }
    }
    @Test fun contactPauseNeverChangesNotificationPermissionOrOtherOptInPreferences() = stores { store, _, name ->
        val independent = "contact_pause_other_${UUID.randomUUID()}"
        val notificationPermission = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        try {
            val other = context.getSharedPreferences(independent, 0)
            check(other.edit().putBoolean("enabled", false).putString("personality", "warm_v2").commit())
            val snapshot = other.all.toMap()
            store.apply("chat", ContactPauseCommand.PAUSE, "user-pause")
            store.apply("chat", ContactPauseCommand.RESUME, "user-resume")
            assertEquals(snapshot, other.all)
            assertEquals(notificationPermission, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            assertTrue(context.getSharedPreferences(name, 0).all.keys.all { it == "chat" })
        } finally { context.deleteSharedPreferences(independent) }
    }
    @Test fun deliveryNeverRunsWhilePausedOrAfterTheSnapshotWasSuperseded() = stores { store, _, _ ->
        val initial = store.read("chat")
        var published = 0
        store.withDeliveryAllowed("chat", initial.revision) { published++ }
        val paused = store.apply("chat", ContactPauseCommand.PAUSE, "user-pause")
        assertThrows(ContactDeliveryBlockedException::class.java) { store.withDeliveryAllowed("chat", paused.revision) { published++ } }
        val resumed = store.apply("chat", ContactPauseCommand.RESUME, "user-resume")
        assertThrows(ContactDeliveryBlockedException::class.java) { store.withDeliveryAllowed("chat", initial.revision) { published++ } }
        store.withDeliveryAllowed("chat", resumed.revision) { published++ }
        assertEquals(2, published)
    }
    @Test fun pauseAndLocalPublicationUseOneLockAcrossStoreInstances() = stores { store, recreate, _ ->
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val pauseStarted = CountDownLatch(1)
        val published = AtomicInteger()
        try {
            val revision = store.read("chat").revision
            val delivery = executor.submit { store.withDeliveryAllowed("chat", revision) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); published.incrementAndGet()
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val pause = executor.submit { pauseStarted.countDown(); recreate().apply("chat", ContactPauseCommand.PAUSE, "user-pause") }
            assertTrue(pauseStarted.await(5, TimeUnit.SECONDS)); assertFalse(pause.isDone)
            release.countDown(); delivery.get(5, TimeUnit.SECONDS); pause.get(5, TimeUnit.SECONDS)
            assertEquals(1, published.get()); assertTrue(recreate().read("chat").paused)
            assertThrows(ContactDeliveryBlockedException::class.java) { store.withDeliveryAllowed("chat", revision) { published.incrementAndGet() } }
        } finally { release.countDown(); executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun pauseThenResumeKeepsTheLastChangeTimeForBlockingOlderPendingNotifications() = stores { _, _, name ->
        var millis = 1000L
        val timed = ContactPauseStore(context, name) { millis }
        assertEquals(0L, timed.read("chat").changedAtMillis)
        val pause = timed.apply("chat", ContactPauseCommand.PAUSE, "user-pause")
        assertEquals(1000L, pause.changedAtMillis)
        millis = 2000L
        val resume = timed.apply("chat", ContactPauseCommand.RESUME, "user-resume")
        assertEquals(2000L, resume.changedAtMillis)
        assertFalse(resume.paused)
        assertEquals(resume, ContactPauseStore(context, name).read("chat"))
        assertTrue(1500L < resume.changedAtMillis) // Root's worker rejects this pending message after the explicit resume.
        assertFalse(2500L < resume.changedAtMillis)
    }
}
