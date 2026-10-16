package com.zhiyu.app.domain

class VoiceRecordingBuffer(private val rate: Int = 16000, private val maximumSeconds: Int = 60) {
    init { require(rate in 8000..48000 && maximumSeconds in 1..60) }
    private val chunks = mutableListOf<FloatArray>()
    private var frames = 0
    val durationMillis: Long get() = frames.toLong() * 1000 / rate
    fun append(samples: FloatArray): Boolean {
        require(samples.size in 1..16000 && samples.all { it.isFinite() && it in -1f..1f })
        require(frames.toLong() + samples.size <= rate.toLong() * maximumSeconds) { "这条语音最长一分钟" }
        chunks.add(samples.copyOf()); frames += samples.size
        return frames < rate * maximumSeconds
    }
    fun samples(): FloatArray = FloatArray(frames).also { result ->
        var offset = 0
        chunks.forEach { it.copyInto(result, offset); offset += it.size }
    }
    fun clear() { chunks.forEach { it.fill(0f) }; chunks.clear(); frames = 0 }
}

object VoiceMessagePolicy {
    fun reply(userText: String, receivedVoice: Boolean, style: String?, turnId: String): Boolean =
        when {
            Regex("(不要|别|不想听|不用).{0,5}语音|只.{0,2}(文字|打字)|发文字就好").containsMatchIn(userText) -> false
            receivedVoice || Regex("(发|来|给我|想听).{0,6}语音|说给我听").containsMatchIn(userText) -> true
            else -> {
                require(turnId.isNotBlank())
                val threshold = when (style) { "anxious" -> 35; "warm", "playful" -> 20; "steady" -> 8; "distant" -> 3; else -> 12 }
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(LocalMemoryCodec.bytes(turnId))
                ((hash[0].toInt() and 255) * 256 + (hash[1].toInt() and 255)) % 100 < threshold
            }
        }
}
