package com.zhiyu.app

import com.zhiyu.app.data.WordPieceTokenizer
import org.junit.Assert.*
import org.junit.Test

class WordPieceTokenizerTest {
    private val vocabulary = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "我", "想", "吃", "苹", "果", "，", "！", "hello", "##s", "a", "café")
    private val tokenizer = WordPieceTokenizer(vocabulary)
    @Test fun chineseAndPunctuationKeepExactUtf16Offsets() {
        val text = "  我想吃苹果，！ "
        val tokens = tokenizer.tokenize(text).toList()
        assertEquals(listOf(5, 6, 7, 8, 9, 10, 11), tokens.map { it.id })
        assertEquals(listOf("我", "想", "吃", "苹", "果", "，", "！"), tokens.map { text.substring(it.start, it.endExclusive) })
        assertEquals(2, tokens.first().start)
        assertEquals(9, tokens.last().endExclusive)
    }
    @Test fun wordPiecesSplitGreedilyWithoutLosingOriginalOffsets() {
        val text = "hellos café"
        val tokens = tokenizer.tokenize(text).toList()
        assertEquals(listOf(12, 13, 15), tokens.map { it.id })
        assertEquals(listOf("hello", "s", "café"), tokens.map { text.substring(it.start, it.endExclusive) })
    }
    @Test fun officialBgeDoesNotLowerCaseOrStripAccents() {
        assertEquals(1, tokenizer.tokenize("HELLO").single().id)
        assertEquals(15, tokenizer.tokenize("café").single().id)
    }
    @Test fun supplementaryCharactersUseUtf16RatherThanCodePointOffsets() {
        val text = "我😀想𠀀吃"
        val tokens = tokenizer.tokenize(text).toList()
        assertEquals(listOf(0 to 1, 1 to 3, 3 to 4, 4 to 6, 6 to 7), tokens.map { it.start to it.endExclusive })
    }
    @Test fun specialLiteralsAreNotConfusedWithAddedClsAndSep() {
        val text = "我[MASK]想"
        val tokens = tokenizer.tokenize(text).toList()
        assertEquals(listOf(5, 4, 6), tokens.map { it.id })
        assertEquals(1 to 7, tokens[1].start to tokens[1].endExclusive)
    }
    @Test fun controlsAreCleanedButWhitespaceDoesNotMergeWords() {
        val tokens = tokenizer.tokenize("我\u0000\u200b想\t吃\n果").toList()
        assertEquals(listOf(5, 6, 7, 9), tokens.map { it.id })
        assertEquals(listOf(0, 3, 5, 7), tokens.map { it.start })
    }
    @Test fun longUnknownWordIsOneTokenWithFullOriginalSpan() {
        val text = "a".repeat(20000)
        val token = tokenizer.tokenize(text).single()
        assertEquals(1, token.id)
        assertEquals(0, token.start)
        assertEquals(text.length, token.endExclusive)
    }
    @Test fun chunksIncludeAllTokensAndOriginalTailWithoutAddedSpecialSpans() {
        val text = "我".repeat(1021) + "苹果"
        val chunks = tokenizer.chunks(text).toList()
        assertEquals(listOf(510, 510, 3), chunks.map { it.tokens.size })
        assertEquals(1023, chunks.last().tokenEndExclusive)
        assertEquals(text.length, chunks.last().textEndExclusive)
        assertEquals("我苹果", text.substring(chunks.last().textStart, chunks.last().textEndExclusive))
        assertEquals(listOf(5, 8, 9), chunks.last().tokens.map { it.id })
    }
    @Test fun windowingRemainsLazyAndMaximumIsValidated() {
        assertEquals(510, tokenizer.chunks("我".repeat(100000)).first().tokens.size)
        rejects { tokenizer.chunks("我", 0).toList() }
        rejects { WordPieceTokenizer(listOf("[UNK]", "[UNK]")) }
    }
    @Test fun appSourceWindowsKeepLongUnknownPrefixAndFinalFactIndependently() {
        val text = "a".repeat(17000) + "苹果"
        assertEquals(3, tokenizer.tokenize(text).count()) // Official raw WordPiece remains one UNK plus two Chinese tokens.
        val chunks = tokenizer.chunks(text).toList()
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.textEndExclusive - it.textStart <= 8192 && it.tokens.size <= 510 })
        assertEquals(listOf(0, 8192, 16384), chunks.map { it.textStart })
        assertEquals(text.length, chunks.last().textEndExclusive)
        assertTrue(text.substring(chunks.last().textStart, chunks.last().textEndExclusive).endsWith("苹果"))
        assertEquals(listOf(1, 8, 9), chunks.last().tokens.map { it.id })
        assertEquals(5, chunks.last().tokenEndExclusive) // Application stream includes independently re-tokenized source windows.
    }
    @Test fun veryWideWhitespaceGapsCannotCreateAnOversizedEvidenceSpan() {
        val text = "我" + " ".repeat(20000) + "苹果"
        val chunks = tokenizer.chunks(text).toList()
        assertEquals(2, chunks.size)
        assertEquals(0 to 1, chunks.first().textStart to chunks.first().textEndExclusive)
        assertEquals(20001 to 20003, chunks.last().textStart to chunks.last().textEndExclusive)
        assertEquals(listOf(5, 8, 9), chunks.flatMap { it.tokens }.map { it.id })
    }
    @Test fun sourceWindowsNeverSplitAUtf16SurrogatePair() {
        val text = "a" + "😀".repeat(9000) + "苹果"
        val chunks = tokenizer.chunks(text).toList()
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.textEndExclusive - it.textStart <= 8192 })
        chunks.forEach { chunk ->
            assertFalse(Character.isLowSurrogate(text[chunk.textStart]))
            assertFalse(Character.isHighSurrogate(text[chunk.textEndExclusive - 1]))
        }
        assertEquals(text.length, chunks.last().textEndExclusive)
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { } }
}
