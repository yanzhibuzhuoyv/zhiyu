package com.zhiyu.app.ui

import com.zhiyu.app.data.voicemessages.VoiceMessageStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Finite local cleanup survives disposal of the recording screen. Sent clips remain store-protected. */
internal object UnsentVoiceCleanup {
    private data class Command(val store: VoiceMessageStore, val id: String)
    private val pending = Channel<Command>(32)
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        for (command in pending) {
            try { withTimeout(5_000) { command.store.discardDraft(command.id) } }
            catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                android.util.Log.w("ZhiYuVoice", "Unsent recording cleanup deferred")
            }
        }
    }
    fun request(store: VoiceMessageStore, id: String): Boolean =
        worker.isActive && pending.trySend(Command(store, id)).isSuccess
}
