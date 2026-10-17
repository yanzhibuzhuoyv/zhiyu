package com.zhiyu.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhiyu.app.data.LocalDailyShareService
import com.zhiyu.app.data.LocalDailyShareWorkStore
import com.zhiyu.app.data.appCredentialStore
import com.zhiyu.app.data.ContactPauseStore
import com.zhiyu.app.data.ContactDeliveryGate
import com.zhiyu.app.domain.ContactDeliveryBlockedException
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
            // API 26/27 has no platform network request; keep the same no-key/no-provider
            // guarantee as proactive contact when the runtime network is actually absent.
            if (!ProactiveNetwork.available(applicationContext)) return@withContext Result.retry()
            val credentials = appCredentialStore(applicationContext)
            val service = LocalDailyShareService(applicationContext, credentials)
            val pauses=ContactPauseStore(applicationContext)
            val contact=pauses.read(id)
            val delivery = ProactiveDelivery(service, state,
                { ProactiveNotifications.allowed(applicationContext)&&ContactDeliveryGate.reason(pauses,id,contact)==null }, ProactiveForeground::isVisible) { message ->
                try {pauses.withDeliveryAllowed(id,contact.revision) {
                    if(ContactDeliveryGate.reason(pauses,id,contact,message)==null)
                        ProactiveNotifications.post(applicationContext, id, message, credentials.readBinding()?.profile?.name)
                }}catch(_:ContactDeliveryBlockedException){ /* A new user command cancels this notification. */ }
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
