package com.zhiyu.app.data.voicemessages

import com.zhiyu.app.data.LocalVoiceRules
import com.zhiyu.app.domain.LocalMemoryCodec
import kotlinx.coroutines.CancellationException

/** Bounded deterministic audio derivative; the source text and recorded/synthesized PCM stay intact. */
internal object VoicePcmProsody {
    fun apply(samples: FloatArray, sampleRate: Int, text: String, active: () -> Boolean = { true }): FloatArray {
        require(text.isNotBlank() && text.length <= 120)
        LocalMemoryCodec.bytes(text)
        LocalVoiceRules.checkAudio(samples, sampleRate)
        ensureActive(active)
        val ending = text.lastOrNull { it !in " \t\r\n\"'”’」』）)]】" }
        val contour = when (ending) { '？', '?' -> .025; '。', '.' -> -.025; '！', '!' -> .015; else -> -.008 }
        val tailGain = when (ending) { '？', '?' -> .01; '。', '.' -> -.03; else -> -.01 }
        val last = samples.lastIndex
        if (last == 0) return floatArrayOf(samples[0].coerceIn(-1f, 1f))
        // x²(x-1) is zero at both endpoints. Its derivative limits local varispeed to <2.5%,
        // retaining all words and the exact frame count, with a mild rising/falling phrase tail.
        return FloatArray(samples.size) { index ->
            if (index % 4096 == 0) ensureActive(active)
            val x = index.toDouble() / last
            val position = (index + contour * last * x * x * (x - 1)).coerceIn(0.0, last.toDouble())
            val left = position.toInt()
            val fraction = position - left
            val interpolated = samples[left] * (1 - fraction) + samples[minOf(left + 1, last)] * fraction
            val tail = ((x - .55) / .45).coerceIn(0.0, 1.0)
            val tailEase = tail * tail * (3 - 2 * tail)
            val gain = .96 + .04 * 4 * x * (1 - x) + tailGain * tailEase
            (interpolated * gain).toFloat().coerceIn(-1f, 1f)
        }.also { ensureActive(active) }
    }

    private fun ensureActive(active: () -> Boolean) {
        if (!active()) throw CancellationException("Speech preparation cancelled")
    }
}
