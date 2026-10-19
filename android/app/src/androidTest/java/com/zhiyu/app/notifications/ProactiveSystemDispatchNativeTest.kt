package com.zhiyu.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import android.net.NetworkCapabilities
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.close
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No TestDriver/startWork/clock jump: Android's actual JobService dispatches the delayed Worker. */
@SuppressLint("RestrictedApi")
@OptIn(androidx.work.ExperimentalConfigurationApi::class)
class ProactiveSystemDispatchNativeTest {
    private class ScopedContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val prefix = "contact_dispatch_${UUID.randomUUID().toString().replace("-", "")}_"
        private val root = File(base.noBackupFilesDir, prefix).apply { check(mkdirs()) }
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(File(root, "databases").apply { mkdirs() }, name)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(prefix + name, mode).also { names.add(prefix + name) }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, handler)
        override fun close() {
            names.forEach(baseContext::deleteSharedPreferences)
            check(root.canonicalFile.parentFile == baseContext.noBackupFilesDir.canonicalFile && root.name == prefix)
            check(root.deleteRecursively())
        }
    }

    private class Fixture(private val holdDelivery: Boolean = false) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ScopedContext(instrumentation.targetContext)
        private val originalManager = WorkManagerImpl.getInstance(instrumentation.targetContext)
        private val executor = Executors.newFixedThreadPool(2)
        private val taskExecutor = Executors.newFixedThreadPool(2)
        private val originalVisible = ProactiveForeground.isVisible()
        val archive = LocalChatDatabase(context, if (holdDelivery) "zhiyu_local_v1.sqlite3" else "dispatch_archive.sqlite3")
        val owner = "companion-" + LocalMemoryCodec.sha("deepseek\u0000deepseek-flash".toByteArray(Charsets.UTF_8))
        val binding = CompanionBinding("deepseek", "deepseek-flash", CompanionProfile(style = "anxious", name = "合成恋人"))
        val credentials = object : CredentialStore {
            override fun read() = SavedConnection(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-dispatch-key", binding.model)
            override fun readBinding() = binding
            override fun write(connection: SavedConnection) = error("No credential changes")
            override fun writeBinding(binding: CompanionBinding) = error("No binding changes")
            override fun clear() = error("No credential changes")
        }
        val chat = archive.ensureConversation(owner, binding.model, binding.profile)
        val providerCalls = AtomicInteger(0)
        val workerCreations = AtomicInteger(0)
        val deliveryStarted = CompletableDeferred<Unit>()
        val releaseDelivery = CompletableDeferred<Unit>()
        private val repository = LocalChatRepository(context, credentials,
            DirectProviderClient(DirectProviderTransport { _, key, json ->
                assertEquals("synthetic-dispatch-key", key)
                assertTrue(JSONObject(json).getJSONArray("messages").length() > 1)
                providerCalls.incrementAndGet()
                JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                    .put("message", JSONObject().put("role", "assistant")
                        .put("content", "我刚泡了杯茶，忽然想跟你说句话。")))).toString()
            }), database = archive)
        val service = LocalProactiveService(context, credentials, RepositoryProactiveGateway(repository))
        val state = ProactiveWorkStore(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val manager: WorkManagerImpl
        init {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            ProactiveNotifications.createChannel(context)
            check(ProactiveNotifications.allowed(context))
            if (holdDelivery) SecureCredentialStore(context).writeBinding(binding)
            val user = archive.append(owner, chat.id, "user", "今天有点累，准备去歇一会儿。")
            archive.writableDatabase.execSQL("UPDATE messages SET created_at=? WHERE id=?",
                arrayOf(Instant.now().minusSeconds(3600).toString(), user.id))
            val factory = object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                    if (workerClassName != ProactiveWorker::class.java.name) return null
                    workerCreations.incrementAndGet()
                    if (holdDelivery) return ProactiveWorker(context, workerParameters) { _, _ ->
                        deliveryStarted.complete(Unit)
                        releaseDelivery.await()
                        ProactiveWorkOutcome.COMPLETE
                    }
                    return ProactiveWorker(context, workerParameters) { id, chain ->
                        val recreatedService = LocalProactiveService(context, credentials, RepositoryProactiveGateway(repository))
                        ProactiveWorkRunner(context, credentials, recreatedService).run(id, chain)
                    }
                }
            }
            val config = Configuration.Builder().setExecutor(executor).setTaskExecutor(taskExecutor)
                .setWorkerFactory(factory).setGreedySchedulerEnabled(false)
                .setJobSchedulerJobIdRange(19000, 20000).build()
            manager = WorkManagerImpl(context, config)
            WorkManagerImpl.setDelegate(manager)
            ProactiveForeground.setVisible(false)
        }
        fun notes() = notifications.activeNotifications.filter { note ->
            archive.history(owner, chat.id).messages.any { it.origin == "proactive" && it.id == note.tag }
        }
        override fun close() {
            releaseDelivery.complete(Unit)
            try {
                manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
                awaitProcessorIdle()
                archive.history(owner, chat.id).messages.filter { it.origin == "proactive" }.forEach { notifications.cancel(it.id, 1) }
                awaitSerialIdle()
                manager.close()
                awaitSerialIdle()
            } finally {
                WorkManagerImpl.setDelegate(originalManager)
                // Shut down gracefully only after WorkManager's serial queue has drained. An abrupt
                // shutdownNow() races SerialExecutorImpl.scheduleNext and rejects on a worker thread.
                executor.shutdown(); taskExecutor.shutdown()
                check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "WorkManager executor did not stop" }
                check(taskExecutor.awaitTermination(5, TimeUnit.SECONDS)) { "WorkManager task executor did not stop" }
                archive.close(); context.close()
                ProactiveForeground.setVisible(originalVisible)
            }
        }

        private fun awaitProcessorIdle() {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (manager.processor.hasWork() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            check(!manager.processor.hasWork()) { "WorkManager still has running work during teardown" }
        }

        /** A FIFO barrier proves every task queued before it ran; the quiet window catches late posts. */
        private fun awaitSerialIdle() {
            val serial = manager.workTaskExecutor.serialTaskExecutor
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var quietSince = 0L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (serial.hasPendingTasks()) { quietSince = 0; SystemClock.sleep(20); continue }
                val barrier = CountDownLatch(1)
                serial.execute { barrier.countDown() }
                check(barrier.await(2, TimeUnit.SECONDS)) { "WorkManager serial executor did not reach its drain barrier" }
                if (serial.hasPendingTasks()) { quietSince = 0; continue }
                if (quietSince == 0L) quietSince = SystemClock.elapsedRealtime()
                if (SystemClock.elapsedRealtime() - quietSince >= 200) return
                SystemClock.sleep(20)
            }
            throw AssertionError("WorkManager serial executor stayed busy during teardown")
        }
    }

    @Test fun aVerifiedConnectionDoesNotCancelAnActuallyRunningContactWorkerOrItsRequest() = runBlocking {
        Fixture(holdDelivery = true).use { test ->
            test.state.setEnabled(test.chat.id, true)
            val pending = test.state.requestId(test.chat.id)
            test.state.recordRun(test.chat.id, ProactiveRunRecord(Instant.now().minusSeconds(60),
                "needs_connection", Instant.now().plusSeconds(6 * 3600)))
            val work = OneTimeWorkRequestBuilder<ProactiveWorker>()
                .setInputData(workDataOf(ProactiveScheduler.CONVERSATION_INPUT to test.chat.id,
                    ProactiveScheduler.CHAIN_INPUT to true))
                .setInitialDelay(1, TimeUnit.SECONDS).addTag(ProactiveScheduler.WORK_TAG).build()
            test.manager.enqueueUniqueWork(ProactiveSchedulePolicy.uniqueDueName(test.chat.id),
                ExistingWorkPolicy.KEEP, work).result.get(5, TimeUnit.SECONDS)
            // A real JobScheduler dispatch is not a one-second timer. Use the same bounded
            // platform-start budget as the other OS tests; validation is invoked only after RUNNING.
            try { withTimeout(90_000) { test.deliveryStarted.await() } }
            catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Before connection validation: platform did not start held delivery; creations=" +
                    test.workerCreations.get() + "; state=" + test.manager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS)?.state, failure)
            }
            assertEquals("Android's real dispatcher must first enter the production Worker", WorkInfo.State.RUNNING,
                test.manager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS)?.state)

            ProactiveScheduler.onConnectionValidated(test.context).join()

            assertEquals("Connection repair must preserve an in-flight attempt under the same work identity",
                WorkInfo.State.RUNNING, test.manager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS)?.state)
            assertEquals(pending, test.state.requestId(test.chat.id))
            assertEquals(0, test.providerCalls.get())
        }
    }

    @Test fun realSystemJobDispatchReachesWorkerGenerationArchiveAndNotificationAfterItsActualDelay() = runBlocking {
        Fixture().use { test ->
            test.service.savePreferences(test.chat.id, ProactivePreferences(enabled = true))
            // Both setters were necessary in the former implementation. Retain the explicit
            // worker grant here so dispatch is tested independently of the migration tests.
            test.state.setEnabled(test.chat.id, true)
            val started = SystemClock.elapsedRealtime()
            ProactiveScheduler.next(test.context, test.chat.id)
            val row = test.manager.getWorkInfosForUniqueWork(ProactiveSchedulePolicy.uniqueDueName(test.chat.id))
                .get(5, TimeUnit.SECONDS).single()
            assertEquals(WorkInfo.State.ENQUEUED, row.state)
            assertEquals(30_000L, row.initialDelayMillis)
            assertTrue(test.manager.schedulers.single().javaClass.name.endsWith(".SystemJobScheduler"))
            val jobDeadline = SystemClock.elapsedRealtime() + 5000
            val platformScheduler = test.context.getSystemService(JobScheduler::class.java)
            // AndroidX 2.12 JobSchedulerExt.wmJobScheduler uses this namespace on API 34+.
            val workScheduler = if (Build.VERSION.SDK_INT >= 34)
                platformScheduler.forNamespace("androidx.work.systemjobscheduler") else platformScheduler
            var registered = false
            while (!registered && SystemClock.elapsedRealtime() < jobDeadline) {
                registered = workScheduler.allPendingJobs.any {
                    it.extras.getString("EXTRA_WORK_SPEC_ID") == row.id.toString()
                }
                if (!registered) delay(25)
            }
            assertTrue("A real Android JobScheduler entry must exist", registered)
            assertEquals(0, test.workerCreations.get())
            assertEquals(0, test.providerCalls.get())
            val deadline = started + 90_000
            while (test.notes().isEmpty() && SystemClock.elapsedRealtime() < deadline) delay(100)
            assertTrue("No manual driver: the actual 30-second delay must elapse", SystemClock.elapsedRealtime() - started >= 29_000)
            assertEquals("SystemJobService must instantiate the actual production Worker", 1, test.workerCreations.get())
            assertEquals("Only the remote HTTP transport is synthetic", 1, test.providerCalls.get())
            val message = test.archive.history(test.owner, test.chat.id).messages.single { it.origin == "proactive" }
            assertEquals("我刚泡了杯茶，忽然想跟你说句话。", message.content)
            assertEquals(message.content, test.notes().single().notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals("sent", test.state.lastRun(test.chat.id)?.outcome)
            val finishDeadline = SystemClock.elapsedRealtime() + 5000
            while (test.manager.getWorkInfoById(row.id).get(5, TimeUnit.SECONDS)?.state != WorkInfo.State.SUCCEEDED &&
                SystemClock.elapsedRealtime() < finishDeadline) delay(25)
            assertEquals(WorkInfo.State.SUCCEEDED, test.manager.getWorkInfoById(row.id).get(5, TimeUnit.SECONDS)?.state)
            assertTrue("The next personality check remains scheduled", test.manager
                .getWorkInfosForUniqueWork(ProactiveSchedulePolicy.uniqueDueName(test.chat.id))
                .get(5, TimeUnit.SECONDS).any { it.id != row.id && !it.state.isFinished })
            val record = test.state.lastRun(test.chat.id)
            // A second real system-dispatched Worker models a periodic repair arriving
            // after the due Worker; it must reread the durable due instead of generating twice.
            val repair = OneTimeWorkRequestBuilder<ProactiveWorker>()
                .setInputData(workDataOf(ProactiveScheduler.CONVERSATION_INPUT to test.chat.id)).build()
            test.manager.enqueue(repair).result.get(5, TimeUnit.SECONDS)
            val repairDeadline = SystemClock.elapsedRealtime() + 30_000
            while (test.manager.getWorkInfoById(repair.id).get(5, TimeUnit.SECONDS)?.state != WorkInfo.State.SUCCEEDED &&
                SystemClock.elapsedRealtime() < repairDeadline) delay(100)
            assertEquals(WorkInfo.State.SUCCEEDED, test.manager.getWorkInfoById(repair.id).get(5, TimeUnit.SECONDS)?.state)
            assertEquals(2, test.workerCreations.get())
            assertEquals(record, ProactiveWorkStore(test.context).lastRun(test.chat.id))
            assertEquals(1, test.providerCalls.get())
            assertEquals(1, test.notes().size)
            assertEquals(1, test.archive.history(test.owner, test.chat.id).messages.count { it.origin == "proactive" })
        }
    }

    @Test fun anOldConnectedDueIsUpdatedInPlaceAndActuallyRunsWithoutPlatformValidation() = runBlocking {
        Fixture().use { test ->
            test.service.savePreferences(test.chat.id, ProactivePreferences(enabled = true))
            test.state.setEnabled(test.chat.id, true)
            val record = ProactiveRunRecord(Instant.now().minusSeconds(60), "not_due", Instant.now().minusSeconds(1))
            test.state.recordRun(test.chat.id, record)
            val pending = test.state.requestId(test.chat.id)
            val legacy = OneTimeWorkRequestBuilder<ProactiveWorker>()
                .setInputData(workDataOf(ProactiveScheduler.CONVERSATION_INPUT to test.chat.id, ProactiveScheduler.CHAIN_INPUT to true))
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                .addTag(ProactiveScheduler.WORK_TAG).build()
            test.manager.enqueueUniqueWork(ProactiveSchedulePolicy.uniqueDueName(test.chat.id), ExistingWorkPolicy.KEEP, legacy)
                .result.get(5, TimeUnit.SECONDS)
            val before = test.manager.workDatabase.workSpecDao().getWorkSpec(legacy.id.toString())!!.copy()
            ProactiveScheduler.repairNetworkConstraints(test.context, test.chat.id).join()
            val after = test.manager.workDatabase.workSpecDao().getWorkSpec(legacy.id.toString())!!
            assertEquals(before.id, after.id)
            assertEquals(before.input, after.input)
            assertEquals(before.lastEnqueueTime, after.lastEnqueueTime)
            assertEquals(before.initialDelay, after.initialDelay)
            assertEquals(before.runAttemptCount, after.runAttemptCount)
            assertEquals(before.backoffPolicy, after.backoffPolicy)
            assertEquals(before.backoffDelayDuration, after.backoffDelayDuration)
            assertEquals(before.constraints.requiresStorageNotLow(), after.constraints.requiresStorageNotLow())
            assertEquals(NetworkType.NOT_REQUIRED, after.constraints.requiredNetworkType)
            if (Build.VERSION.SDK_INT >= 28) {
                assertTrue(after.constraints.requiredNetworkRequest!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                assertFalse(after.constraints.requiredNetworkRequest!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
            assertEquals(record, test.state.lastRun(test.chat.id))
            assertEquals(pending, test.state.requestId(test.chat.id))
            val deadline = SystemClock.elapsedRealtime() + 90_000
            while (test.notes().isEmpty() && SystemClock.elapsedRealtime() < deadline) delay(100)
            assertEquals(1, test.workerCreations.get())
            assertEquals(1, test.providerCalls.get())
            assertEquals(1, test.notes().size)
            assertEquals(1, test.archive.history(test.owner, test.chat.id).messages.count { it.origin == "proactive" })
        }
    }
}
