package com.zhiyu.app.data

import android.content.Context
import androidx.work.*
import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.BackgroundNetworkRepair
import com.zhiyu.app.notifications.ProactiveForeground
import com.zhiyu.app.notifications.ProactiveNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Optional cloud classification runs only in the background with the current bound model. */
class LocalMemoryRecognitionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (ProactiveForeground.isVisible()) return@withContext Result.retry()
        // Do not read a Key or start a paid classification when the runtime network is absent.
        if (!ProactiveNetwork.available(applicationContext)) return@withContext Result.retry()
        val credentials = appCredentialStore(applicationContext)
        val saved = credentials.read() ?: return@withContext Result.success()
        val binding = credentials.readBinding() ?: return@withContext Result.success()
        if (!valid(saved, binding)) return@withContext Result.success()
        val database = LocalChatDatabase(applicationContext)
        try {
            val owner = "companion-" + LocalMemoryCodec.sha((binding.providerId + "\u0000" + binding.model).toByteArray(Charsets.UTF_8))
            val chat = database.findConversation(owner) ?: return@withContext Result.success()
            if (chat.model != binding.model) return@withContext Result.failure()
            val identity = LocalMemoryRecognitionIdentity(owner, chat.id, binding.providerId, binding.model)
            val store = LocalMemoryRecognitionSqlStore(database)
            val allowed = { credentials.read() == saved && credentials.readBinding() == binding }
            val recognizer = LocalMemoryOfficialRecognizer(binding.providerId, saved.apiKey, binding.model)
            LocalMemoryRecognitionEngine(store, recognizer, ProactiveForeground.changes, allowed).run(identity)
            if (store.pending(identity)) Result.retry() else Result.success()
        } catch (_: LocalMemoryRecognitionPaused) { Result.retry() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { Result.failure() }
        catch (_: SecurityException) { Result.failure() }
        catch (_: Exception) { Result.retry() }
        finally { database.close() }
    }
    private fun valid(saved: SavedConnection, binding: CompanionBinding) =
        saved.serverUrl == LocalChatRepository.LOCAL_URL && saved.session == null && saved.apiKey.isNotBlank() &&
            saved.providerId == binding.providerId && saved.model == binding.model && binding.profileConfigured
}

object LocalMemoryRecognitionScheduler {
    private const val ONCE = "zhiyu_native_memory_recognition_once"
    private const val PERIODIC = "zhiyu_native_memory_recognition_periodic"
    private fun constraints() = ProactiveNetwork.constraints()

    /** Invoke after successful login/send and when the app becomes hidden. */
    fun schedule(context: Context) {
        val app = context.applicationContext ?: context
        val manager = WorkManager.getInstance(app)
        val once = OneTimeWorkRequestBuilder<LocalMemoryRecognitionWorker>().setConstraints(constraints())
            .setInitialDelay(15, TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, once)
        val periodic = PeriodicWorkRequestBuilder<LocalMemoryRecognitionWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // UPDATE keeps the same periodic UUID and adds the INTERNET-only request to an old row.
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        BackgroundNetworkRepair.launch("Memory recognition network repair") { migrateLegacyNetworkNow(app) }
    }

    /** This worker does not consume input, so updating an existing lease cannot change its meaning. */
    private fun migrateLegacyNetworkNow(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.getWorkInfosForUniqueWork(ONCE).get(5, TimeUnit.SECONDS).filter { row ->
            !row.state.isFinished && row.workerClassName == LocalMemoryRecognitionWorker::class.java.name &&
                ProactiveNetwork.isLegacy(row.constraints)
        }.forEach { row ->
            val request = OneTimeWorkRequestBuilder<LocalMemoryRecognitionWorker>().setId(row.id)
                .setInitialDelay(row.initialDelayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(ProactiveNetwork.constraints(row.constraints))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .apply { row.tags.forEach(::addTag) }.build()
            manager.updateWork(request).get(5, TimeUnit.SECONDS)
        }
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(ONCE)
        manager.cancelUniqueWork(PERIODIC)
    }
}
