package com.zhiyu.app.data

import android.content.Context
import android.media.*
import android.os.SystemClock
import com.zhiyu.app.domain.ApiFailure
import com.zhiyu.app.domain.VoiceTuningSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** Streamed PCM playback with local audio focus. Never changes the user's system volume. */
class LocalVoicePlayback(context: Context) {
    private val audioManager = requireNotNull(context.applicationContext.getSystemService(AudioManager::class.java)) { "声音播放服务不可用" }
    private val track = AtomicReference<AudioTrack?>()
    private val lostFocus = AtomicBoolean(false)
    private val stopVersion = AtomicLong(0L)

    suspend fun play(audio: LocalVoiceAudio, onLevel: (Float) -> Unit = {}, volume: Float = 1f,
        pitchSemitones: Float = 0f) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val token = stopVersion.get()
        val tuning = VoiceTuningSettings(volume = volume, pitchSemitones = pitchSemitones)
        LocalVoiceRules.checkAudio(audio.samples, audio.sampleRate)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = focusRequest(attributes)
        lostFocus.set(false)
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw ApiFailure(0, "声音暂时被其他应用占用", "voice_audio_focus_unavailable")
        }
        var created: AudioTrack? = null
        try {
            created = createTrack(audio.sampleRate, attributes)
            require(created.state == AudioTrack.STATE_INITIALIZED)
            check(track.compareAndSet(null, created)) { "已经在播放声音" }
            created.setVolume(volume)
            LocalVoicePlaybackTuning.apply(created, tuning)
            checkPlaying(Long.MAX_VALUE, token)
            created.play()
            writeAndDrain(created, audio, onLevel, token)
        } finally {
            try { onLevel(0f) } finally {
                try { created?.let(::releaseTrack) }
                finally { audioManager.abandonAudioFocusRequest(focus) }
            }
        }
    }

    private fun focusRequest(attributes: AudioAttributes) = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change in setOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) { lostFocus.set(true); stop() }
        }.build()

    private fun createTrack(rate: Int, attributes: AudioAttributes): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "这台设备暂时无法播放声音" }
        return AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum * 2, 8192)).build()
    }

    private suspend fun writeAndDrain(output: AudioTrack, audio: LocalVoiceAudio, onLevel: (Float) -> Unit, token: Long) {
        val deadline = SystemClock.elapsedRealtime() + audio.samples.size.toLong() * 1000 / audio.sampleRate + 10_000L
        var offset = 0
        while (offset < audio.samples.size) {
            checkPlaying(deadline, token)
            val end = minOf(audio.samples.size, offset + 1024)
            val part = ShortArray(end - offset) { (audio.samples[offset + it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
            val written = output.write(part, 0, part.size, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) throw ApiFailure(0, "声音播放暂时中断", "voice_playback_failed")
            if (written == 0) { delay(10); continue }
            require(written <= part.size)
            val energy = audio.samples.sliceArray(offset until offset + written).sumOf { it.toDouble() * it }
            onLevel((sqrt(energy / written) * 5).toFloat().coerceIn(0f, 1f))
            offset += written
        }
        // Wait for queued frames to finish; do not cut off the last syllable.
        while (output.playbackHeadPosition.toLong() < audio.samples.size.toLong()) { checkPlaying(deadline, token); delay(15) }
    }

    private suspend fun checkPlaying(deadline: Long, token: Long) {
        currentCoroutineContext().ensureActive()
        if (SystemClock.elapsedRealtime() > deadline) throw ApiFailure(504, "声音播放暂时没有完成，可以重新听", "voice_playback_timeout")
        if (stopVersion.get() != token && !lostFocus.get()) throw kotlinx.coroutines.CancellationException()
        if (lostFocus.get()) throw ApiFailure(0, "播放被其他声音打断，可以重新听", "voice_audio_focus_lost")
    }

    private fun releaseTrack(output: AudioTrack) {
        track.compareAndSet(output, null)
        try { if (output.playState == AudioTrack.PLAYSTATE_PLAYING) output.stop() }
        finally { output.release() }
    }

    fun stop() {
        stopVersion.incrementAndGet()
        try { track.get()?.let { it.pause(); it.flush() } }
        catch (_: IllegalStateException) { /* Playback finally owns release; concurrent stop is idempotent. */ }
    }
}
