package com.zhiyu.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zhiyu.app.BuildConfig
import com.zhiyu.app.data.ProactiveRepository
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.data.SecureCredentialStore
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProactiveWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(ProactiveScheduler.CONVERSATION_INPUT) ?: return@withContext Result.failure()
        try {
            val credentials = UsbDebugCredentialStore(SecureCredentialStore(applicationContext), BuildConfig.DEBUG && BuildConfig.USE_USB_BACKEND)
            val state = ProactiveWorkStore(applicationContext)
            val service = ProactiveRepository(credentials, BuildConfig.DEBUG)
            val engine = ProactiveDelivery(service, state, { ProactiveNotifications.allowed(applicationContext) }, ProactiveForeground::isVisible) { message ->
                ProactiveNotifications.post(applicationContext, id, message, credentials.readBinding()?.profile?.name)
            }
            when (engine.run(id)) {
                ProactiveWorkOutcome.COMPLETE -> Result.success()
                ProactiveWorkOutcome.RETRY -> Result.retry()
                ProactiveWorkOutcome.STOP -> { ProactiveScheduler.cancel(applicationContext); Result.failure() }
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
