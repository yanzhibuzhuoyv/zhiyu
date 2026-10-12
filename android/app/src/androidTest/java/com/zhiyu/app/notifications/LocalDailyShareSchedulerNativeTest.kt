package com.zhiyu.app.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zhiyu.app.data.LocalDailyShareWorkStore
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.domain.LocalDailyShareSchedulePolicy
import com.zhiyu.app.domain.ProactiveRunRecord
import com.zhiyu.app.domain.ProactiveSchedulePolicy
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real WorkManager integration on the isolated emulator; never a model or cloud call. */
class LocalDailyShareSchedulerNativeTest {
    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val chat = UUID.randomUUID().toString().replace("-", "")
        val manager = WorkManager.getInstance(context)
        val optIn = ProactiveWorkStore(context)
        val shareState = LocalDailyShareWorkStore(context)
        val dueName = LocalDailyShareSchedulePolicy.uniqueDueName(chat)
        val photoName = "zhiyu_daily_share_picture_$chat"

        fun prepare() {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            ProactiveNotifications.createChannel(context)
            assertTrue(ProactiveNotifications.allowed(context))
        }
        fun rows(name: String) = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
        fun await(name: String, accept: (List<WorkInfo>) -> Boolean): List<WorkInfo> {
            val end = SystemClock.elapsedRealtime() + 5000
            do { rows(name).let { if (accept(it)) return it }; SystemClock.sleep(20) }
            while (SystemClock.elapsedRealtime() < end)
            throw AssertionError("WorkManager state did not settle for $name")
        }
        override fun close() {
            listOf(dueName, photoName, "zhiyu_daily_share_repair", ProactiveSchedulePolicy.uniqueDueName(chat), ProactiveScheduler.UNIQUE_NAME)
                .forEach { manager.cancelUniqueWork(it).result.get(5, TimeUnit.SECONDS) }
            listOf("proactive_delivery", "daily_share_delivery").forEach { name ->
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val edit = prefs.edit()
                prefs.all.keys.filter { it == "enabled_$chat" || it == "pending_$chat" || it.startsWith("run_${chat}_") }.forEach(edit::remove)
                check(edit.commit())
            }
        }
    }

    @Test fun deniedOptInDoesNotEnqueueDailyWorkOrPhotoArrivalJobs() {
        Fixture().use { test ->
            test.prepare()
            LocalDailyShareScheduler.schedule(test.context, test.chat)
            LocalDailyShareScheduler.next(test.context, test.chat)
            LocalDailyShareScheduler.pictureAvailable(test.context, test.chat)
            assertTrue(test.rows(test.dueName).isEmpty())
            assertTrue(test.rows(test.photoName).isEmpty())
        }
    }

    @Test fun contactOptInRepairsIndependentPhotoSharingWithoutRerollingOrDuplicateJobs() {
        Fixture().use { test ->
            test.prepare(); test.optIn.setEnabled(test.chat, true)
            val now = Instant.now()
            val due = now.plusSeconds(900)
            test.shareState.recordRun(test.chat, ProactiveRunRecord(now, "not_due", due))
            test.optIn.recordRun(test.chat, ProactiveRunRecord(now, "not_due", now.plusSeconds(1200)))
            ProactiveScheduler.schedule(test.context, test.chat)
            val first = test.await(test.dueName) { it.size == 1 }.single()
            assertTrue(first.tags.contains(LocalDailyShareScheduler.WORK_TAG))
            assertFalse(first.tags.contains(ProactiveScheduler.WORK_TAG))
            assertTrue(first.nextScheduleTimeMillis in due.toEpochMilli() - 2000..due.toEpochMilli() + 2000)
            LocalDailyShareScheduler.schedule(test.context, test.chat)
            assertEquals(first.id, test.await(test.dueName) { it.size == 1 }.single().id)
            assertEquals(due, test.shareState.lastRun(test.chat)?.nextDue)
            assertEquals(1, test.await("zhiyu_daily_share_repair") { it.size == 1 }.size)
            LocalDailyShareScheduler.pictureAvailable(test.context, test.chat)
            val picture = test.await(test.photoName) { it.size == 1 }.single()
            LocalDailyShareScheduler.pictureAvailable(test.context, test.chat)
            assertEquals(picture.id, test.await(test.photoName) { it.size == 1 }.single().id)
            assertEquals(first.id, test.rows(test.dueName).single().id)
            ProactiveScheduler.cancel(test.context)
            test.await(test.dueName) { it.all { row -> row.state == WorkInfo.State.CANCELLED } }
            test.await(test.photoName) { it.all { row -> row.state == WorkInfo.State.CANCELLED } }
            assertEquals(due, test.shareState.lastRun(test.chat)?.nextDue)
        }
    }
}
