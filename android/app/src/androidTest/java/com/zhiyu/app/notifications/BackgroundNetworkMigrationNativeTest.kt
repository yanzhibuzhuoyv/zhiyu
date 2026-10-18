package com.zhiyu.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.workDataOf
import com.zhiyu.app.data.LocalDailyShareWorkStore
import com.zhiyu.app.data.LocalSceneStockScheduler
import com.zhiyu.app.data.LocalSceneStockWorker
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.domain.LocalDailyRules
import com.zhiyu.app.domain.LocalDailyShareSchedulePolicy
import com.zhiyu.app.domain.ProactiveRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The same Android network validation that blocked proactive contact also gates three other
 * background chains. These tests assert the real WorkManager row, not a helper's return value.
 */
@SuppressLint("RestrictedApi")
class BackgroundNetworkMigrationNativeTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext
        val chat = UUID.randomUUID().toString().replace("-", "")
        val manager = WorkManager.getInstance(context)
        val optIn = ProactiveWorkStore(context)
        val version = (1..999).random()
        val day = LocalDailyRules.dayId(Instant.now())
        val dailyDueName = LocalDailyShareSchedulePolicy.uniqueDueName(chat)
        val dailyPhotoName = "zhiyu_daily_share_picture_$chat"
        val dailyPeriodicName = "zhiyu_daily_share_repair"
        val stockName = "zhiyu_stock_${chat}_$version"
        val memoryOnceName = "zhiyu_native_memory_recognition_once"
        val memoryPeriodicName = "zhiyu_native_memory_recognition_periodic"

        init {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            ProactiveNotifications.createChannel(context)
            check(ProactiveNotifications.allowed(context))
            cancelAll()
        }

        fun enableDailyShare() {
            optIn.setEnabled(chat, true)
            check(LocalDailyShareWorkStore(context).enabled(chat))
        }

        fun cancelAll() = listOf(dailyDueName, dailyPhotoName, dailyPeriodicName, stockName, memoryOnceName, memoryPeriodicName)
            .forEach { name ->
                manager.cancelUniqueWork(name).result.get(5, TimeUnit.SECONDS)
                awaitRows(name) { rows -> rows.all { it.state.isFinished } }
            }

        fun rows(name: String) = manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)

        fun awaitRows(name: String, accept: (List<WorkInfo>) -> Boolean): List<WorkInfo> {
            val end = SystemClock.elapsedRealtime() + 5_000
            do {
                rows(name).let { if (accept(it)) return it }
                SystemClock.sleep(20)
            } while (SystemClock.elapsedRealtime() < end)
            throw AssertionError("WorkManager state did not settle for $name")
        }

        fun spec(id: UUID) = checkNotNull(WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
            .getWorkSpec(id.toString())) { "Missing WorkSpec $id" }

        fun awaitSpec(id: UUID, accept: (androidx.work.impl.model.WorkSpec) -> Boolean) =
            awaitSpec(id, 5_000, accept)

        fun awaitSpec(id: UUID, timeoutMs: Long, accept: (androidx.work.impl.model.WorkSpec) -> Boolean): androidx.work.impl.model.WorkSpec {
            val end = SystemClock.elapsedRealtime() + timeoutMs
            do {
                spec(id).let { if (accept(it)) return it }
                SystemClock.sleep(20)
            } while (SystemClock.elapsedRealtime() < end)
            throw AssertionError("WorkSpec $id did not settle")
        }

        override fun close() {
            cancelAll()
            listOf("proactive_delivery", "daily_share_delivery").forEach { name ->
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val edit = prefs.edit()
                prefs.all.keys.filter { it == "enabled_$chat" || it == "pending_$chat" || it.startsWith("run_${chat}_") }
                    .forEach(edit::remove)
                check(edit.commit())
            }
        }
    }

    private fun assertInternetOnly(constraints: Constraints, label: String) {
        assertEquals("$label keeps no platform network type", NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
        if (Build.VERSION.SDK_INT >= 28) {
            val request = checkNotNull(constraints.requiredNetworkRequest) { "$label lacks a custom request" }
            assertTrue("$label requires INTERNET", request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            assertFalse("$label must not require platform validation", request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            assertFalse("$label must not require a trusted network", request.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED))
        }
    }

    private fun legacy(constraints: Constraints) = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .apply {
            if (constraints.requiresStorageNotLow()) setRequiresStorageNotLow(true)
            if (constraints.requiresBatteryNotLow()) setRequiresBatteryNotLow(true)
        }.build()

    @Test fun dailyShareLegacyDuePhotoAndPeriodicMigrateInPlace() {
        Fixture().use { test ->
            test.enableDailyShare()
            val dueInput = workDataOf(LocalDailyShareScheduler.CHAT_INPUT to ProactiveRules.conversationId(test.chat),
                LocalDailyShareScheduler.CHAIN_INPUT to true)
            val legacyDue = OneTimeWorkRequestBuilder<LocalDailyShareWorker>().setInputData(dueInput)
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setConstraints(legacy(Constraints.Builder().setRequiresStorageNotLow(true).build()))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(LocalDailyShareScheduler.WORK_TAG).build()
            test.manager.enqueueUniqueWork(test.dailyDueName, ExistingWorkPolicy.KEEP, legacyDue).result.get(5, TimeUnit.SECONDS)

            val photoInput = workDataOf(LocalDailyShareScheduler.CHAT_INPUT to ProactiveRules.conversationId(test.chat),
                "picture_ready" to true)
            val legacyPhoto = OneTimeWorkRequestBuilder<LocalDailyShareWorker>().setInputData(photoInput)
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setConstraints(legacy(Constraints.Builder().build()))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(LocalDailyShareScheduler.WORK_TAG).build()
            test.manager.enqueueUniqueWork(test.dailyPhotoName, ExistingWorkPolicy.KEEP, legacyPhoto).result.get(5, TimeUnit.SECONDS)

            val legacyPeriodic = PeriodicWorkRequestBuilder<LocalDailyShareWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(LocalDailyShareScheduler.CHAT_INPUT to test.chat))
                .setConstraints(legacy(Constraints.Builder().build()))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(LocalDailyShareScheduler.WORK_TAG).build()
            test.manager.enqueueUniquePeriodicWork(test.dailyPeriodicName, ExistingPeriodicWorkPolicy.KEEP, legacyPeriodic)
                .result.get(5, TimeUnit.SECONDS)

            val dueBefore = test.spec(legacyDue.id)
            val photoBefore = test.spec(legacyPhoto.id)
            val periodicBefore = test.spec(legacyPeriodic.id)

            LocalDailyShareScheduler.schedule(test.context, test.chat)

            val dueAfter = test.awaitSpec(legacyDue.id) { it.constraints.requiredNetworkType == NetworkType.NOT_REQUIRED }
            val photoAfter = test.awaitSpec(legacyPhoto.id) { it.constraints.requiredNetworkType == NetworkType.NOT_REQUIRED }
            val periodicAfter = test.awaitSpec(legacyPeriodic.id) { it.constraints.requiredNetworkType == NetworkType.NOT_REQUIRED }

            assertEquals(dueBefore.id, dueAfter.id)
            assertEquals(dueBefore.input, dueAfter.input)
            assertEquals(dueBefore.lastEnqueueTime, dueAfter.lastEnqueueTime)
            assertEquals(dueBefore.initialDelay, dueAfter.initialDelay)
            assertEquals(dueBefore.runAttemptCount, dueAfter.runAttemptCount)
            assertEquals(dueBefore.backoffPolicy, dueAfter.backoffPolicy)
            assertEquals(dueBefore.backoffDelayDuration, dueAfter.backoffDelayDuration)
            assertTrue(dueAfter.constraints.requiresStorageNotLow())
            assertInternetOnly(dueAfter.constraints, "daily share due")

            assertEquals(photoBefore.id, photoAfter.id)
            assertEquals(photoBefore.input, photoAfter.input)
            assertEquals(photoBefore.initialDelay, photoAfter.initialDelay)
            assertEquals(photoBefore.lastEnqueueTime, photoAfter.lastEnqueueTime)
            assertInternetOnly(photoAfter.constraints, "daily share photo")

            assertEquals(periodicBefore.id, periodicAfter.id)
            assertEquals(periodicBefore.input, periodicAfter.input)
            assertEquals(periodicBefore.intervalDuration, periodicAfter.intervalDuration)
            assertInternetOnly(periodicAfter.constraints, "daily share periodic")
        }
    }

    @Test fun sceneStockLegacyWaitingJobMigratesInPlace() {
        Fixture().use { test ->
            val input = workDataOf("chat" to test.chat, "day" to test.day, "version" to test.version)
            val legacy = OneTimeWorkRequestBuilder<LocalSceneStockWorker>().setInputData(input)
                // A future delay proves identity-preserving migration without letting the real
                // worker run and increment attempts during the assertion window.
                .setInitialDelay(1, TimeUnit.HOURS)
                .setConstraints(legacy(Constraints.Builder().setRequiresBatteryNotLow(true).build()))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            test.manager.enqueueUniqueWork(test.stockName, ExistingWorkPolicy.KEEP, legacy).result.get(5, TimeUnit.SECONDS)
            val before = test.spec(legacy.id)

            LocalSceneStockScheduler.enqueue(test.context, test.chat, test.version, test.day)

            val after = test.awaitSpec(legacy.id) { it.constraints.requiredNetworkType == NetworkType.NOT_REQUIRED }
            assertEquals(before.id, after.id)
            assertEquals(before.input, after.input)
            assertEquals(before.lastEnqueueTime, after.lastEnqueueTime)
            assertEquals(before.initialDelay, after.initialDelay)
            assertEquals(before.runAttemptCount, after.runAttemptCount)
            assertEquals(before.backoffPolicy, after.backoffPolicy)
            assertEquals(before.backoffDelayDuration, after.backoffDelayDuration)
            assertTrue(after.constraints.requiresBatteryNotLow())
            assertInternetOnly(after.constraints, "scene stock")
        }
    }

    @Test fun memoryRecognitionLegacyOnceAndPeriodicMigrateInPlace() {
        Fixture().use { test ->
            val legacyOnce = OneTimeWorkRequestBuilder<com.zhiyu.app.data.LocalMemoryRecognitionWorker>()
                .setInitialDelay(15, TimeUnit.SECONDS)
                .setConstraints(legacy(Constraints.Builder().setRequiresStorageNotLow(true).build()))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            test.manager.enqueueUniqueWork(test.memoryOnceName, ExistingWorkPolicy.KEEP, legacyOnce).result.get(5, TimeUnit.SECONDS)

            val legacyPeriodic = PeriodicWorkRequestBuilder<com.zhiyu.app.data.LocalMemoryRecognitionWorker>(15, TimeUnit.MINUTES)
                .setConstraints(legacy(Constraints.Builder().build()))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            test.manager.enqueueUniquePeriodicWork(test.memoryPeriodicName, ExistingPeriodicWorkPolicy.KEEP, legacyPeriodic)
                .result.get(5, TimeUnit.SECONDS)

            val onceBefore = test.spec(legacyOnce.id)
            val periodicBefore = test.spec(legacyPeriodic.id)

            com.zhiyu.app.data.LocalMemoryRecognitionScheduler.schedule(test.context)

            val onceAfter = test.awaitSpec(legacyOnce.id) { it.constraints.requiredNetworkType == NetworkType.NOT_REQUIRED }
            val periodicAfter = test.awaitSpec(legacyPeriodic.id) { it.constraints.requiredNetworkType == NetworkType.NOT_REQUIRED }

            assertEquals(onceBefore.id, onceAfter.id)
            assertEquals(onceBefore.input, onceAfter.input)
            assertEquals(onceBefore.lastEnqueueTime, onceAfter.lastEnqueueTime)
            assertEquals(onceBefore.initialDelay, onceAfter.initialDelay)
            assertEquals(onceBefore.runAttemptCount, onceAfter.runAttemptCount)
            assertTrue(onceAfter.constraints.requiresStorageNotLow())
            assertInternetOnly(onceAfter.constraints, "memory recognition once")

            assertEquals(periodicBefore.id, periodicAfter.id)
            assertEquals(periodicBefore.intervalDuration, periodicAfter.intervalDuration)
            assertInternetOnly(periodicAfter.constraints, "memory recognition periodic")
        }
    }

    @Test fun freshDailyShareJobsUseInternetOnlyAndKeepTheirIdentity() {
        Fixture().use { test ->
            test.enableDailyShare()
            LocalDailyShareScheduler.schedule(test.context, test.chat)
            val due = test.awaitRows(test.dailyDueName) { it.size == 1 }.single()
            assertInternetOnly(due.constraints, "fresh daily share due")
            assertEquals(30_000L, due.initialDelayMillis)
            LocalDailyShareScheduler.pictureAvailable(test.context, test.chat)
            val photo = test.awaitRows(test.dailyPhotoName) { it.size == 1 }.single()
            assertInternetOnly(photo.constraints, "fresh daily share photo")
            assertEquals(10_000L, photo.initialDelayMillis)
            val periodic = test.awaitRows(test.dailyPeriodicName) { it.size == 1 }.single()
            assertInternetOnly(periodic.constraints, "fresh daily share periodic")
            LocalDailyShareScheduler.schedule(test.context, test.chat)
            assertEquals(due.id, test.awaitRows(test.dailyDueName) { it.size == 1 }.single().id)
            assertEquals(photo.id, test.awaitRows(test.dailyPhotoName) { it.size == 1 }.single().id)
            assertEquals(periodic.id, test.awaitRows(test.dailyPeriodicName) { it.size == 1 }.single().id)
        }
    }

    @Test fun freshMemoryRecognitionAndSceneStockUseInternetOnly() {
        Fixture().use { test ->
            com.zhiyu.app.data.LocalMemoryRecognitionScheduler.schedule(test.context)
            val once = test.awaitRows(test.memoryOnceName) { it.size == 1 }.single()
            assertInternetOnly(once.constraints, "fresh memory recognition once")
            val periodic = test.awaitRows(test.memoryPeriodicName) { it.size == 1 }.single()
            assertInternetOnly(periodic.constraints, "fresh memory recognition periodic")

            LocalSceneStockScheduler.enqueue(test.context, test.chat, test.version, test.day)
            val stock = test.awaitRows(test.stockName) { it.size == 1 }.single()
            assertInternetOnly(stock.constraints, "fresh scene stock")
            assertNotNull(stock.id)
        }
    }
}
