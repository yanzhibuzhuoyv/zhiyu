package com.zhiyu.app.data.voicemessages

import com.zhiyu.app.data.LocalVoiceAudio
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Canonical PCM16 mono WAV only. No decoder, executable payload or unbounded allocation. */
internal object VoiceWavCodec {
    const val MAX_SECONDS = 180
    const val MAX_BYTES = 44 + 48000 * MAX_SECONDS * 2
    fun validate(samples: FloatArray, rate: Int, seconds: Int) {
        require(rate in 8000..48000 && seconds in 1..MAX_SECONDS && samples.size in 1..rate * seconds) { "语音长度或采样率无效" }
        require(samples.all { it.isFinite() && it in -1f..1f }) { "语音采样数据无效" }
    }
    fun encode(samples: FloatArray, rate: Int, seconds: Int): ByteArray {
        validate(samples, rate, seconds)
        val dataSize = samples.size * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataSize)
            samples.forEach { sample -> putShort((sample * if (sample < 0) 32768f else 32767f).roundToInt().toShort()) }
        }.array()
    }
    fun decode(bytes: ByteArray): LocalVoiceAudio {
        require(bytes.size in 46..MAX_BYTES) { "语音文件长度无效" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(value: String) { require(ByteArray(value.length).also(input::get).contentEquals(value.toByteArray(Charsets.US_ASCII))) { "语音文件格式无效" } }
        tag("RIFF"); require(input.int == bytes.size - 8); tag("WAVEfmt ")
        require(input.int == 16 && input.short.toInt() == 1 && input.short.toInt() == 1)
        val rate = input.int
        require(rate in 8000..48000 && input.int == rate * 2 && input.short.toInt() == 2 && input.short.toInt() == 16)
        tag("data")
        val dataSize = input.int
        require(dataSize == bytes.size - 44 && dataSize % 2 == 0 && dataSize / 2 in 1..rate * MAX_SECONDS)
        return LocalVoiceAudio(FloatArray(dataSize / 2) { input.short / 32768f }, rate)
    }
}
