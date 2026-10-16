package com.zhiyu.app.notifications

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.zhiyu.app.data.LocalChatDatabase
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.data.SecureCredentialStore
import com.zhiyu.app.domain.CompanionBinding
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.ProactiveRunRecord
import com.zhiyu.app.domain.ProactiveSchedulePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Isolated binding and archive metadata only; no API Key and no provider request. */
class ContactRecoveryNativeTest {
    private class ScopedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "contact_recovery_${UUID.randomUUID()}_"
        private val preferences = mutableSetOf<String>()
        private val databases = mutableSetOf<String>()
        var notificationDenied = false
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int =
            if (notificationDenied && permission == Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED
            else super.checkSelfPermission(permission)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = prefix + name
            preferences.add(scoped)
            return baseContext.getSharedPreferences(scoped, mode)
        }
        override fun getDatabasePath(name: String) = baseContext.getDatabasePath(prefix + name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
            databases.add(prefix + name)
            return baseContext.openOrCreateDatabase(prefix + name, mode, factory)
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase {
            databases.add(prefix + name)
            return baseContext.openOrCreateDatabase(prefix + name, mode, factory, handler)
        }
        fun cleanup() {
            preferences.forEach(baseContext::deleteSharedPreferences)
            databases.forEach(baseContext::deleteDatabase)
        }
    }

    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ScopedContext(instrumentation.targetContext)
        val manager = WorkManager.getInstance(context)
        val state = ProactiveWorkStore(context)
        val now = Instant.now()
        val chat: String
        init {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            ProactiveNotifications.createChannel(context)
            val binding = CompanionBinding("synthetic", "synthetic-contact-model")
            SecureCredentialStore(context).writeBinding(binding)
            val owner = "companion-" + LocalMemoryCodec.sha((binding.providerId + "\u0000" + binding.model).toByteArray(Charsets.UTF_8))
            chat = LocalChatDatabase(context).use { it.ensureConversation(owner, binding.model, null).id }
        }
        fun rows(name: String) = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
        fun settle() {
            // Enqueue queries are serialized after the operations on WorkManager's task executor.
            rows(ProactiveScheduler.UNIQUE_NAME)
            rows(ProactiveSchedulePolicy.uniqueDueName(chat))
        }
        override fun close() {
            listOf(ProactiveScheduler.UNIQUE_NAME, "zhiyu_companion_resume", ProactiveSchedulePolicy.uniqueDueName(chat),
                "zhiyu_daily_share_repair", "zhiyu_daily_share_due_$chat").forEach {
                manager.cancelUniqueWork(it).result.get(5, TimeUnit.SECONDS)
            }
            // The old buggy scheduler may already have run a disabled worker under the real
            // application context. Remove only this test's synthetic run metadata there too.
            val basePreferences = instrumentation.targetContext.getSharedPreferences("proactive_delivery", Context.MODE_PRIVATE)
            val edit = basePreferences.edit()
            basePreferences.all.keys.filter { it.startsWith("run_${chat}_") || it == "pending_$chat" }.forEach(edit::remove)
            check(edit.commit())
            context.cleanup()
        }
    }

    @Test fun scheduleWithoutDurableOptInDoesNotLeavePeriodicContactWork() {
        Fixture().use { test ->
            ProactiveScheduler.schedule(test.context, test.chat)
            test.settle()
            assertTrue(test.rows(ProactiveScheduler.UNIQUE_NAME).none { !it.state.isFinished })
            assertTrue(test.rows(ProactiveSchedulePolicy.uniqueDueName(test.chat)).none { !it.state.isFinished })
        }
    }

    @Test fun deniedNotificationDoesNotLeaveADueChainEvenWhenOptedIn() {
        Fixture().use { test ->
            if (Build.VERSION.SDK_INT < 33) return
            test.state.setEnabled(test.chat, true)
            test.context.notificationDenied = true
            ProactiveScheduler.schedule(test.context, test.chat)
            ProactiveScheduler.next(test.context, test.chat, test.now)
            test.settle()
            assertTrue(test.rows(ProactiveSchedulePolicy.uniqueDueName(test.chat)).none { !it.state.isFinished })
            assertTrue(test.rows(ProactiveScheduler.UNIQUE_NAME).none { !it.state.isFinished })
        }
    }

    @Test fun backgroundRepairPreservesTheRealCheckOutcomeAndDue() = runBlocking {
        Fixture().use { test ->
            test.state.setEnabled(test.chat, true)
            val record = ProactiveRunRecord(test.now.minusSeconds(300), "needs_connection", test.now.plusSeconds(6 * 3600))
            test.state.recordRun(test.chat, record)
            ProactiveScheduler.recover(test.context, test.now)
            test.settle()
            assertEquals("Repair is not an actual contact check", record, test.state.lastRun(test.chat))
            val deadline = SystemClock.elapsedRealtime() + 5000
            var due = test.rows(ProactiveSchedulePolicy.uniqueDueName(test.chat)).lastOrNull { !it.state.isFinished }
            while (due == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(20)
                due = test.rows(ProactiveSchedulePolicy.uniqueDueName(test.chat)).lastOrNull { !it.state.isFinished }
            }
            assertNotNull(due)
            assertTrue(due!!.nextScheduleTimeMillis in record.nextDue!!.toEpochMilli() - 2000..record.nextDue.toEpochMilli() + 2000)
        }
    }

    @Test fun verifiedConnectionReleasesOnlyItsOldConnectionBackoffAndKeepsThePendingRequest() = runBlocking {
        Fixture().use { test ->
            test.state.setEnabled(test.chat, true)
            val request = test.state.requestId(test.chat)
            val record = ProactiveRunRecord(test.now.minusSeconds(300), "needs_connection", test.now.plusSeconds(6 * 3600))
            test.state.recordRun(test.chat, record)
            ProactiveScheduler.schedule(test.context, test.chat)
            test.settle()
            ProactiveScheduler.onConnectionValidated(test.context).join()
            test.settle()
            val updated = test.state.lastRun(test.chat)!!
            assertEquals(record.checkedAt, updated.checkedAt)
            assertEquals(record.outcome, updated.outcome)
            assertTrue("A verified connection must release the obsolete six-hour deferral", updated.nextDue!! <= Instant.now())
            assertEquals("Connection repair must not invent another provider attempt", request, test.state.requestId(test.chat))
            val due = test.rows(ProactiveSchedulePolicy.uniqueDueName(test.chat)).lastOrNull { !it.state.isFinished }
            assertNotNull(due)
            assertTrue("The previous pending due chain must also be replaced", due!!.nextScheduleTimeMillis <= Instant.now().plusSeconds(35).toEpochMilli())
        }
    }

    @Test fun verifiedConnectionDoesNotReleaseAnActualPersonalitySpacing() = runBlocking {
        Fixture().use { test ->
            test.state.setEnabled(test.chat, true)
            val record = ProactiveRunRecord(test.now.minusSeconds(300), "not_due", test.now.plusSeconds(4 * 3600))
            test.state.recordRun(test.chat, record)
            ProactiveScheduler.onConnectionValidated(test.context).join()
            assertEquals("Successful login must not erase a real contact interval", record, test.state.lastRun(test.chat))
        }
    }
}
