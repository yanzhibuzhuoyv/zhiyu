package com.zhiyu.app.data

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import com.zhiyu.app.data.voicemessages.VoicePcmProsody
import com.zhiyu.app.data.voicemessages.VoiceWavCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in synthetic A/B listening samples, excluded from the ordinary release suite. */
class VoiceProsodySampleNativeTest {
    @Test fun exportBundledVoiceBeforeAndAfterTheActualSynthesisBoundary() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Run explicitly with -e voice_prosody_sample true", args.getString("voice_prosody_sample") == "true")
        val voice = args.getString("voice_prosody_speaker", "0").toInt().also { LocalVoiceRules.voice(it) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phrases = listOf("你今天过得好吗？", "我就在这里，慢慢说。", "原来是这样，我还想听你说。")
        val directory = File(context.cacheDir.canonicalFile, "voice-prosody-sample-v1")
        require(directory.canonicalFile == directory && directory.parentFile == context.cacheDir.canonicalFile)
        check(directory.isDirectory || directory.mkdir())
        val baseline = mutableListOf<LocalVoiceAudio>()
        val root = "voice/vits-icefall-zh-aishell3"
        val model = OfflineTts(context.assets, OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(model = "$root/model.onnx",
                lexicon = "$root/lexicon.txt", tokens = "$root/tokens.txt", noiseScale = 0f, noiseScaleW = 0f,
                lengthScale = 1f), numThreads = 2, provider = "cpu", debug = false),
            ruleFsts = "$root/phone.fst,$root/date.fst,$root/number.fst", ruleFars = "", maxNumSentences = 1))
        try {
            phrases.forEach { text ->
                val raw = model.generateWithCallback(text, voice, 1f, LocalVoiceTtsCallback { true })
                baseline.add(LocalVoiceAudio(raw.samples, raw.sampleRate))
            }
        } finally { model.release() }
        val manifest = JSONArray()
        LocalVoiceEngine(context).use { engine ->
            phrases.forEachIndexed { index, text ->
                val original = baseline[index]
                val actual = engine.synthesize(text, voice)
                assertEquals(original.sampleRate, actual.sampleRate)
                assertEquals("No words or end frames may be cut off", original.samples.size, actual.samples.size)
                assertFalse("Engine did not use the cadence derivative", original.samples.contentEquals(actual.samples))
                assertArrayEquals(VoicePcmProsody.apply(original.samples, original.sampleRate, text), actual.samples, 0f)
                val before = write(directory, "speaker-$voice-$index-before.wav", original)
                val after = write(directory, "speaker-$voice-$index-after.wav", actual)
                manifest.put(JSONObject().put("text", text).put("speaker", voice).put("rate", actual.sampleRate)
                    .put("frames", actual.samples.size).put("before", before.first).put("before_sha256", before.second)
                    .put("after", after.first).put("after_sha256", after.second))
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("voice_prosody_sample", manifest.toString())
        })
    }

    private fun write(directory: File, name: String, audio: LocalVoiceAudio): Pair<String, String> {
        val target = File(directory, name)
        require(target.canonicalFile == target && target.parentFile == directory)
        val bytes = VoiceWavCodec.encode(audio.samples, audio.sampleRate, 90)
        target.writeBytes(bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return target.absolutePath to sha
    }
}
