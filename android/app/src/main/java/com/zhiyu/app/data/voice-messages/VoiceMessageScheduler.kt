package com.zhiyu.app.data.voicemessages

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zhiyu.app.data.LocalChatDatabase
import com.zhiyu.app.data.LocalVoiceEngine
import com.zhiyu.app.data.LocalVoicePreferences
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object VoiceMessageScheduler {
    internal const val TAG = "zhiyu_voice_reply"
    private val enqueueLock = Any()
    fun enqueue(context: Context, owner: String, chatId: String, message: Message): Unit = synchronized(enqueueLock) {
        require(message.role == "assistant" && message.origin.startsWith("voice_reply:"))
        val id = requireNotNull(VoiceMessageOrigins.clipId(message.origin))
        val sha = LocalMemoryCodec.sha(LocalMemoryCodec.bytes(message.content))
        val app = context.applicationContext ?: context
        val original = LocalChatDatabase(app).use { it.recognitionOriginal(owner, chatId, message.id) }
        require(original?.role == "assistant" && original.sha256 == sha && original.origin == message.origin) { "语音来源与原话不一致" }
        val store = VoiceMessageStore(app)
        val pending = store.prepareReply(id, owner, chatId, message.id, sha, LocalVoicePreferences(app).tuning())
        if (pending.state != VoiceClipState.PENDING) return@synchronized
        val request = OneTimeWorkRequestBuilder<VoiceMessageWorker>().addTag(TAG)
            .setInputData(workDataOf("clip" to id, "owner" to owner, "chat" to chatId, "message" to message.id, "sha" to sha)).build()
        WorkManager.getInstance(app).enqueueUniqueWork("zhiyu_voice_reply_$id", ExistingWorkPolicy.KEEP, request)
    }
}

class VoiceMessageWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("clip") ?: return@withContext Result.failure()
        if (VoiceMessageOrigins.clipId("voice_reply:$id") != id) return@withContext Result.failure()
        val owner = inputData.getString("owner") ?: return@withContext Result.failure()
        val chat = inputData.getString("chat") ?: return@withContext Result.failure()
        val message = inputData.getString("message") ?: return@withContext Result.failure()
        val sha = inputData.getString("sha") ?: return@withContext Result.failure()
        val store = VoiceMessageStore(applicationContext)
        try {
            serial.withLock { withTimeout(180_000) {
                currentCoroutineContext().ensureActive()
                LocalChatDatabase(applicationContext).use { archive ->
                    LocalVoiceEngine(applicationContext).use { engine ->
                        val prepare = VoiceReplyPreparation(store,
                            { archive.recognitionOriginal(owner, chat, message) },
                            { LocalVoicePreferences(applicationContext).snapshot() },
                            { text, voice, tuning, active -> engine.synthesize(text, voice, tuning, active) })
                        prepare.run(id, owner, chat, message, sha)
                    }
                }
            } }
            Result.success()
        } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { store.fail(id,
                if (failure is CancellationException) "interrupted" else "preparation_failed") }
            if (failure is CancellationException) throw failure
            Result.success() // Terminal local failure remains visible; do not loop synthesis/retry.
        }
    }
    companion object { private val serial = Mutex() }
}
