package com.zhiyu.app.ui

import com.zhiyu.app.data.voicemessages.VoiceMessageStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Finite local cleanup survives disposal of the recording screen. Sent clips remain store-protected. */
internal object UnsentVoiceCleanup {
    private sealed interface Command
    private data class Discard(val store: VoiceMessageStore, val id: String) : Command
    private data class Barrier(val done: CompletableDeferred<Unit>) : Command
    private val pending = Channel<Command>(32)
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        for (command in pending) {
            when (command) {
                is Barrier -> command.done.complete(Unit)
                is Discard -> try { withTimeout(5_000) { command.store.discardDraft(command.id) } }
                    catch (failure: Exception) {
                        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                        android.util.Log.w("ZhiYuVoice", "Unsent recording cleanup deferred")
                    }
            }
        }
    }
    fun request(store: VoiceMessageStore, id: String): Boolean =
        worker.isActive && pending.trySend(Discard(store, id)).isSuccess

    /** Test/teardown barrier: all previously queued discards have completed when this returns. */
    internal suspend fun awaitIdle(timeoutMillis: Long = 5_000) {
        if (!worker.isActive) return
        val done = CompletableDeferred<Unit>()
        if (!pending.trySend(Barrier(done)).isSuccess) return
        withTimeout(timeoutMillis) { done.await() }
    }
}
