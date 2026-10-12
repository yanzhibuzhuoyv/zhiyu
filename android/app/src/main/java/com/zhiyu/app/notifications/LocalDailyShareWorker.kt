package com.zhiyu.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhiyu.app.data.LocalDailyShareService
import com.zhiyu.app.data.LocalDailyShareWorkStore
import com.zhiyu.app.data.appCredentialStore
import com.zhiyu.app.domain.ProactiveDelivery
import com.zhiyu.app.domain.ProactiveWorkOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class LocalDailyShareWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(LocalDailyShareScheduler.CHAT_INPUT) ?: return@withContext Result.failure()
        try {
            val state = LocalDailyShareWorkStore(applicationContext)
            val chain = inputData.getBoolean(LocalDailyShareScheduler.CHAIN_INPUT, false)
            val due = state.lastRun(id)?.nextDue
            if (!inputData.getBoolean("picture_ready", false) && state.enabled(id) && due?.isAfter(Instant.now()) == true) {
                LocalDailyShareScheduler.next(applicationContext, id, continueChain = chain)
                return@withContext Result.success()
            }
            val credentials = appCredentialStore(applicationContext)
            val service = LocalDailyShareService(applicationContext, credentials)
            val delivery = ProactiveDelivery(service, state,
                { ProactiveNotifications.allowed(applicationContext) }, ProactiveForeground::isVisible) { message ->
                ProactiveNotifications.post(applicationContext, id, message, credentials.readBinding()?.profile?.name)
            }
            when (delivery.run(id)) {
                ProactiveWorkOutcome.RETRY -> Result.retry()
                ProactiveWorkOutcome.COMPLETE, ProactiveWorkOutcome.STOP -> {
                    LocalDailyShareScheduler.next(applicationContext, id, continueChain = chain)
                    Result.success()
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            when (failure) {
                is IllegalArgumentException, is SecurityException -> Result.failure()
                else -> Result.retry()
            }
        }
    }
}
