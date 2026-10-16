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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import java.time.Instant
import java.util.concurrent.TimeUnit

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
    }

    private fun scheduleRequests(context: Context, conversationId: String): List<Operation> {
        val id = ProactiveRules.conversationId(conversationId)
        ProactiveNotifications.createChannel(context)
        if (!ProactiveWorkStore(context).enabled(id) || !ProactiveNotifications.allowed(context)) return emptyList()
        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
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
        continueChain: Boolean = false, replacePending: Boolean = false): Operation? {
        val state = ProactiveWorkStore(context)
        if (!state.enabled(conversationId) || !ProactiveNotifications.allowed(context)) return null
        val due = ProactiveSchedulePolicy.nextCheck(now, state.lastRun(conversationId))
        val request = OneTimeWorkRequestBuilder<ProactiveWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(CONVERSATION_INPUT to ProactiveRules.conversationId(conversationId), CHAIN_INPUT to true))
            .setInitialDelay(ProactiveSchedulePolicy.delayMillis(now, due), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_TAG).build()
        val policy = when {
            replacePending -> ExistingWorkPolicy.REPLACE
            continueChain -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.KEEP
        }
        return WorkManager.getInstance(context).enqueueUniqueWork(ProactiveSchedulePolicy.uniqueDueName(conversationId), policy, request)
    }

    private fun resume(context: Context, conversationId: String): Operation? {
        if (!ProactiveWorkStore(context).enabled(conversationId) || !ProactiveNotifications.allowed(context)) return null
        val request = OneTimeWorkRequestBuilder<ProactiveWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
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
        return recoveryScope.launch { recoveryMutex.withLock {
            try {
                ProactiveNotifications.createChannel(app)
                if (!ProactiveNotifications.allowed(app)) return@withLock
                val chat = boundContactChat(app) ?: return@withLock
                val now = Instant.now()
                if (!ProactiveWorkStore(app).releaseConnectionBackoff(chat, now)) return@withLock
                val operations = scheduleRequests(app, chat) + listOfNotNull(
                    enqueueNext(app, chat, now, replacePending = true))
                operations.forEach { it.result.get(5, TimeUnit.SECONDS) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                Log.w("ZhiYuContact", "Validated connection scheduling repair deferred; stored preferences are retained")
            }
        } }
    }

    /** Opening or leaving the app repairs persisted intent; it never opts in a denied permission. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun recover(context: Context, now: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        recoveryMutex.withLock {
        try {
            ProactiveNotifications.createChannel(context)
            if (!ProactiveNotifications.allowed(context)) return@withLock
            val chat = boundContactChat(context) ?: return@withLock
            if (!ProactiveWorkStore(context).enabled(chat)) return@withLock
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
