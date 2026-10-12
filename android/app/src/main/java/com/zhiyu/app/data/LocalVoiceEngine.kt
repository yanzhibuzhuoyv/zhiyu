package com.zhiyu.app.data

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.VoiceTuningSettings

data class LocalVoiceAudio(val samples: FloatArray, val sampleRate: Int)

/** Bundled CPU-only recognition and synthesis. Calls are serialized; release never races native inference. */
class LocalVoiceEngine(context: Context) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val lock = Any()
    private var recognizer: OnlineRecognizer? = null
    private var tts: OfflineTts? = null
    private val sessions = mutableSetOf<LocalVoiceRecognition>()
    private var closed = false
    companion object {
        private const val ASR = "voice/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"
        private const val TTS = "voice/vits-icefall-zh-aishell3"
    }

    fun openRecognition(): LocalVoiceRecognition = synchronized(lock) {
        check(!closed) { "语音会话已结束，请重新打开" }
        val model = recognizer ?: OnlineRecognizer(assets, OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = LocalVoiceRules.ASR_SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
                // Match official getModelConfig(9): quantized encoder/joiner, full-precision decoder.
                encoder = "$ASR/encoder-epoch-99-avg-1.int8.onnx", decoder = "$ASR/decoder-epoch-99-avg-1.onnx",
                joiner = "$ASR/joiner-epoch-99-avg-1.int8.onnx"), tokens = "$ASR/tokens.txt",
                numThreads = 2, provider = "cpu", modelType = "zipformer", debug = false),
            enableEndpoint = true, decodingMethod = "greedy_search",
        )).also { recognizer = it }
        LocalVoiceRecognition(model, model.createStream(), lock) { sessions.remove(it) }.also { sessions.add(it) }
    }

    fun synthesize(text: String, voiceId: Int, active: () -> Boolean = { true }): LocalVoiceAudio =
        synthesize(text, voiceId, VoiceTuningSettings(), active)

    fun synthesize(text: String, voiceId: Int, tuning: VoiceTuningSettings,
        active: () -> Boolean = { true }): LocalVoiceAudio = synchronized(lock) {
        check(!closed) { "语音会话已结束，请重新打开" }
        if (!active()) throw kotlinx.coroutines.CancellationException()
        LocalVoiceRules.voice(voiceId)
        require(text.isNotBlank() && text.length <= 120)
        LocalMemoryCodec.bytes(text)
        val model = synthesisModel()
        require(voiceId < model.numSpeakers()) { "内置模型没有这个声音" }
        val audio = model.generateWithCallback(text, voiceId, tuning.speed, LocalVoiceTtsCallback(active))
        if (!active()) throw kotlinx.coroutines.CancellationException()
        LocalVoiceRules.checkAudio(audio.samples, audio.sampleRate)
        LocalVoiceAudio(audio.samples, audio.sampleRate)
    }

    fun speakerCount(): Int = synchronized(lock) {
        check(!closed) { "语音会话已结束，请重新打开" }
        synthesisModel().numSpeakers()
    }

    private fun synthesisModel(): OfflineTts = tts ?: OfflineTts(assets, OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(model = "$TTS/model.onnx",
                lexicon = "$TTS/lexicon.txt", tokens = "$TTS/tokens.txt",
                // Disable stochastic acoustics/durations so identical text and voice can remain stable.
                noiseScale = 0f, noiseScaleW = 0f, lengthScale = 1f), numThreads = 2,
                provider = "cpu", debug = false),
            ruleFsts = "$TTS/phone.fst,$TTS/date.fst,$TTS/number.fst", ruleFars = "", maxNumSentences = 1,
        )).also { model ->
            if (model.numSpeakers() != LocalVoiceRules.SPEAKER_COUNT) {
                model.release()
                error("内置音色数量与声音库不一致，请使用完整安装包")
            }
            tts = model
        }

    fun recognizePcm(samples: FloatArray, sampleRate: Int): String {
        val normalized = LocalVoiceRules.resample(samples, sampleRate)
        return openRecognition().use { session ->
            var offset = 0
            while (offset < normalized.size) {
                val end = minOf(normalized.size, offset + 3200)
                session.accept(normalized.copyOfRange(offset, end))
                offset = end
            }
            session.finish()
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            sessions.toList().forEach { it.close() }
            tts?.release(); tts = null
            recognizer?.release(); recognizer = null
            closed = true
        }
    }
}

class LocalVoiceRecognition internal constructor(private val recognizer: OnlineRecognizer,
    private val stream: OnlineStream, private val lock: Any,
    private val onClose: (LocalVoiceRecognition) -> Unit) : AutoCloseable {
    private var closed = false
    private var finished = false
    fun accept(samples: FloatArray): String = synchronized(lock) {
        check(!closed && !finished)
        require(samples.size in 1..16000 && samples.all { it.isFinite() && it in -1.0001f..1.0001f })
        stream.acceptWaveform(samples, LocalVoiceRules.ASR_SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        recognizer.getResult(stream).text
    }
    fun endpoint(): Boolean = synchronized(lock) { check(!closed && !finished); recognizer.isEndpoint(stream) }
    fun finish(): String = synchronized(lock) {
        check(!closed)
        if (finished) return@synchronized recognizer.getResult(stream).text
        stream.acceptWaveform(FloatArray(LocalVoiceRules.ASR_SAMPLE_RATE / 2), LocalVoiceRules.ASR_SAMPLE_RATE)
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        finished = true
        recognizer.getResult(stream).text.also { LocalMemoryCodec.bytes(it) }
    }
    override fun close() = synchronized(lock) { if (!closed) { stream.release(); closed = true; onClose(this) } }
}
