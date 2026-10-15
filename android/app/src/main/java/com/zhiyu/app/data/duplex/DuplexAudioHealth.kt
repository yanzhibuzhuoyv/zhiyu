package com.zhiyu.app.data.duplex

import com.zhiyu.app.domain.ApiFailure
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Only sanitized device failure codes; no transcript, provider error, or PCM is retained. */
internal class DuplexAudioHealth {
    private val failure = AtomicReference<String?>()
    private val stop = AtomicReference<(() -> Unit)?>()
    private val closed = AtomicBoolean(false)

    fun fail(code: String) {
        if (!closed.get() && failure.compareAndSet(null, code)) signalStop()
    }

    fun onFailure(action: () -> Unit) {
        stop.set(action)
        if (failure.get() != null && !closed.get()) signalStop()
    }

    fun check() {
        failure.get()?.let { throw ApiFailure(0, "通话声音暂时中断，请重新开始", it) }
        check(!closed.get()) { "Call audio has ended" }
    }

    fun close() { closed.set(true); stop.set(null) }

    private fun signalStop() {
        try { stop.get()?.invoke() }
        catch (_: RuntimeException) {
            // Called from Android focus/device listeners: report failure through the capture loop,
            // never throw a device pause/flush exception out of the system's main-thread callback.
            failure.set("voice_duplex_audio_stop_failed")
        }
    }
}
