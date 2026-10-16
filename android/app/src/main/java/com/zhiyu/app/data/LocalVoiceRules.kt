package com.zhiyu.app.data

import com.zhiyu.app.domain.LocalMemoryCodec
import kotlin.math.floor

data class LocalVoiceChoice(val id: Int, val name: String)

object LocalVoiceRules {
    const val SPEAKER_COUNT = 174
    const val ASR_SAMPLE_RATE = 16000
    const val MAX_RECORD_SECONDS = 120
    const val MAX_SPOKEN_CHARS = 128000
    private const val CHUNK_CHARS = 120
    val choices = listOf(LocalVoiceChoice(10, "声音一"), LocalVoiceChoice(33, "声音二"), LocalVoiceChoice(0, "声音三"))
    fun voice(id: Int): LocalVoiceChoice {
        require(id in 0 until SPEAKER_COUNT) { "请选择有效的内置音色" }
        return choices.firstOrNull { it.id == id } ?: LocalVoiceChoice(id, "音色 ${id + 1}")
    }
    /** Retain the third-choice slot when upgrading superseded trial voices; other choices stay fixed. */
    fun storedVoice(id: Int): Int = voice(if (id == 99 || id == 2) 0 else id).id

    /** Only a speech derivative is split. Full original chat text is never edited or truncated. */
    fun speechChunks(text: String): List<String> {
        require(text.isNotBlank() && text.length <= MAX_SPOKEN_CHARS) { "这段话暂时无法朗读，聊天原文仍完整保留" }
        LocalMemoryCodec.bytes(text)
        val results = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + CHUNK_CHARS)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            if (end < text.length) {
                val boundary = (end - 1 downTo start + CHUNK_CHARS / 3).firstOrNull {
                    text[it] in "。！？；，.!?;,\n"
                }
                if (boundary != null) end = boundary + 1
            }
            results.add(text.substring(start, end))
            start = end
        }
        return results.toList()
    }

    fun checkAudio(samples: FloatArray, rate: Int, maximumSeconds: Int = 90) {
        require(maximumSeconds in 1..180)
        require(rate in 8000..48000 && samples.size in 1..rate * maximumSeconds && samples.all { it.isFinite() && it in -1.0001f..1.0001f }) {
            "本机语音没有生成有效音频"
        }
    }

    fun resample(samples: FloatArray, sourceRate: Int, targetRate: Int = ASR_SAMPLE_RATE): FloatArray {
        require(sourceRate in 8000..48000 && targetRate in 8000..48000 && samples.size in 1..sourceRate * MAX_RECORD_SECONDS &&
            samples.all { it.isFinite() && it in -1.0001f..1.0001f }) { "语音采样数据无效" }
        if (sourceRate == targetRate) return samples.copyOf()
        val length = (samples.size.toLong() * targetRate / sourceRate).toInt().coerceAtLeast(1)
        return FloatArray(length) { index ->
            val position = index.toDouble() * sourceRate / targetRate
            val left = floor(position).toInt().coerceAtMost(samples.lastIndex)
            val right = minOf(left + 1, samples.lastIndex)
            val fraction = (position - left).toFloat()
            samples[left] * (1f - fraction) + samples[right] * fraction
        }
    }
}
