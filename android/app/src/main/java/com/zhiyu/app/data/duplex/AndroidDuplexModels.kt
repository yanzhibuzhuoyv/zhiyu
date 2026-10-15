package com.zhiyu.app.data.duplex

import android.content.Context
import com.zhiyu.app.data.LocalVoiceEngine
import com.zhiyu.app.data.LocalVoiceRecognition
import com.zhiyu.app.data.LocalVoiceRules
import com.zhiyu.app.domain.VoiceTuningSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import rig2d.voice.VoicePcm
import rig2d.voice.VoiceRecognition
import rig2d.voice.VoiceRecognizer
import rig2d.voice.VoiceStamp
import rig2d.voice.VoiceSynthesizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class AndroidDuplexModels(context: Context, private val voiceId: Int,
    private val tuning: VoiceTuningSettings) : AutoCloseable {
    internal val asrOwner = LocalVoiceEngine(context)
    internal val ttsOwner = LocalVoiceEngine(context)
    private val asrClosed = AtomicBoolean(false)
    private val ttsClosed = AtomicBoolean(false)
    private val synthesis = AtomicReference<VoiceStamp?>()

    val recognizer: VoiceRecognizer = object : VoiceRecognizer {
        override suspend fun open(stamp: VoiceStamp): VoiceRecognition {
            var opened: LocalVoiceRecognition? = null
            try {
                return withContext(Dispatchers.IO) {
                    check(!asrClosed.get())
                    val stream = asrOwner.openRecognition().also { opened = it }
                    currentCoroutineContext().ensureActive()
                    Recognition(stream)
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { opened?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
        override fun close() { if (asrClosed.compareAndSet(false, true)) asrOwner.close() }
    }

    val synthesizer: VoiceSynthesizer = object : VoiceSynthesizer {
        override suspend fun synthesize(text: String, stamp: VoiceStamp, active: () -> Boolean): VoicePcm =
            withContext(Dispatchers.IO) {
                check(!ttsClosed.get())
                currentCoroutineContext().ensureActive()
                if (!active()) throw CancellationException()
                check(synthesis.compareAndSet(null, stamp)) { "Previous native synthesis is still running" }
                val job = currentCoroutineContext()
                try {
                    // Keep LocalVoiceTtsCallback's concrete JNI invoke(float[]) signature unchanged.
                    val chunks = LocalVoiceRules.speechChunks(text)
                    require(chunks.size <= 2) { "Speech chunk is too large" }
                    val audios = chunks.map { part ->
                        ttsOwner.synthesize(part, voiceId, tuning) {
                            job[kotlinx.coroutines.Job]?.isActive != false && synthesis.get() == stamp && active()
                        }
                    }
                    job.ensureActive()
                    if (synthesis.get() != stamp || !active()) throw CancellationException()
                    val rate = audios.first().sampleRate
                    val size = audios.sumOf { it.samples.size }
                    require(size <= rate * 30 && audios.all { it.sampleRate == rate })
                    val pcm = FloatArray(size); var offset = 0
                    audios.forEach { it.samples.copyInto(pcm, offset); offset += it.samples.size }
                    VoicePcm(pcm, rate)
                } finally { synthesis.compareAndSet(stamp, null) }
            }
        override fun cancel(stamp: VoiceStamp) { synthesis.updateAndGet { if (it == stamp) null else it } }
        override fun close() {
            synthesis.set(null)
            if (ttsClosed.compareAndSet(false, true)) ttsOwner.close()
        }
    }

    suspend fun warm() = withContext(Dispatchers.IO) {
        LocalVoiceRules.voice(voiceId)
        currentCoroutineContext().ensureActive()
        asrOwner.openRecognition().use { }
        currentCoroutineContext().ensureActive()
        check(ttsOwner.speakerCount() > voiceId)
        currentCoroutineContext().ensureActive()
    }

    override fun close() {
        var failure: Throwable? = null
        try { synthesizer.close() } catch (problem: Throwable) { failure = problem }
        try { recognizer.close() } catch (problem: Throwable) {
            if (failure == null) failure = problem else failure.addSuppressed(problem)
        }
        failure?.let { throw it }
    }

    private class Recognition(private val stream: LocalVoiceRecognition) : VoiceRecognition {
        override suspend fun accept(frame: VoicePcm): String = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            require(frame.sampleRate == 16000 && frame.sampleCount <= 1600)
            stream.accept(frame.samplesCopy()).also { currentCoroutineContext().ensureActive() }
        }
        override suspend fun finish(): String = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            stream.finish().also { currentCoroutineContext().ensureActive() }
        }
        override fun close() = stream.close()
    }
}
