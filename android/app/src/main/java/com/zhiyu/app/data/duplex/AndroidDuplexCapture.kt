package com.zhiyu.app.data.duplex

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import rig2d.voice.VoiceCapture
import rig2d.voice.VoicePcm
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidDuplexCapture private constructor(private val context: Context,
    internal val audioRecord: AudioRecord, private val echo: AcousticEchoCanceler?,
    private val health: DuplexAudioHealth, private val onClose: () -> Unit, private val checkRoute: () -> Unit,
    val aecEnabled: Boolean) : VoiceCapture {
    private val closed = AtomicBoolean(false)
    private val collected = AtomicBoolean(false)

    override fun frames(): Flow<VoicePcm> = flow {
        check(collected.compareAndSet(false, true)) { "Microphone frames can only be collected once" }
        check(!closed.get())
        checkPermission(context); health.check(); currentCoroutineContext().ensureActive()
        audioRecord.startRecording()
        check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
        val buffer = ShortArray(640); val framer = DuplexPcmFramer()
        var receivedAt = SystemClock.elapsedRealtime()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (closed.get()) throw CancellationException("Call microphone ended")
                checkPermission(context); checkRoute(); health.check()
                if (aecEnabled && echo?.let { it.hasControl() && it.enabled } != true) {
                    health.fail("voice_duplex_aec_lost"); health.check()
                }
                if (Build.VERSION.SDK_INT >= 29 && audioRecord.activeRecordingConfiguration?.isClientSilenced == true) {
                    health.fail("voice_duplex_capture_silenced"); health.check()
                }
                check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone stopped unexpectedly" }
                val count = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Microphone read failed" }
                if (count == 0) {
                    check(SystemClock.elapsedRealtime() - receivedAt < 3000) { "Microphone produced no PCM" }
                    delay(5)
                } else {
                    receivedAt = SystemClock.elapsedRealtime()
                    for (frame in framer.append(buffer, count)) emit(frame)
                }
            }
        } finally {
            // The call owns release; do not restart recording between ASR utterances or during TTS.
            if (!closed.get() && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
        }
    }.buffer(0).flowOn(Dispatchers.IO)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        fun release(block: () -> Unit) { try { block() } catch (problem: Throwable) {
            if (failure == null) failure = problem else failure!!.addSuppressed(problem)
        } }
        release { if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop() }
        release { echo?.release() }
        release { audioRecord.release() }
        release(onClose)
        failure?.let { throw it }
    }

    companion object {
        fun create(context: Context, health: DuplexAudioHealth = DuplexAudioHealth(),
            onClose: () -> Unit = {}, checkRoute: () -> Unit = {}): AndroidDuplexCapture {
            val app = context.applicationContext
            checkPermission(app)
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Communication microphone is unavailable" }
            val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 5120)).build()
            var effect: AcousticEchoCanceler? = null
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED) { "Communication microphone did not initialize" }
                if (AcousticEchoCanceler.isAvailable()) effect = try { AcousticEchoCanceler.create(record.audioSessionId) }
                    catch (_: RuntimeException) { null } // Real capability remains false; never substitute an RMS gate.
                val enabled = effect?.let { value ->
                    try { value.hasControl() && value.setEnabled(true) == AudioEffect.SUCCESS && value.enabled }
                    catch (_: RuntimeException) { false }
                } ?: false
                return AndroidDuplexCapture(app, record, effect, health, onClose, checkRoute, enabled)
            } catch (failure: Throwable) {
                try { effect?.release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { record.release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        private fun checkPermission(context: Context) {
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission is required for a call"
            }
        }
    }
}
