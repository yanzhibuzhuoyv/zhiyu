package com.zhiyu.app.notifications

import android.content.Context
import androidx.work.*
import com.zhiyu.app.data.LocalDailyShareWorkStore
import com.zhiyu.app.domain.LocalDailyShareSchedulePolicy
import com.zhiyu.app.domain.ProactiveRules
import java.time.Instant
import java.util.concurrent.TimeUnit

object LocalDailyShareScheduler {
    const val WORK_TAG = "zhiyu_daily_share_delivery"
    const val CHAT_INPUT = "daily_share_chat"
    const val CHAIN_INPUT = "continue_daily_share_chain"
    private const val PERIODIC = "zhiyu_daily_share_repair"
    private const val PHOTO_READY = "zhiyu_daily_share_picture_"

    fun schedule(context: Context, chat: String, now: Instant = Instant.now()) {
        ProactiveRules.conversationId(chat)
        if (!LocalDailyShareWorkStore(context).enabled(chat) || !ProactiveNotifications.allowed(context)) return
        ProactiveNotifications.createChannel(context)
        val request = PeriodicWorkRequestBuilder<LocalDailyShareWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network()).setInputData(workDataOf(CHAT_INPUT to chat))
            .setInitialDelay(15, TimeUnit.MINUTES).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        next(context, chat, now)
    }

    fun next(context: Context, chat: String, now: Instant = Instant.now(), continueChain: Boolean = false) {
        val state = LocalDailyShareWorkStore(context)
        if (!state.enabled(chat) || !ProactiveNotifications.allowed(context)) return
        val due = LocalDailyShareSchedulePolicy.nextCheck(now, state.lastRun(chat))
        val request = OneTimeWorkRequestBuilder<LocalDailyShareWorker>().setConstraints(network())
            .setInputData(workDataOf(CHAT_INPUT to ProactiveRules.conversationId(chat), CHAIN_INPUT to true))
            .setInitialDelay(LocalDailyShareSchedulePolicy.delayMillis(now, due), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(LocalDailyShareSchedulePolicy.uniqueDueName(chat),
            if (continueChain) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** Inventory arrival can re-check a waiting event, without replacing its independent due chain. */
    fun pictureAvailable(context: Context, chat: String) {
        if (!LocalDailyShareWorkStore(context).enabled(chat) || !ProactiveNotifications.allowed(context)) return
        val request = OneTimeWorkRequestBuilder<LocalDailyShareWorker>().setConstraints(network())
            .setInputData(workDataOf(CHAT_INPUT to ProactiveRules.conversationId(chat), "picture_ready" to true))
            .setInitialDelay(10, TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(PHOTO_READY + chat, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
