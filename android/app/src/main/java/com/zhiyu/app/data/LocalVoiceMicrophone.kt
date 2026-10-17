package com.zhiyu.app.data

import android.content.Context
import android.os.SystemClock
import com.zhiyu.app.domain.ApiFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** User-started capture runs before ASR initialization; an eight-second FIFO preserves the prefix. */
class LocalVoiceMicrophone internal constructor(private val inputFactory: () -> VoiceCaptureInput) {
    constructor(context: Context) : this({ AndroidVoiceCaptureInput(context) })
    private val input = AtomicReference<VoiceCaptureInput?>()
    private val finished = AtomicBoolean(false)

    suspend fun capture(session: LocalVoiceRecognition, onStarted: () -> Unit,
        onPartial: (String) -> Unit, maximumSeconds: Int = LocalVoiceRules.MAX_RECORD_SECONDS,
        autoEndpoint: Boolean = true, onSamples: (FloatArray) -> Unit = {}): String =
        capture({ session }, onStarted, onPartial, maximumSeconds, autoEndpoint, onSamples)

    internal suspend fun capture(openRecognition: suspend () -> VoiceCaptureRecognition, onStarted: () -> Unit,
        onPartial: (String) -> Unit, maximumSeconds: Int = LocalVoiceRules.MAX_RECORD_SECONDS,
        autoEndpoint: Boolean = true, onSamples: (FloatArray) -> Unit = {}): String = withContext(Dispatchers.IO) {
        require(maximumSeconds in 1..LocalVoiceRules.MAX_RECORD_SECONDS)
        val created = inputFactory()
        if (!input.compareAndSet(null, created)) { created.close(); error("已经在听你说话") }
        finished.set(false)
        try {
            currentCoroutineContext().ensureActive()
            created.start()
            currentCoroutineContext().ensureActive()
            onStarted()
            recognizeCaptured(created, openRecognition, onPartial, maximumSeconds, autoEndpoint, onSamples)
        } finally { releaseInput(created) }
    }

    private suspend fun recognizeCaptured(source: VoiceCaptureInput, openRecognition: suspend () -> VoiceCaptureRecognition,
        onPartial: (String) -> Unit, maximumSeconds: Int, autoEndpoint: Boolean,
        onSamples: (FloatArray) -> Unit): String = coroutineScope {
        val packets = Channel<FloatArray>(80)
        val ownedRecognition = AtomicReference<VoiceCaptureRecognition?>()
        var model: Deferred<VoiceCaptureRecognition>? = null
        var producer: Job? = null
        try {
            model = async(Dispatchers.Default) { openRecognition().also(ownedRecognition::set) }
            producer = launch(Dispatchers.IO) { readPcm(source, packets, maximumSeconds, onSamples) }
            val session = model.await()
            decodePcm(session, packets, onPartial, autoEndpoint)
            producer.cancelAndJoin()
            currentCoroutineContext().ensureActive()
            session.finish().also { if (it.isBlank()) throw ApiFailure(422, "刚才没听清，再说一次好不好", "voice_no_speech") }
        } finally {
            producer?.cancel(); model?.cancel()
            // Release hardware before waiting for a synchronous native model constructor.
            try { releaseInput(source) } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    producer?.join(); model?.join()
                    while (true) (packets.tryReceive().getOrNull() ?: break).fill(0f)
                    packets.cancel()
                    ownedRecognition.getAndSet(null)?.close()
                }
            }
        }
    }

    private suspend fun readPcm(source: VoiceCaptureInput, packets: Channel<FloatArray>, maximumSeconds: Int,
        onSamples: (FloatArray) -> Unit) {
        val maximum = 16000 * maximumSeconds
        val deadline = SystemClock.elapsedRealtime() + maximumSeconds * 1000L
        var count = 0
        var staging = FloatArray(1600)
        var filled = 0
        try {
            while (!finished.get() && SystemClock.elapsedRealtime() < deadline && count < maximum) {
                currentCoroutineContext().ensureActive()
                val requested = minOf(1600 - filled, maximum - count)
                val samples = try { source.read(requested) } catch (failure: Exception) {
                    if (finished.get() || !currentCoroutineContext().isActive) break else throw failure
                }
                if (samples.isEmpty()) { delay(10); continue }
                require(samples.size <= requested && samples.all { it.isFinite() && it in -1f..1f }) { "录音采样数据无效" }
                samples.copyInto(staging, filled)
                filled += samples.size
                count += samples.size
                onSamples(samples.copyOf())
                if (filled == staging.size) {
                    enqueuePcm(packets, staging)
                    staging = FloatArray(1600)
                    filled = 0
                }
            }
            currentCoroutineContext().ensureActive()
            if (filled > 0) enqueuePcm(packets, staging.copyOf(filled))
        } finally { staging.fill(0f); source.stop(); packets.close() }
    }

    private fun enqueuePcm(packets: Channel<FloatArray>, owned: FloatArray) {
        if (!packets.trySend(owned).isSuccess) {
            owned.fill(0f)
            throw ApiFailure(0, "语音识别准备较慢，这次录音没有保存，请再录一次", "voice_prebuffer_full")
        }
    }

    private suspend fun decodePcm(session: VoiceCaptureRecognition, packets: Channel<FloatArray>,
        onPartial: (String) -> Unit, autoEndpoint: Boolean) {
        var lastText = ""
        for (samples in packets) {
            currentCoroutineContext().ensureActive()
            val text = try { session.accept(samples) } finally { samples.fill(0f) }
            if (text != lastText) { onPartial(text); lastText = text }
            if (autoEndpoint && text.isNotBlank() && session.endpoint()) break
        }
    }
    private fun releaseInput(source: VoiceCaptureInput) { if (input.compareAndSet(source, null)) source.close() }
    fun finishTurn() { finished.set(true); input.get()?.stop() }
}
