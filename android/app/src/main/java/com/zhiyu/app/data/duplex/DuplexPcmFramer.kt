package com.zhiyu.app.data.duplex

import rig2d.voice.VoicePcm

/** One bounded partial frame; never emits sub-frame silence that would distort endpoint timing. */
internal class DuplexPcmFramer {
    private val partial = ShortArray(640)
    private var size = 0

    fun append(samples: ShortArray, count: Int): List<VoicePcm> {
        require(count in 0..samples.size && count <= 16000) { "Invalid bounded microphone read" }
        val frames = mutableListOf<VoicePcm>()
        var offset = 0
        while (offset < count) {
            val copied = minOf(partial.size - size, count - offset)
            samples.copyInto(partial, size, offset, offset + copied)
            offset += copied; size += copied
            if (size == partial.size) {
                frames += VoicePcm(FloatArray(partial.size) { partial[it] / 32768f }, 16000)
                size = 0
            }
        }
        return frames
    }
}
