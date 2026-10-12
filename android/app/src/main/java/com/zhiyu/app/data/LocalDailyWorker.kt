package com.zhiyu.app.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zhiyu.app.domain.LocalDailyRules
import com.zhiyu.app.domain.LocalDailyScene
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.MediaRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Completely local. Android may defer background execution; date selection itself never uses a stale worker input. */
class LocalDailyWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        LocalDailyScheduler.refreshBoundPlan(applicationContext, expectedChat = inputData.getString(LocalDailyScheduler.CHAT_INPUT))
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: IllegalArgumentException) { Result.failure() }
    catch (_: SecurityException) { Result.failure() }
    catch (_: Exception) { Result.retry() }
}

object LocalDailyScheduler {
    const val CHAT_INPUT = "daily_conversation_id"
    private const val PERIODIC = "zhiyu_local_daily_repair"
    private const val MIDNIGHT = "zhiyu_local_daily_midnight_"

    /** A midnight attempt plus local repair checks; no network, notification, alarm, or location permission needed. */
    fun schedule(context: Context, chatId: String, now: Instant = Instant.now()) {
        MediaRules.path(chatId)
        val manager = WorkManager.getInstance(context)
        val next = LocalDailyRules.nextMidnight(now)
        val delayMs = Duration.between(now, next).toMillis().coerceAtLeast(1L)
        val data = workDataOf(CHAT_INPUT to chatId)
        val midnight = OneTimeWorkRequestBuilder<LocalDailyWorker>().setInputData(data)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // Separate dated names avoid replacing the currently executing midnight worker with its successor.
        manager.enqueueUniqueWork(MIDNIGHT + chatId + "_" + LocalDailyRules.dayId(next), ExistingWorkPolicy.KEEP, midnight)
        val periodic = PeriodicWorkRequestBuilder<LocalDailyWorker>(15, TimeUnit.MINUTES).setInputData(data)
            .setInitialDelay(15, TimeUnit.MINUTES).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
    }

    /** Does not read a Key or create a new chat; the fixed binding must resolve to its existing local conversation. */
    suspend fun refreshBoundPlan(context: Context, now: Instant = Instant.now(), expectedChat: String? = null): LocalDailyScene? {
        val appContext = context.applicationContext ?: context
        val binding = withContext(Dispatchers.IO) { appCredentialStore(appContext).readBinding() } ?: return null
        val chat = withContext(Dispatchers.IO) {
            LocalChatDatabase(appContext).use { database ->
                val owner = "companion-" + LocalMemoryCodec.sha((binding.providerId + "\u0000" + binding.model).toByteArray(Charsets.UTF_8))
                database.findConversation(owner)?.takeIf { it.model == binding.model && (expectedChat == null || it.id == expectedChat) }
            }
        } ?: return null
        val version = LocalVisualRepository(appContext).profile(chat.id).visualVersion
        val scene = withContext(Dispatchers.IO) { LocalDailyRepository(appContext).use { it.scene(chat.id, version, now) } }
        LocalSceneStockScheduler.enqueue(appContext, chat.id, version, scene.dayId)
        schedule(appContext, chat.id, now)
        com.zhiyu.app.notifications.LocalDailyShareScheduler.schedule(appContext, chat.id, now)
        return scene
    }

    /** Attach to repeatOnLifecycle(STARTED). An open chat crosses midnight even when nobody sends a message. */
    suspend fun monitorForeground(context: Context) {
        while (true) {
            val now = Instant.now()
            try { refreshBoundPlan(context, now) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Original plan and chat remain untouched. A foreground minute or background repair retries.
                Log.w("ZhiYuDaily", "Local OC day plan refresh failed; stored plan and chat are retained")
                delay(60_000)
                continue
            }
            val untilMidnight = Duration.between(Instant.now(), LocalDailyRules.nextMidnight(now)).toMillis()
            delay(untilMidnight.coerceIn(1L, 60_000L))
        }
    }
}
