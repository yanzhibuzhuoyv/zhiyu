package com.zhiyu.app.data

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.Bundle
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class LocalVoiceNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun threeBundledVoicesGenerateDistinctUsableSpeechWithNoNetworkOrKey() {
        LocalVoiceEngine(context).use { engine ->
            val audios = LocalVoiceRules.choices.map { choice -> engine.synthesize("你好，我在这里。", choice.id) }
            audios.forEach { audio ->
                assertTrue(audio.samples.size > audio.sampleRate / 2)
                assertTrue(audio.samples.any { kotlin.math.abs(it) > 0.01f })
                LocalVoiceRules.checkAudio(audio.samples, audio.sampleRate)
            }
            assertEquals(3, audios.map { digest(it.samples) }.distinct().size)
        }
    }

    @Test fun localGeneratedChineseAudioFeedsRealInt8StreamingRecognitionAndEngineCanReopen() {
        val generated = LocalVoiceEngine(context).use { it.synthesize("你好，今天的天气真好。", 10) }
        val recognized = LocalVoiceEngine(context).use { it.recognizePcm(generated.samples, generated.sampleRate) }
        saveSyntheticAudio(generated, recognized)
        assertTrue("Bundled Chinese ASR returned '$recognized' from ${generated.samples.size} frames @ ${generated.sampleRate}", recognized.isNotBlank())
        assertTrue("Simple greeting should remain recognizable; actual='$recognized'", recognized.contains("你好") || recognized.contains("天气"))
        val engine = LocalVoiceEngine(context)
        val session = engine.openRecognition()
        engine.close() // Also releases an outstanding stream; no inference can use a freed model.
        engine.close()
        try { session.accept(floatArrayOf(0f)); fail() } catch (_: IllegalStateException) { }
        session.close()
    }

    @Test fun realAudioTrackPlaysSilentlyWithoutChangingDeviceVolume() = runBlocking {
        val audio = LocalVoiceEngine(context).use { it.synthesize("我在。", 33) }
        val player = LocalVoicePlayback(context)
        var callbacks = 0
        player.play(audio, onLevel = { callbacks++ }, volume = 0f)
        assertTrue(callbacks > 1)
        player.stop()
    }

    @Test fun cancelledNativeSynthesisReturnsControlAndNextReplyStillGenerates() {
        LocalVoiceEngine(context).use { engine ->
            try {
                engine.synthesize("这句话应该可以及时停下来。", 10) { false }
                fail("Cancelled native synthesis must not be returned as usable speech")
            } catch (_: CancellationException) { }
            val next = engine.synthesize("我在。", 10)
            assertTrue(next.samples.isNotEmpty())
            LocalVoiceRules.checkAudio(next.samples, next.sampleRate)
        }
    }

    @Test fun tenRepeatedSyntheticTurnsPreserveSpeechContentAcrossAllThreeVoices() {
        val phrase = "你好，今天的天气真好。"
        val expected = phrase.filter { it in '\u4e00'..'\u9fff' }
        val results = LocalVoiceEngine(context).use { engine ->
            (0 until 10).map { index ->
                val voice = LocalVoiceRules.choices[index % LocalVoiceRules.choices.size].id
                val audio = engine.synthesize(phrase, voice)
                val actual = engine.recognizePcm(audio.samples, audio.sampleRate)
                val normalized = actual.filter { it in '\u4e00'..'\u9fff' }
                val cer = editDistance(expected, normalized).toDouble() / expected.length
                saveSyntheticAudio(audio, actual, "voice-test-probe-$index-$voice.wav")
                JSONObject().put("turn", index + 1).put("voice", voice).put("expected", phrase)
                    .put("actual", actual).put("character_error_rate", cer)
                    .put("recognizable", actual.contains("你好") || actual.contains("天气"))
            }
        }
        val diagnostics = JSONArray(results).toString()
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("voice_asr_ten_turn_probe", diagnostics)
        })
        val successes = results.count { it.getBoolean("recognizable") }
        val meanCer = results.sumOf { it.getDouble("character_error_rate") } / results.size
        assertTrue("At least 8/10 turns must retain the greeting or weather; $diagnostics", successes >= 8)
        assertTrue("Average character error rate must be <=35%; $diagnostics", meanCer <= 0.35)
        LocalVoiceRules.choices.forEach { voice ->
            assertTrue("Every selected voice must retain recognizable speech; $diagnostics",
                results.any { it.getInt("voice") == voice.id && it.getBoolean("recognizable") })
        }
    }

    private fun digest(samples: FloatArray): String {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
    }

    private fun saveSyntheticAudio(audio: LocalVoiceAudio, text: String, name: String = "voice-test-synthetic.wav") {
        // This fixture is entirely synthetic, never microphone audio or a real user's text.
        val out = File(context.cacheDir, name)
        val dataBytes = audio.samples.size * 2
        val wave = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
        wave.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        wave.putShort(1).putShort(1).putInt(audio.sampleRate).putInt(audio.sampleRate * 2)
        wave.putShort(2).putShort(16).put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        audio.samples.forEach { wave.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        out.writeBytes(wave.array())
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("voice_asr_diagnostic", "actual='$text'; frames=${audio.samples.size}; sample_rate=${audio.sampleRate}; " +
                "peak=${audio.samples.maxOf { kotlin.math.abs(it) }}; sha256=${digest(audio.samples)}; wav=${out.absolutePath}")
        })
    }

    private fun editDistance(expected: String, actual: String): Int {
        var previous = IntArray(actual.length + 1) { it }
        expected.forEachIndexed { index, character ->
            val current = IntArray(actual.length + 1)
            current[0] = index + 1
            actual.forEachIndexed { j, other ->
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1,
                    previous[j] + if (character == other) 0 else 1)
            }
            previous = current
        }
        return previous.last()
    }
}
