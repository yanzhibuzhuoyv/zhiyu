package com.zhiyu.app.data

import android.content.Context
import androidx.work.*
import com.zhiyu.app.domain.*
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
        val work=OneTimeWorkRequestBuilder<LocalSceneStockWorker>().setInputData(workDataOf("chat" to chat,"day" to day,"version" to version))
            .setInitialDelay(if(continueChain)30 else 0,java.util.concurrent.TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,java.util.concurrent.TimeUnit.SECONDS).build()
        // Every run covers the whole window. Future job callbacks must not start parallel stock renderers.
        WorkManager.getInstance(context).enqueueUniqueWork("zhiyu_stock_${chat}_$version",
            if(continueChain)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,work)
    }
}
