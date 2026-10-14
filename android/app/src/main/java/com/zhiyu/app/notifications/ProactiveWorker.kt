package com.zhiyu.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhiyu.app.data.LocalProactiveService
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.data.appCredentialStore
import com.zhiyu.app.data.ContactPauseStore
import com.zhiyu.app.data.ContactDeliveryGate
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProactiveWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(ProactiveScheduler.CONVERSATION_INPUT) ?: return@withContext Result.failure()
        try {
            val credentials = appCredentialStore(applicationContext)
            val state = ProactiveWorkStore(applicationContext)
            val continueChain = inputData.getBoolean(ProactiveScheduler.CHAIN_INPUT, false)
            val due = state.lastRun(id)?.nextDue
            if (state.enabled(id) && due != null && due.isAfter(java.time.Instant.now())) {
                ProactiveScheduler.next(applicationContext, id, continueChain = continueChain)
                return@withContext Result.success()
            }
            val service = LocalProactiveService(applicationContext, credentials)
            val pauses=ContactPauseStore(applicationContext)
            val contact=pauses.read(id)
            val engine = ProactiveDelivery(service, state, { ProactiveNotifications.allowed(applicationContext)&&
                ContactDeliveryGate.reason(pauses,id,contact)==null }, ProactiveForeground::isVisible) { message ->
                try {pauses.withDeliveryAllowed(id,contact.revision) {
                    if(ContactDeliveryGate.reason(pauses,id,contact,message)==null)
                        ProactiveNotifications.post(applicationContext, id, message, credentials.readBinding()?.profile?.name)
                }}catch(_:ContactDeliveryBlockedException){ /* A new user command cancels this notification. */ }
            }
            when (engine.run(id)) {
                ProactiveWorkOutcome.COMPLETE -> { ProactiveScheduler.next(applicationContext, id, continueChain = continueChain); Result.success() }
                ProactiveWorkOutcome.RETRY -> Result.retry()
                // A provider rejection pauses this attempt; it must not erase the user's durable opt-in.
                ProactiveWorkOutcome.STOP -> { ProactiveScheduler.next(applicationContext, id, continueChain = continueChain); Result.success() }
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
