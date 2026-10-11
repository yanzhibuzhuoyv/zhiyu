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
        if(day!=LocalDailyRules.dayId(Instant.now())) return Result.success()
        return try {
            MediaRules.path(chat)
            withContext(Dispatchers.IO) {
                LocalChatDatabase(applicationContext).use { archive ->
                    val sweep = LocalMediaRetention(applicationContext, archive::pinnedMediaIds).sweep()
                    if (sweep.failures.isNotEmpty()) Log.w("ZhiYuImages", "Unsent scene cache cleanup incomplete; referenced images are retained")
                }
            }
            // Free expired, unreferenced cache before an ingest can hit the media record limit.
            // A render/provider failure cannot prevent this sweep; originals remain protected.
            LocalSceneStock(applicationContext).prepare(chat,day)
            Result.success()
        }
        catch(error:CancellationException) { throw error }
        catch(_:IllegalArgumentException) { Result.failure() }
        catch(_:Exception) { Result.retry() }
    }
}
object LocalSceneStockScheduler {
    fun enqueue(context: Context, chat: String, version: Int, day: String = LocalDailyRules.dayId(Instant.now())) {
        MediaRules.path(chat); require(version>=0 && Regex("\\d{4}-\\d{2}-\\d{2}").matches(day))
        val work=OneTimeWorkRequestBuilder<LocalSceneStockWorker>().setInputData(workDataOf("chat" to chat,"day" to day)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("zhiyu_stock_${chat}_${day}_$version",ExistingWorkPolicy.KEEP,work)
    }
}
