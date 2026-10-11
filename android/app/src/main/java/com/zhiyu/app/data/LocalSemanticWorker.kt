package com.zhiyu.app.data

import android.content.Context
import androidx.work.*
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.notifications.ProactiveForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object LocalSemanticScheduler {
    fun enqueue(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        manager.enqueueUniqueWork("local_semantic_incremental", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LocalSemanticWorker>().setConstraints(constraints).build())
        manager.enqueueUniquePeriodicWork("local_semantic_recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LocalSemanticWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).build())
    }
}

/** Reads only a binding, never an API Key. Bounded background windows survive process recreation. */
class LocalSemanticWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        if (ProactiveForeground.isVisible()) return@withContext Result.retry()
        val binding = appCredentialStore(applicationContext).readBinding() ?: return@withContext Result.success()
        val encoder = BgeLocalSemanticEncoder(applicationContext)
        if (!encoder.isAvailable()) return@withContext Result.success()
        val archive = LocalChatDatabase(applicationContext)
        try {
            val owner = "companion-" + LocalMemoryCodec.sha((binding.providerId + "\u0000" + binding.model).toByteArray(Charsets.UTF_8))
            val chat = archive.findConversation(owner) ?: return@withContext Result.success()
            val service = LocalSemanticService(archive,encoder)
            val deadline = System.nanoTime() + 90_000_000_000L
            var encoded = 0
            while (System.nanoTime() < deadline && encoded < 128) {
                val result = service.indexBatch(owner,chat.id,minOf(12,128 - encoded))
                encoded += result.encodedChunks
                if (result.reason != "batch_limit") return@withContext if (result.reason == "encoding_failed") Result.retry() else Result.success()
            }
            Result.success()
        } catch (cancelled:CancellationException) { throw cancelled }
        catch (_:Exception) { Result.retry() }
        finally { archive.close() }
    }
}
