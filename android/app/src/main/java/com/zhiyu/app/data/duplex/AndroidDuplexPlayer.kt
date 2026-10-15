package com.zhiyu.app.data.duplex

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.zhiyu.app.data.LocalVoicePlaybackTuning
import com.zhiyu.app.domain.VoiceTuningSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import rig2d.voice.VoicePcm
import rig2d.voice.VoicePlayer
import rig2d.voice.VoiceStamp
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

internal object DuplexAudioTrackFactory {
    fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    fun create(rate: Int): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Communication playback is unavailable" }
        return AudioTrack.Builder().setAudioAttributes(attributes()).setAudioFormat(AudioFormat.Builder()
            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum * 2, 8192)).build().also { output ->
                if (output.state != AudioTrack.STATE_INITIALIZED) {
                    output.release(); error("Communication playback did not initialize")
                }
            }
    }
}

/** One track per bounded sentence. The call session, not this class, owns focus and routing. */
internal class AndroidDuplexPlayer(private val health: DuplexAudioHealth,
    private val tuning: VoiceTuningSettings) : VoicePlayer {
    private val closed = AtomicBoolean(false)
    private val tracks = DuplexPlaybackOwner<AudioTrack> { output -> output.pause(); output.flush() }

    init { health.onFailure { stop(null) } }

    override suspend fun play(audio: VoicePcm, stamp: VoiceStamp, onLevel: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive(); health.check()
            check(!closed.get())
            val output = DuplexAudioTrackFactory.create(audio.sampleRate)
            try {
                tracks.attach(stamp, output)
                output.setVolume(tuning.volume)
                LocalVoicePlaybackTuning.apply(output, tuning)
                checkActive(stamp, output, Long.MAX_VALUE)
                if (!tracks.runIfActive(stamp, output) { output.play() }) throw CancellationException("Stale audio start")
                writeAndDrain(output, audio, stamp, onLevel)
            } finally {
                try { onLevel(0f) } finally {
                    tracks.detach(output)
                    try { output.pause(); output.flush() } finally { output.release() }
                }
            }
        }

    private suspend fun writeAndDrain(output: AudioTrack, audio: VoicePcm, stamp: VoiceStamp,
        onLevel: (Float) -> Unit) {
        val samples = audio.samplesCopy()
        val deadline = SystemClock.elapsedRealtime() + audio.durationMillis + 10000
        var offset = 0
        while (offset < samples.size) {
            checkActive(stamp, output, deadline)
            val size = minOf(1024, samples.size - offset)
            val part = ShortArray(size) { (samples[offset + it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
            val written = output.write(part, 0, size, AudioTrack.WRITE_NON_BLOCKING)
            check(written in 0..size) { "Communication audio write failed" }
            if (written == 0) { delay(5); continue }
            var energy = 0.0
            for (index in offset until offset + written) energy += samples[index].toDouble() * samples[index]
            if (tracks.active(stamp, output)) onLevel((sqrt(energy / written) * 5).toFloat().coerceIn(0f, 1f))
            offset += written
        }
        // This position measures actual consumed frames, not frames merely submitted to AudioTrack.
        while ((output.playbackHeadPosition.toLong() and 0xffffffffL) < samples.size) {
            checkActive(stamp, output, deadline); delay(10)
        }
        checkActive(stamp, output, deadline)
    }

    private suspend fun checkActive(stamp: VoiceStamp, output: AudioTrack, deadline: Long) {
        currentCoroutineContext().ensureActive(); health.check()
        if (closed.get() || !tracks.active(stamp, output)) throw CancellationException("Stale audio track")
        check(SystemClock.elapsedRealtime() <= deadline) { "Communication audio did not finish" }
    }

    override fun stop(stamp: VoiceStamp?) = tracks.stop(stamp)
    override fun close() { if (closed.compareAndSet(false, true)) stop(null) }
}
