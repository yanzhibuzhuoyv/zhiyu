package com.zhiyu.app.data

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Synthetic-only regression: repeated synthesis must not randomly alter the same voice's waveform. */
@RunWith(AndroidJUnit4::class)
class LocalVoiceSynthesisStabilityNativeTest {
    @Test fun sameTextAndVoiceRepeatWithoutRandomWaveformOrDurationChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phrase = "你好，今天的天气真好。"
        val rows = JSONArray()
        val identities = LocalVoiceEngine(context).use { engine ->
            LocalVoiceRules.choices.map { voice ->
                val repeats = (0 until 3).map { round ->
                    val audio = engine.synthesize(phrase, voice.id)
                    LocalVoiceRules.checkAudio(audio.samples, audio.sampleRate)
                    val identity = AudioIdentity(audio.sampleRate, audio.samples.size, digest(audio.samples))
                    rows.put(JSONObject().put("voice", voice.id).put("round", round + 1)
                        .put("sample_rate", identity.sampleRate).put("frames", identity.frames)
                        .put("sha256", identity.sha256).put("peak", audio.samples.maxOf { kotlin.math.abs(it) }))
                    identity
                }
                voice.id to repeats
            }
        }
        val diagnostics = rows.toString()
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("voice_synthesis_stability", diagnostics)
        })
        // Assert after every voice has produced diagnostics, so a first failure does not hide the others.
        identities.forEach { (voice, repeats) ->
            assertEquals("Voice $voice changed waveform or duration for identical text; $diagnostics",
                1, repeats.distinct().size)
        }
        assertTrue("Distinct choices must retain distinct voices; $diagnostics",
            identities.map { (_, repeats) -> repeats.first().sha256 }.distinct().size == 3)
    }

    private data class AudioIdentity(val sampleRate: Int, val frames: Int, val sha256: String)

    private fun digest(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
}
