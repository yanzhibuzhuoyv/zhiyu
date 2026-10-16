package com.zhiyu.app.domain

/** Offsets always refer to the complete stored original; presentation never rewrites memory. */
data class ResponseSegment(val start: Int, val end: Int, val text: String)

object ResponseSegments {
    private const val MAX_SEGMENTS = 16
    private val protected = Regex("```[\\s\\S]*?(?:```|\\z)|`[^`\\r\\n]*`|(?:https?://|www\\.)[^\\s<>。！？；，、（）“”\"]+")
    private val endings = "。！？!?；;…"
    private val closers = "”’\"'）)]】}」』"

    fun split(text: String): List<ResponseSegment> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<ResponseSegment>()
        val protectedMatches = protected.findAll(text).iterator()
        var nextProtected = if (protectedMatches.hasNext()) protectedMatches.next() else null
        var start = 0
        var cursor = 0
        while (cursor < text.length && result.size < MAX_SEGMENTS - 1) {
            val region = nextProtected
            if (region != null && cursor >= region.range.first) {
                cursor = region.range.last + 1
                nextProtected = if (protectedMatches.hasNext()) protectedMatches.next() else null
                continue
            }
            val char = text[cursor++]
            if (char !in endings && char != '\n' && char != '\r') continue
            var end = cursor
            while (end < text.length && (text[end] in endings || text[end] in closers || text[end].isWhitespace())) end++
            if (text.substring(start, end).isBlank()) { cursor = end; continue }
            result += ResponseSegment(start, end, text.substring(start, end))
            start = end; cursor = end
        }
        if (start < text.length) result += ResponseSegment(start, text.length, text.substring(start))
        return result
    }
}
