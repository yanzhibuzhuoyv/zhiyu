package com.zhiyu.app.data.local.qwen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import com.zhiyu.app.R
import com.zhiyu.app.data.LocalSceneStockScheduler
import com.zhiyu.app.data.LocalSceneResourceReader
import com.zhiyu.app.domain.LocalDailyRules
import com.zhiyu.app.domain.LocalScenePrefetchRules
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Foreground long-running native job. OS quotas may defer it; phases survive that interruption. */
class QwenImageWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = engineLock.withLock {
        if (!QwenRelease.supported()) return@withLock Result.failure()
        val jobs = QwenImageJobStore(applicationContext)
        val outcome = withContext(Dispatchers.IO) { QwenImageQueue(jobs, execute = { job ->
            if (job.day !in LocalScenePrefetchRules.days(Instant.now())) {
                jobs.write(job.copy(state = "stale"))
            } else {
            val today = java.time.LocalDate.parse(LocalDailyRules.dayId(Instant.now()))
            val nextDay = job.day == today.plusDays(1).toString()
            val decision = LocalScenePrefetchRules.decision(LocalSceneResourceReader(applicationContext).read(), nextDay)
            if (!decision.allowed) throw QwenResourceDeferred()
            setForeground(foreground())
            QwenImagePipeline(applicationContext).run(job)
            }
        }, completed = { job -> LocalSceneStockScheduler.enqueue(applicationContext,job.chat,job.version,job.day) },
            next = { QwenImageScheduler.next(applicationContext) }).run() }
        if (outcome == QwenQueueOutcome.RETRY) Result.retry() else Result.success()
    }
    private fun foreground(): ForegroundInfo {
        val channel = "zhiyu_local_photos"
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,"生活照片准备",NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(applicationContext,channel).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("正在准备生活照片").setContentText("聊天可以继续，照片会提前准备好。")
            .setOngoing(true).addAction(Notification.Action.Builder(null,"暂停这次准备",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()).build()
        return if (Build.VERSION.SDK_INT >= 34) ForegroundInfo(4082,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else ForegroundInfo(4082,notification)
    }
    companion object { private val engineLock = Mutex() }
}

object QwenImageScheduler {
    private const val NAME = "zhiyu_qwen_image_queue"
    private fun request() = OneTimeWorkRequestBuilder<QwenImageWorker>()
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES).build()
    fun enqueue(context: Context) { WorkManager.getInstance(context).enqueueUniqueWork(NAME,ExistingWorkPolicy.KEEP,request()) }
    fun next(context: Context) { WorkManager.getInstance(context).enqueueUniqueWork(NAME,ExistingWorkPolicy.APPEND_OR_REPLACE,request()) }
}
