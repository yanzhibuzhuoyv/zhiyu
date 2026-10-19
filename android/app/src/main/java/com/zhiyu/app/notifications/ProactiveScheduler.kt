package com.zhiyu.app.notifications

import android.content.Context
import androidx.work.*
import com.zhiyu.app.domain.ProactiveRules
import com.zhiyu.app.domain.ProactiveSchedulePolicy
import com.zhiyu.app.data.ProactiveWorkStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import java.time.Instant
import java.util.concurrent.TimeUnit
import com.zhiyu.app.data.LocalProactivePreferencesStore
import com.zhiyu.app.data.LocalProactiveService
import com.zhiyu.app.data.latestProactiveActivity
import com.zhiyu.app.data.appCredentialStore
import com.zhiyu.app.domain.ProactivePreferences
import com.zhiyu.app.domain.ProactiveService

object ProactiveScheduler {
    const val UNIQUE_NAME = "zhiyu_companion_contact"
    const val CONVERSATION_INPUT = "conversation_id"
    const val WORK_TAG = "zhiyu_companion_delivery"
    const val CHAIN_INPUT = "continue_contact_chain"
    private const val RESUME_NAME = "zhiyu_companion_resume"
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryLock = Any()
    private val recoveryMutex = Mutex()
    private var recoveryJob: Job? = null

    fun schedule(context: Context, conversationId: String) {
        scheduleRequests(context, conversationId)
        repairNetworkConstraints(context, conversationId)
    }

    /** An explicit settings repair is new user consent, unlike automatic lifecycle recovery. */
    fun restoreContact(context: Context): Deferred<ProactivePreferences> = restoreContact(context, null)

    internal fun restoreContact(context: Context, service: ProactiveService?): Deferred<ProactivePreferences> {
        val app = context.applicationContext ?: context
        return recoveryScope.async {
            check(ProactiveNotifications.allowed(app)) { "请先在系统设置中允许知语通知" }
            val id = boundContactChat(app) ?: error("请先返回原来的恋人聊天")
            val preferences = LocalProactivePreferencesStore(app).read(id).copy(enabled = true)
            ContactPreferenceWriter.submit(app, id, preferences,
                service ?: LocalProactiveService(app, appCredentialStore(app))).await()
        }
    }

    /** A new false-to-true grant requests one fresh policy check, without cancelling paid work. */
    internal fun onContactGranted(context: Context, conversationId: String) {
        val app = context.applicationContext ?: context
        val state = ProactiveWorkStore(app)
        state.requestFreshCheck(conversationId, Instant.now())
        migrateLegacyNetworkRequests(app, conversationId)
        val operations = scheduleRequests(app, conversationId) + listOfNotNull(resume(app, conversationId))
        operations.forEach { it.result.get(5, TimeUnit.SECONDS) }
    }

    internal fun repairNetworkConstraints(context: Context, conversationId: String): Job = recoveryScope.launch {
        try { migrateLegacyNetworkRequests(context.applicationContext ?: context, conversationId) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.w("ZhiYuContact", "Legacy contact network repair deferred; durable intent is retained")
        }
    }

