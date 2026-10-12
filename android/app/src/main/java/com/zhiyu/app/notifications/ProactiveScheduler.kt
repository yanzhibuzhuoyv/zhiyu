package com.zhiyu.app.notifications

import android.content.Context
import androidx.work.*
import com.zhiyu.app.domain.ProactiveRules
import com.zhiyu.app.domain.ProactiveSchedulePolicy
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.ProactiveRunRecord
import com.zhiyu.app.data.LocalChatDatabase
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.data.appCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.time.Instant
import java.util.concurrent.TimeUnit

object ProactiveScheduler {
    const val UNIQUE_NAME = "zhiyu_companion_contact"
    const val CONVERSATION_INPUT = "conversation_id"
    const val WORK_TAG = "zhiyu_companion_delivery"
    const val CHAIN_INPUT = "continue_contact_chain"
    private const val RESUME_NAME = "zhiyu_companion_resume"

    fun schedule(context: Context, conversationId: String) {
        ProactiveNotifications.createChannel(context)
        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(CONVERSATION_INPUT to ProactiveRules.conversationId(conversationId)))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        next(context, conversationId)
    }

    fun next(context: Context, conversationId: String, now: Instant = Instant.now(), continueChain: Boolean = false) {
        val state = ProactiveWorkStore(context)
        if (!state.enabled(conversationId)) return
        val due = ProactiveSchedulePolicy.nextCheck(now, state.lastRun(conversationId))
        val request = OneTimeWorkRequestBuilder<ProactiveWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(CONVERSATION_INPUT to ProactiveRules.conversationId(conversationId), CHAIN_INPUT to true))
            .setInitialDelay(ProactiveSchedulePolicy.delayMillis(now, due), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_TAG).build()
        val policy = if (continueChain) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(ProactiveSchedulePolicy.uniqueDueName(conversationId), policy, request)
    }

    private fun resume(context: Context, conversationId: String) {
        val request = OneTimeWorkRequestBuilder<ProactiveWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(CONVERSATION_INPUT to ProactiveRules.conversationId(conversationId)))
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(RESUME_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Opening or leaving the app repairs persisted intent; it never opts in a denied permission. */
    suspend fun recover(context: Context, now: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        try {
            ProactiveNotifications.createChannel(context)
            if (!ProactiveNotifications.allowed(context)) return@withContext
            val binding = appCredentialStore(context).readBinding() ?: return@withContext
            if (!context.getDatabasePath("zhiyu_local_v1.sqlite3").isFile) return@withContext
            val owner = "companion-" + LocalMemoryCodec.sha((binding.providerId + "\u0000" + binding.model).toByteArray(Charsets.UTF_8))
            val chat = LocalChatDatabase(context).use { it.findConversation(owner) } ?: return@withContext
            if (chat.model != binding.model || !ProactiveWorkStore(context).enabled(chat.id)) return@withContext
            ProactiveWorkStore(context).recordRun(chat.id, ProactiveRunRecord(now, "not_due", now.plusSeconds(30)))
            schedule(context, chat.id)
            resume(context, chat.id)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.w("ZhiYuContact", "Contact scheduling repair deferred; stored preferences are retained")
        }
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(UNIQUE_NAME)
        manager.cancelAllWorkByTag(WORK_TAG)
    }
}
