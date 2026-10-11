package com.zhiyu.app.data

import java.text.Normalizer
import java.util.Locale

data class WordPieceToken(val id: Int, val start: Int, val endExclusive: Int)
data class SemanticTokenChunk(val chunkIndex: Int, val tokenStart: Int, val tokenEndExclusive: Int,
    val textStart: Int, val textEndExclusive: Int, val tokens: List<WordPieceToken>)
private data class TokenUnit(val start: Int, val end: Int)
private data class BasicWord(val value: String, val units: List<TokenUnit>, val start: Int, val end: Int, val tooLong: Boolean)

/** BERT WordPiece with source UTF16 offsets. BGE's official normalizer preserves case and accents. */
class WordPieceTokenizer(vocabulary: List<String>, private val lowerCase: Boolean = false,
    private val stripAccents: Boolean = false) {
    companion object { const val MAX_SOURCE_UNITS = 8192 }
    private val ids = vocabulary.withIndex().associate { it.value to it.index }
    private val unknown = ids["[UNK]"] ?: throw IllegalArgumentException("词表缺少 UNK")
    val clsId = ids["[CLS]"] ?: throw IllegalArgumentException("词表缺少 CLS")
    val sepId = ids["[SEP]"] ?: throw IllegalArgumentException("词表缺少 SEP")
    val padId = ids["[PAD]"] ?: throw IllegalArgumentException("词表缺少 PAD")
    private val specials = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]").filter { it in ids }
    init { require(vocabulary.size == ids.size && vocabulary.none { it.isEmpty() }) { "词表重复或为空" } }
    fun tokenize(text: String): Sequence<WordPieceToken> = sequence {
        require(text.length <= 2 * 1024 * 1024) { "语义输入超过 2 MiB 字符限制" }
        for (word in basicWords(text)) {
            if (word.value in specials) yield(WordPieceToken(ids.getValue(word.value), word.start, word.end))
            else for (token in splitWord(word)) yield(token)
        }
    }
    fun chunks(text: String, maximumContentTokens: Int = 510): Sequence<SemanticTokenChunk> = sequence {
        require(maximumContentTokens in 1..510) { "语义分段大小无效" }
        var chunkIndex = 0; var tokenStart = 0
        var pending = ArrayList<WordPieceToken>(maximumContentTokens)
        for (token in sourceWindowTokens(text)) {
            if (pending.isNotEmpty() && token.endExclusive - pending.first().start > MAX_SOURCE_UNITS) {
                yield(SemanticTokenChunk(chunkIndex++, tokenStart, tokenStart + pending.size,
                    pending.first().start, pending.last().endExclusive, pending.toList()))
                tokenStart += pending.size
                pending = ArrayList(maximumContentTokens)
            }
            pending.add(token)
            if (pending.size == maximumContentTokens) {
                yield(SemanticTokenChunk(chunkIndex++, tokenStart, tokenStart + pending.size,
                    pending.first().start, pending.last().endExclusive, pending.toList()))
                tokenStart += pending.size
                pending = ArrayList(maximumContentTokens)
            }
        }
        if (pending.isNotEmpty()) yield(SemanticTokenChunk(chunkIndex, tokenStart, tokenStart + pending.size,
            pending.first().start, pending.last().endExclusive, pending.toList()))
    }
    /** Source windowing is an application policy; tokenize() itself keeps official WordPiece behavior. */
    private fun sourceWindowTokens(text: String): Sequence<WordPieceToken> = sequence {
        for (token in tokenize(text)) {
            if (token.endExclusive - token.start <= MAX_SOURCE_UNITS) { yield(token); continue }
            var sourceStart = token.start
            while (sourceStart < token.endExclusive) {
                var sourceEnd = minOf(sourceStart + MAX_SOURCE_UNITS, token.endExclusive)
                if (sourceEnd < token.endExclusive && Character.isHighSurrogate(text[sourceEnd - 1]) &&
                    Character.isLowSurrogate(text[sourceEnd])) sourceEnd--
                val sourceWindow = text.substring(sourceStart, sourceEnd)
                for (part in tokenize(sourceWindow)) yield(part.copy(start = part.start + sourceStart,
                    endExclusive = part.endExclusive + sourceStart))
                sourceStart = sourceEnd
            }
        }
    }
    private fun splitWord(word: BasicWord): List<WordPieceToken> {
        if (word.tooLong) return listOf(WordPieceToken(unknown, word.start, word.end))
        val pieces = ArrayList<WordPieceToken>()
        var start = 0
        while (start < word.value.length) {
            var end = word.value.length
            var chosen: Int? = null
            while (end > start) {
                chosen = ids[(if (start > 0) "##" else "") + word.value.substring(start, end)]
                if (chosen != null) break
                end--
            }
            if (chosen == null) return listOf(WordPieceToken(unknown, word.start, word.end))
            pieces.add(WordPieceToken(chosen, word.units[start].start, word.units[end - 1].end))
            start = end
        }
        return pieces
    }
    private fun basicWords(text: String): Sequence<BasicWord> = sequence {
        val word = StringBuilder()
        val units = ArrayList<TokenUnit>()
        var start = -1; var end = 0; var codePoints = 0
        fun finish(): BasicWord? {
            if (start < 0) return null
            val result = BasicWord(word.toString(), units.toList(), start, end, codePoints > 100)
            word.setLength(0); units.clear(); start = -1; codePoints = 0
            return result
        }
        var offset = 0
        while (offset < text.length) {
            val special = specials.firstOrNull { text.startsWith(it, offset) }
            if (special != null) {
                finish()?.let { yield(it) }
                yield(BasicWord(special, emptyList(), offset, offset + special.length, false))
                offset += special.length; continue
            }
            val point = text.codePointAt(offset); val next = offset + Character.charCount(point)
            if (point == 0 || point == 0xfffd || isControl(point)) { offset = next; continue }
            if (isWhitespace(point)) { finish()?.let { yield(it) }; offset = next; continue }
            if (isChinese(point) || isPunctuation(point)) {
                finish()?.let { yield(it) }
                val part = normalize(String(Character.toChars(point)))
                if (part.isNotEmpty()) yield(BasicWord(part, List(part.length) { TokenUnit(offset, next) }, offset, next, false))
                offset = next; continue
            }
            if (start < 0) start = offset
            end = next; codePoints++
            if (codePoints <= 100) {
                val part = normalize(String(Character.toChars(point)))
                word.append(part)
                repeat(part.length) { units.add(TokenUnit(offset, next)) }
            }
            offset = next
        }
        finish()?.let { yield(it) }
    }
    private fun normalize(value: String): String {
        val text = if (lowerCase) value.lowercase(Locale.ROOT) else value
        return if (stripAccents) Normalizer.normalize(text, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() } else text
    }
    private fun isWhitespace(point: Int) = point == 9 || point == 10 || point == 13 || point == 32 || Character.getType(point) == Character.SPACE_SEPARATOR.toInt()
    private fun isControl(point: Int) = point != 9 && point != 10 && point != 13 &&
        (Character.getType(point) == Character.CONTROL.toInt() || Character.getType(point) == Character.FORMAT.toInt())
    private fun isPunctuation(point: Int): Boolean = point in 33..47 || point in 58..64 || point in 91..96 || point in 123..126 ||
        when (Character.getType(point)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    private fun isChinese(point: Int) = point in 0x4e00..0x9fff || point in 0x3400..0x4dbf || point in 0x20000..0x2a6df ||
        point in 0x2a700..0x2b73f || point in 0x2b740..0x2b81f || point in 0x2b820..0x2ceaf || point in 0xf900..0xfaff || point in 0x2f800..0x2fa1f
}