    /** Own fixed input contracts only; updateWork preserves identity, attempts and enqueue time. */
    private fun migrateLegacyNetworkRequests(context: Context, conversationId: String) {
        val id = ProactiveRules.conversationId(conversationId)
        val manager = WorkManager.getInstance(context)
        val names = listOf(ProactiveSchedulePolicy.uniqueDueName(id) to true, RESUME_NAME to false)
        names.forEach { (name, chain) ->
            manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).filter { row ->
                !row.state.isFinished && row.workerClassName == ProactiveWorker::class.java.name &&
                    ProactiveNetwork.isLegacy(row.constraints)
            }.forEach { row ->
                val data = if (chain) workDataOf(CONVERSATION_INPUT to id, CHAIN_INPUT to true)
                    else workDataOf(CONVERSATION_INPUT to id)
                val request = OneTimeWorkRequestBuilder<ProactiveWorker>().setId(row.id)
                    .setInputData(data).setInitialDelay(row.initialDelayMillis, TimeUnit.MILLISECONDS)
                    .setConstraints(ProactiveNetwork.constraints(row.constraints))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .apply { row.tags.forEach(::addTag) }.build()
                // A RUNNING worker is updated for its next run, never cancelled mid-request.
                manager.updateWork(request).get(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun scheduleRequests(context: Context, conversationId: String): List<Operation> {
        val id = ProactiveRules.conversationId(conversationId)
        ProactiveNotifications.createChannel(context)
        if (!ProactiveWorkStore(context).enabled(id) || !ProactiveNotifications.allowed(context)) return emptyList()
        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(15, TimeUnit.MINUTES)
            .setConstraints(ProactiveNetwork.constraints())
            .setInputData(workDataOf(CONVERSATION_INPUT to ProactiveRules.conversationId(conversationId)))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        val periodic = WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        val due = enqueueNext(context, id)
        LocalDailyShareScheduler.schedule(context, id)
        return listOfNotNull(periodic, due)
    }

    fun next(context: Context, conversationId: String, now: Instant = Instant.now(), continueChain: Boolean = false) {
        enqueueNext(context, conversationId, now, continueChain)
    }

    private fun enqueueNext(context: Context, conversationId: String, now: Instant = Instant.now(),
        continueChain: Boolean = false): Operation? {
        val state = ProactiveWorkStore(context)
        if (!state.enabled(conversationId) || !ProactiveNotifications.allowed(context)) return null
        val due = ProactiveSchedulePolicy.nextCheck(now, state.lastRun(conversationId))
        val request = OneTimeWorkRequestBuilder<ProactiveWorker>()
            .setConstraints(ProactiveNetwork.constraints())
            .setInputData(workDataOf(CONVERSATION_INPUT to ProactiveRules.conversationId(conversationId), CHAIN_INPUT to true))
            .setInitialDelay(ProactiveSchedulePolicy.delayMillis(now, due), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_TAG).build()
        val policy = when {
            continueChain -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.KEEP
        }
        return WorkManager.getInstance(context).enqueueUniqueWork(ProactiveSchedulePolicy.uniqueDueName(conversationId), policy, request)
    }

    private fun resume(context: Context, conversationId: String): Operation? {
        if (!ProactiveWorkStore(context).enabled(conversationId) || !ProactiveNotifications.allowed(context)) return null
        val request = OneTimeWorkRequestBuilder<ProactiveWorker>()
            .setConstraints(ProactiveNetwork.constraints())
            .setInputData(workDataOf(CONVERSATION_INPUT to ProactiveRules.conversationId(conversationId)))
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_TAG).build()
        return WorkManager.getInstance(context).enqueueUniqueWork(RESUME_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** A short process-owned enqueue repair survives Activity destruction; it is not a keep-alive. */
    fun requestRecovery(context: Context): Job = synchronized(recoveryLock) {
        recoveryJob?.takeIf { it.isActive } ?: recoveryScope.launch(start = CoroutineStart.LAZY) {
            recover(context.applicationContext ?: context)
        }.also { recoveryJob = it; it.start() }
    }

    /** Call only after a provider has accepted login/revalidation, never on ordinary restore. */
    fun onConnectionValidated(context: Context): Job {
        val app = context.applicationContext ?: context
        val verifiedAt = Instant.now()
        return recoveryScope.launch { recoveryMutex.withLock {
            try {
                ProactiveNotifications.createChannel(app)
                if (!ProactiveNotifications.allowed(app)) return@withLock
                val chat = boundContactChat(app) ?: return@withLock
                migrateLegacyNetworkRequests(app, chat)
                val now = Instant.now()
                if (!ProactiveWorkStore(app).releaseConnectionBackoff(chat, verifiedAt)) return@withLock
                val operations = scheduleRequests(app, chat)
                operations.forEach { it.result.get(5, TimeUnit.SECONDS) }
                updatePendingDue(app, chat, now)
                resume(app, chat)?.result?.get(5, TimeUnit.SECONDS)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.w("ZhiYuContact", "Validated connection scheduling repair deferred; stored preferences are retained")
            }
        } }
    }

    /** updateWork is safe even if an ENQUEUED row starts after this read; REPLACE would cancel it. */
    private fun updatePendingDue(context: Context, conversationId: String, now: Instant) {
        val manager = WorkManager.getInstance(context)
        val due = ProactiveSchedulePolicy.nextCheck(now, ProactiveWorkStore(context).lastRun(conversationId))
        manager.getWorkInfosForUniqueWork(ProactiveSchedulePolicy.uniqueDueName(conversationId))
            .get(5, TimeUnit.SECONDS).filter { row ->
                row.workerClassName == ProactiveWorker::class.java.name && row.state == WorkInfo.State.ENQUEUED &&
                    row.runAttemptCount == 0 && row.nextScheduleTimeMillis != Long.MAX_VALUE
            }.forEach { row ->
                // WorkManager retains the original enqueue time when updating an existing request.
                val enqueuedAt = row.nextScheduleTimeMillis - row.initialDelayMillis
                val delay = (due.toEpochMilli() - enqueuedAt).coerceAtLeast(0)
                val request = OneTimeWorkRequestBuilder<ProactiveWorker>().setId(row.id)
                    .setInputData(workDataOf(CONVERSATION_INPUT to conversationId, CHAIN_INPUT to true))
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setConstraints(ProactiveNetwork.constraints(row.constraints))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .apply { row.tags.forEach(::addTag) }.build()
                manager.updateWork(request).get(5, TimeUnit.SECONDS)
            }
    }

    /** Called after durable ordinary chat activity; only a real provider response validates its connection. */
    fun onConversationActivity(context: Context, conversationId: String, userMessageId: String,
        userCreatedAt: String, connectionValidated: Boolean = false, verifiedAt: Instant? = null): Job {
        val app = context.applicationContext ?: context
        val observedAt = Instant.now()
        return recoveryScope.launch { recoveryMutex.withLock {
            try {
                if (boundContactChat(app) != conversationId) return@withLock
                val state = ProactiveWorkStore(app)
                val activityChanged = state.recordConversationActivity(conversationId, userMessageId,
                    Instant.parse(userCreatedAt), observedAt)
                val connectionChanged = connectionValidated && verifiedAt != null &&
                    !verifiedAt.isAfter(observedAt.plusSeconds(120)) && state.releaseConnectionBackoff(conversationId, verifiedAt)
                if (!activityChanged && !connectionChanged) return@withLock
                // KEEP the original due/request, including RUNNING or uncertain paid attempts.
                // A separate short repair observes the newly eligible policy without cancelling it.
                val operations = scheduleRequests(app, conversationId) + listOfNotNull(resume(app, conversationId))
                operations.forEach { it.result.get(5, TimeUnit.SECONDS) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.w("ZhiYuContact", "Conversation scheduling repair deferred; durable contact intent is retained")
            }
        } }
    }

    /** Opening or leaving the app repairs persisted intent; it never opts in a denied permission. */
    suspend fun recover(context: Context, now: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        recoveryMutex.withLock {
        try {
            ProactiveNotifications.createChannel(context)
            if (!ProactiveNotifications.allowed(context)) return@withLock
            val chat = boundContactChat(context) ?: return@withLock
            val state = ProactiveWorkStore(context)
            if (!state.enabled(chat)) return@withLock
            latestProactiveActivity(context, chat)?.let { activity ->
                state.recordConversationActivity(chat, activity.messageId, activity.createdAt, now)
            }
            migrateLegacyNetworkRequests(context, chat)
            // Repairing jobs is not a contact check. Keep the real last outcome and due intact.
            val operations = scheduleRequests(context, chat) + listOfNotNull(resume(context, chat))
            operations.forEach { it.result.get(5, TimeUnit.SECONDS) }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.w("ZhiYuContact", "Contact scheduling repair deferred; stored preferences are retained")
        }
        }
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(UNIQUE_NAME)
        manager.cancelAllWorkByTag(WORK_TAG)
        LocalDailyShareScheduler.cancel(context)
    }
}
