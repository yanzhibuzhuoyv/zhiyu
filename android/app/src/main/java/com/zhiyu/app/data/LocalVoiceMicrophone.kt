package com.zhiyu.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.zhiyu.app.domain.ApiFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Foreground turn recording only. Samples feed ASR in memory and are never written to disk. */
class LocalVoiceMicrophone(context: Context) {
    private val app = context.applicationContext
    private val record = AtomicReference<AudioRecord?>()
    private val finished = AtomicBoolean(false)

    suspend fun capture(session: LocalVoiceRecognition, onStarted: () -> Unit,
        onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        require(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "请允许麦克风后再说话" }
        val minimum = AudioRecord.getMinBufferSize(LocalVoiceRules.ASR_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw ApiFailure(0, "这台设备暂时无法录音", "voice_microphone_unavailable")
        currentCoroutineContext().ensureActive()
        val created = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(LocalVoiceRules.ASR_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 8192)).build()
        if (!record.compareAndSet(null, created)) { created.release(); throw IllegalStateException("已经在听你说话") }
        finished.set(false)
        try {
            require(created.state == AudioRecord.STATE_INITIALIZED) { "麦克风没有准备好，请重新试试" }
            currentCoroutineContext().ensureActive()
            created.startRecording()
            require(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "录音未开始，请检查麦克风权限" }
            onStarted()
            readTurn(created, session, onPartial)
            currentCoroutineContext().ensureActive()
            session.finish().also { if (it.isBlank()) throw ApiFailure(422, "刚才没听清，再说一次好不好", "voice_no_speech") }
        } finally {
            record.compareAndSet(created, null)
            try { if (created.recordingState == AudioRecord.RECORDSTATE_RECORDING) created.stop() }
            finally { created.release() }
        }
    }

    private suspend fun readTurn(input: AudioRecord, session: LocalVoiceRecognition, onPartial: (String) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + LocalVoiceRules.MAX_RECORD_SECONDS * 1000L
        val buffer = ShortArray(1600)
        var count = 0
        var lastText = ""
        while (!finished.get() && SystemClock.elapsedRealtime() < deadline &&
            count < LocalVoiceRules.ASR_SAMPLE_RATE * LocalVoiceRules.MAX_RECORD_SECONDS) {
            currentCoroutineContext().ensureActive()
            val received = input.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            if (received < 0) {
                if (finished.get()) break
                throw ApiFailure(0, "录音暂时中断，请重新说一次", "voice_microphone_failed")
            }
            if (received == 0) { delay(10); continue }
            count += received
            val text = session.accept(FloatArray(received) { buffer[it] / 32768f })
            if (text != lastText) { onPartial(text); lastText = text }
            if (text.isNotBlank() && session.endpoint()) break
        }
    }

    fun finishTurn() {
        finished.set(true)
        try { record.get()?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } }
        catch (_: IllegalStateException) { /* The capture finally block owns release after cancellation. */ }
    }
}
