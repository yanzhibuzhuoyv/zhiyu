package com.zhiyu.app.data

import android.content.Context
import androidx.work.*
import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.BackgroundNetworkRepair
import com.zhiyu.app.notifications.ProactiveNetwork
import kotlinx.coroutines.CancellationException
import java.time.Instant
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalSceneStockWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val chat=inputData.getString("chat") ?: return Result.failure()
        val day=inputData.getString("day") ?: return Result.failure()
        val now = Instant.now()
        if(day !in LocalScenePrefetchRules.days(now)) return Result.success()
        val version = inputData.getInt("version", -1).takeIf { it >= 0 }
        return try {
            MediaRules.path(chat)
            withContext(Dispatchers.IO) {
                LocalChatDatabase(applicationContext).use { archive ->
                    val sweep = LocalMediaRetention(applicationContext, archive::pinnedMediaIds).sweep()
                    if (sweep.failures.isNotEmpty()) Log.w("ZhiYuImages", "Decoded image audit incomplete; all stored pictures are retained")
                }
                com.zhiyu.app.data.local.qwen.QwenImageJobStore(applicationContext).sweepBefore(LocalDailyRules.dayId(now))
            }
            if (!ProactiveNetwork.available(applicationContext)) return Result.retry()
            val result = LocalSceneStock(applicationContext).prepare(chat,day,version)
            if (result.prepared > 0) com.zhiyu.app.notifications.LocalDailyShareScheduler.pictureAvailable(applicationContext, chat)
            if (result.reason in setOf("yield","correction_pending")) {
                LocalSceneStockScheduler.enqueue(applicationContext,chat,
                    version?:LocalVisualRepository(applicationContext).profile(chat).visualVersion,day,continueChain=true)
            }
            Result.success()
        }
        catch(error:CancellationException) { throw error }
        catch(_:IllegalArgumentException) { Result.failure() }
        catch(_:Exception) { Result.retry() }
    }
}
object LocalSceneStockScheduler {
    fun enqueue(context: Context, chat: String, version: Int, day: String = LocalDailyRules.dayId(Instant.now()),continueChain:Boolean=false) {
        MediaRules.path(chat); require(version>=0 && Regex("\\d{4}-\\d{2}-\\d{2}").matches(day))
        java.time.LocalDate.parse(day)
        migrateLegacyNetwork(context, chat, version, day)
        val work=OneTimeWorkRequestBuilder<LocalSceneStockWorker>().setInputData(workDataOf("chat" to chat,"day" to day,"version" to version))
            .setInitialDelay(if(continueChain)30 else 0,java.util.concurrent.TimeUnit.SECONDS)
            .setConstraints(ProactiveNetwork.constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,java.util.concurrent.TimeUnit.SECONDS).build()
        // Every run covers the whole window. Future job callbacks must not start parallel stock renderers.
        WorkManager.getInstance(context).enqueueUniqueWork("zhiyu_stock_${chat}_$version",
            if(continueChain)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,work)
    }

    /**
     * A waiting row under this chat/version window is retargeted to the caller's current day.
     * A RUNNING renderer is never touched: its paid request must finish under its original input.
     */
    private fun migrateLegacyNetwork(context: Context, chat: String, version: Int, day: String) {
        val app = context.applicationContext ?: context
        BackgroundNetworkRepair.launch("Scene stock network repair") { migrateLegacyNetworkNow(app, chat, version, day) }
    }

    private fun migrateLegacyNetworkNow(context: Context, chat: String, version: Int, day: String) {
        val manager = WorkManager.getInstance(context)
        manager.getWorkInfosForUniqueWork("zhiyu_stock_${chat}_$version")
            .get(5, java.util.concurrent.TimeUnit.SECONDS).filter { row ->
                !row.state.isFinished && row.state != WorkInfo.State.RUNNING &&
                    row.workerClassName == LocalSceneStockWorker::class.java.name &&
                    ProactiveNetwork.isLegacy(row.constraints)
            }.forEach { row ->
                val request = OneTimeWorkRequestBuilder<LocalSceneStockWorker>().setId(row.id)
                    .setInputData(workDataOf("chat" to chat, "day" to day, "version" to version))
                    .setInitialDelay(row.initialDelayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .setConstraints(ProactiveNetwork.constraints(row.constraints))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                    .apply { row.tags.forEach(::addTag) }.build()
                manager.updateWork(request).get(5, java.util.concurrent.TimeUnit.SECONDS)
            }
    }
}
