package com.zhiyu.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import com.zhiyu.app.domain.ApiFailure
import java.util.concurrent.atomic.AtomicReference

/** Hardware-only capture; model initialization must not be required to construct this input. */
internal class AndroidVoiceCaptureInput(context: Context) : VoiceCaptureInput {
    private val app = context.applicationContext ?: context
    private val record = AtomicReference<AudioRecord?>()
    private var suppression: NoiseSuppressor? = null
    private var echo: AcousticEchoCanceler? = null
    override fun start() {
        require(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "请允许麦克风后再说话" }
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw ApiFailure(0, "这台设备暂时无法录音", "voice_microphone_unavailable")
        val created = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(minimum * 2, 8192)).build()
        if (!record.compareAndSet(null, created)) { created.release(); error("已经在听你说话") }
        require(created.state == AudioRecord.STATE_INITIALIZED) { "麦克风没有准备好，请重新试试" }
        suppression = try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(created.audioSessionId) else null
        } catch (_: RuntimeException) { null }
        echo = try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(created.audioSessionId) else null
        } catch (_: RuntimeException) { null }
        val enabled = suppression?.let {
            try { it.hasControl() && it.setEnabled(true) == AudioEffect.SUCCESS && it.enabled }
            catch (_: RuntimeException) { false }
        } ?: false
        val echoEnabled = echo?.let {
            try { it.hasControl() && it.setEnabled(true) == AudioEffect.SUCCESS && it.enabled }
            catch (_: RuntimeException) { false }
        } ?: false
        android.util.Log.d("ZhiYuVoice", "Recording effects: ns=$enabled aec=$echoEnabled")
        created.startRecording()
        require(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "录音未开始，请检查麦克风权限" }
    }
    override fun read(maximumFrames: Int): FloatArray {
        require(maximumFrames in 1..1600)
        val pcm = ShortArray(maximumFrames)
        val count = checkNotNull(record.get()).read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
        if (count < 0) throw ApiFailure(0, "录音暂时中断，请重新说一次", "voice_microphone_failed")
        return FloatArray(count) { pcm[it] / 32768f }
    }
    override fun stop() {
        try { record.get()?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } }
        catch (_: IllegalStateException) { /* Capture finally owns release. */ }
    }
    override fun close() {
        val current = record.getAndSet(null) ?: return
        try { if (current.recordingState == AudioRecord.RECORDSTATE_RECORDING) current.stop() }
        finally {
            try {
            try { echo?.release() }
            finally { echo = null }
        } finally {
            try { suppression?.release() }
            finally { suppression = null; current.release() }
        }
        }
    }
}
