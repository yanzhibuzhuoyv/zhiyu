package com.zhiyu.app.notifications

import android.content.Context
import androidx.work.*
import com.zhiyu.app.domain.ProactiveRules
import java.util.concurrent.TimeUnit

object ProactiveScheduler {
    const val UNIQUE_NAME = "zhiyu_companion_contact"
    const val CONVERSATION_INPUT = "conversation_id"

    fun schedule(context: Context, conversationId: String) {
        val request = PeriodicWorkRequestBuilder<ProactiveWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(CONVERSATION_INPUT to ProactiveRules.conversationId(conversationId)))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME) }
}
