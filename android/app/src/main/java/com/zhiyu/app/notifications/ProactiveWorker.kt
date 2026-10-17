package com.zhiyu.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProactiveWorker internal constructor(context: Context, parameters: WorkerParameters,
    private val deliver: suspend (String, Boolean) -> ProactiveWorkOutcome,
) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters,
        { id, continueChain -> ProactiveWorkRunner(context).run(id, continueChain) })
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(ProactiveScheduler.CONVERSATION_INPUT) ?: return@withContext Result.failure()
        try {
            val continueChain = inputData.getBoolean(ProactiveScheduler.CHAIN_INPUT, false)
            when (deliver(id, continueChain)) {
                ProactiveWorkOutcome.COMPLETE -> Result.success()
                ProactiveWorkOutcome.RETRY -> Result.retry()
                // A provider rejection pauses this attempt; it must not erase the user's durable opt-in.
                ProactiveWorkOutcome.STOP -> Result.success()
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
