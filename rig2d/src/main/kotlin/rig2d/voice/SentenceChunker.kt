package rig2d.voice

/** Bounded code-point chunks; Chinese sentence ends emit immediately, emoji remain intact. */
class SentenceChunker(maxChunkCodePoints: Int = 80, maxTotalCodePoints: Int = 8192) {
    private val chunkLimit = maxChunkCodePoints
    private val totalLimit = maxTotalCodePoints
    private val buffer = StringBuilder()
    private var pendingHigh: Char? = null
    private var chunkPoints = 0
    private var totalPoints = 0
    private var finished = false

    init { require(chunkLimit in 1..120 && totalLimit in chunkLimit..32768) }

    fun accept(token: String): List<String> {
        check(!finished) { "Sentence stream is finished" }
        require(token.length <= totalLimit * 2 + 1) { "Token is too large" }
        val output = mutableListOf<String>()
        var index = 0
        while (index < token.length) {
            val first = token[index++]
            val high = pendingHigh
            val codePoint = if (high != null) {
                require(first.isLowSurrogate()) { "Invalid streamed Unicode" }
                pendingHigh = null
                Character.toCodePoint(high, first)
            } else if (first.isHighSurrogate()) {
                if (index == token.length) { pendingHigh = first; break }
                val low = token[index++]
                require(low.isLowSurrogate()) { "Invalid streamed Unicode" }
                Character.toCodePoint(first, low)
            } else {
                require(!first.isLowSurrogate()) { "Invalid streamed Unicode" }
                first.code
            }
            require(++totalPoints <= totalLimit) { "Reply is too long" }
            buffer.appendCodePoint(codePoint)
            chunkPoints++
            if (codePoint in sentenceEnds || chunkPoints >= chunkLimit) flush(output)
        }
        return output
    }

    fun finish(): List<String> {
        require(pendingHigh == null) { "Incomplete streamed Unicode" }
        finished = true
        return mutableListOf<String>().also(::flush)
    }

    private fun flush(output: MutableList<String>) {
        val value = buffer.toString().trim()
        if (value.isNotBlank()) output += value
        buffer.setLength(0); chunkPoints = 0
    }

    companion object {
        private val sentenceEnds = setOf('。', '！', '？', '!', '?', '；', ';', '\n').map { it.code }.toSet()
    }
}
