package com.zhiyu.app.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.VoiceTuningSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import java.util.concurrent.atomic.AtomicBoolean

/** Public synthetic speech/sine only; no credentials, microphone, chat or user image access. */
class LocalVoiceTuningNativeTest {
    @Test fun actualModelCatalogMatchesMetadataAndNonPresetTimbresGenerateDifferentSpeech() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LocalVoiceEngine(context).use { engine ->
            assertEquals(174, engine.speakerCount())
            val first = engine.synthesize("我在这里。", 1)
            val last = engine.synthesize("我在这里。", 173)
            LocalVoiceRules.checkAudio(first.samples, first.sampleRate)
            LocalVoiceRules.checkAudio(last.samples, last.sampleRate)
            assertFalse("Two distinct model speakers returned the identical waveform", first.samples.contentEquals(last.samples))
            listOf(-1,174).forEach { id ->
                try { engine.synthesize("我在。", id); fail() } catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun synthesisSpeedChangesDurationAndSameCustomSpeedRemainsDeterministic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LocalVoiceEngine(context).use { engine ->
            val phrase = "我在这里，慢慢说。"
            val normal = engine.synthesize(phrase, 10)
            val slow = engine.synthesize(phrase, 10, VoiceTuningSettings(speed = .75f))
            val fast = engine.synthesize(phrase, 10, VoiceTuningSettings(speed = 1.35f))
            val repeated = engine.synthesize(phrase, 10, VoiceTuningSettings(speed = 1.35f))
            assertTrue("VITS ignored slow speed", slow.samples.size > normal.samples.size)
            assertTrue("VITS ignored fast speed", fast.samples.size < normal.samples.size)
            assertEquals(normal.sampleRate, slow.sampleRate)
            assertEquals(normal.sampleRate, fast.sampleRate)
            assertArrayEquals(fast.samples, repeated.samples, 0f)
        }
    }

    @Test fun pitchConfigurationKeepsSpeedAndSourceRateIndependent() {
        val rate = 22050
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        assertTrue(minimum > 0)
        val output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum * 2, 8192)).build()
        try {
            listOf(-4f, 4f).forEach { pitch ->
                val value = VoiceTuningSettings(speed = .75f, pitchSemitones = pitch)
                LocalVoicePlaybackTuning.apply(output, value)
                assertEquals(1f, output.playbackParams.speed, .0001f)
                assertEquals(value.pitchRatio, output.playbackParams.pitch, .0001f)
                assertEquals(rate, output.sampleRate)
                assertEquals(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL,
                    LocalVoicePlaybackTuning.parameters(value).audioFallbackMode)
            }
        } finally { output.release() }
    }

    @Test fun stoppedCustomPitchPlaybackCannotContinueAndNextPlaybackCanStart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val playback = LocalVoicePlayback(context)
        val rate = 22050
        val sine = LocalVoiceAudio(FloatArray(rate * 2) { (.05 * sin(2 * PI * 440 * it / rate)).toFloat() }, rate)
        val activeLevelSeen = AtomicBoolean(false)
        val task = launch(Dispatchers.Default) {
            playback.play(sine, onLevel = { if (it > 0f) activeLevelSeen.set(true) }, volume = 0f, pitchSemitones = 2f)
        }
        try {
            withTimeout(4000) { while (!activeLevelSeen.get() && task.isActive) delay(10) }
            assertTrue("Muted playback never accepted synthetic frames", activeLevelSeen.get())
            playback.stop()
            withTimeout(2000) { task.join() }
            assertTrue(task.isCancelled)
            playback.play(LocalVoiceAudio(FloatArray(rate / 5), rate), volume = 0f, pitchSemitones = -2f)
        } finally { task.cancelAndJoin(); playback.stop() }
    }
}
