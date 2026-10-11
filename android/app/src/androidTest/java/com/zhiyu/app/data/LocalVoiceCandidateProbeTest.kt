package com.zhiyu.app.data

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File

/** Opt-in model quality research only. Normal release regression does not run this probe. */
class LocalVoiceCandidateProbeTest {
    @Test fun reportCandidateSpeechQualityAcrossRepeatedSyntheticTurns() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Run explicitly with -e voice_quality_probe true",
            arguments.getString("voice_quality_probe") == "true")
        val candidates = arguments.getString("voice_candidates", "0,1,2,8,20,60").split(",").map { it.toInt() }
        require(candidates.size in 1..12 && candidates.distinct().size == candidates.size && candidates.all { it in 0..173 })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "voice/vits-icefall-zh-aishell3"
        val tts = OfflineTts(context.assets, OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
                model = "$directory/model.onnx", lexicon = "$directory/lexicon.txt", tokens = "$directory/tokens.txt",
                noiseScale = 0f, noiseScaleW = 0f, lengthScale = 1f),
                numThreads = 2, provider = "cpu", debug = false),
            ruleFsts = "$directory/phone.fst,$directory/date.fst,$directory/number.fst", ruleFars = "", maxNumSentences = 1))
        val phrases = when (arguments.getString("voice_phrase_set", "greeting")) {
            "greeting" -> listOf("你好，今天的天气真好。")
            "varied" -> listOf("你吃晚饭了吗，记得喝点水。", "我今天在公园散步，看见了一只小猫。", "今晚早点休息，我会陪着你。")
            else -> error("Unknown synthetic phrase set")
        }
        val results = JSONArray()
        try {
            LocalVoiceEngine(context).use { engine ->
                candidates.forEach { voice ->
                    require(voice < tts.numSpeakers())
                    phrases.forEachIndexed { phraseIndex, phrase ->
                    val expected = phrase.filter { it in '\u4e00'..'\u9fff' }
                    repeat(3) { round ->
                        val audio = tts.generateWithCallback(phrase, voice, 1f, LocalVoiceTtsCallback { true })
                        LocalVoiceRules.checkAudio(audio.samples, audio.sampleRate)
                        val actual = engine.recognizePcm(audio.samples, audio.sampleRate)
                        val normalized = actual.filter { it in '\u4e00'..'\u9fff' }
                        val wave = File(context.cacheDir, "voice-test-candidate-$voice-$phraseIndex-$round.wav")
                        wave.writeBytes(wav(audio.samples, audio.sampleRate))
                        val row = JSONObject().put("voice", voice).put("round", round + 1)
                            .put("expected", phrase).put("actual", actual)
                            .put("character_error_rate", distance(expected, normalized).toDouble() / expected.length)
                            .put("recognizable", if (phrase.startsWith("你好"))
                                actual.contains("你好") || actual.contains("天气") else JSONObject.NULL)
                            .put("sample_rate", audio.sampleRate).put("frames", audio.samples.size)
                            .put("peak", audio.samples.maxOf { kotlin.math.abs(it) }).put("wav", wave.absolutePath)
                        results.put(row)
                        status("voice_candidate_turn", row.toString())
                    }
                    }
                }
            }
        } finally { tts.release() }
        status("voice_candidate_quality_summary", results.toString())
        // Candidate selection uses this report; ordinary release acceptance keeps its stricter ten-turn assertions.
    }

    private fun status(key: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })
    }

    private fun wav(samples: FloatArray, rate: Int): ByteArray {
        val size = samples.size * 2
        val wave = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + size)
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        wave.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray(Charsets.US_ASCII)).putInt(size)
        samples.forEach { wave.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        return wave.array()
    }

    private fun distance(expected: String, actual: String): Int {
        var previous = IntArray(actual.length + 1) { it }
        expected.forEachIndexed { i, char ->
            val current = IntArray(actual.length + 1).also { it[0] = i + 1 }
            actual.forEachIndexed { j, other ->
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (char == other) 0 else 1)
            }
            previous = current
        }
        return previous.last()
    }
}
