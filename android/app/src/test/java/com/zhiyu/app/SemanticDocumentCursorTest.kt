package com.zhiyu.app

import com.zhiyu.app.data.SemanticChunk
import com.zhiyu.app.data.SemanticDocumentCursor
import com.zhiyu.app.data.SemanticTokenChunk
import com.zhiyu.app.data.WordPieceTokenizer
import org.junit.Assert.*
import org.junit.Test

class SemanticDocumentCursorTest {
    private val tokenizer = WordPieceTokenizer(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "我"))
    private fun vector(chunk: SemanticTokenChunk) = SemanticChunk(chunk.chunkIndex, chunk.tokenStart,
        chunk.tokenEndExclusive, chunk.textStart, chunk.textEndExclusive, floatArrayOf(1f))

    @Test fun failureAfterReadingATokenNeverSkipsItOnRetry() {
        val cursor = SemanticDocumentCursor(tokenizer)
        val text = "我".repeat(1021)
        try {
            cursor.encodeWindow(text, 0, 2, encode = { chunk ->
                if (chunk.chunkIndex == 1) throw IllegalStateException("synthetic model failure")
                vector(chunk)
            })
            fail("failure should propagate")
        } catch (_: IllegalStateException) { }
        val retry = cursor.encodeWindow(text, 0, 2, encode = ::vector)
        assertEquals(listOf(0, 1), retry.map { it.chunkIndex })
        assertEquals(listOf(0, 510), retry.map { it.textStart })
        assertEquals(2, cursor.encodeWindow(text, 2, 1, encode = ::vector).single().chunkIndex)
    }

    @Test fun cancellationAfterReadPropagatesAndRetryStartsAtRequestedSpan() {
        val cursor = SemanticDocumentCursor(tokenizer)
        val text = "我".repeat(1021)
        val cancellation = java.util.concurrent.CancellationException("synthetic cancellation")
        try {
            cursor.encodeWindow(text, 0, 2, encode = { throw cancellation })
            fail("cancellation should propagate")
        } catch (actual: java.util.concurrent.CancellationException) { assertSame(cancellation, actual) }
        assertEquals(0, cursor.encodeWindow(text, 0, 1, encode = ::vector).single().chunkIndex)
    }

    @Test fun batchOnlyEncodesRequestedWindowAndKeepsGlobalOffsets() {
        val cursor = SemanticDocumentCursor(tokenizer)
        val observed = mutableListOf<Int>()
        val result = cursor.encodeWindow("我".repeat(2000), 2, 1, encode = {
            observed.add(it.chunkIndex); vector(it)
        })
        assertEquals(listOf(2), observed)
        assertEquals(1020, result.single().textStart)
        assertEquals(1530, result.single().textEndExclusive)
    }

    @Test fun completedWindowCanRestartAndDifferentDocumentsCannotReuseCursor() {
        val cursor = SemanticDocumentCursor(tokenizer)
        assertEquals(1, cursor.encodeWindow("我", 0, 8, encode = ::vector).size)
        assertTrue(cursor.encodeWindow("我", 1, 8, encode = ::vector).isEmpty())
        assertEquals(0, cursor.encodeWindow("我", 0, 8, encode = ::vector).single().chunkIndex)
        assertEquals(2, cursor.encodeWindow("我".repeat(511), 0, 8, encode = ::vector).size)
    }

    @Test fun errorsAndCancellationChecksAreNeverSwallowed() {
        val cursor = SemanticDocumentCursor(tokenizer)
        val error = AssertionError("synthetic fatal error")
        try {
            cursor.encodeWindow("我", 0, 1, encode = ::vector, ensureActive = { throw error })
            fail("fatal error swallowed")
        } catch (actual: AssertionError) { assertSame(error, actual) }
        assertEquals(0, cursor.encodeWindow("我", 0, 1, encode = ::vector).single().chunkIndex)
    }

    @Test fun rejectsInvalidWindowBounds() {
        val cursor = SemanticDocumentCursor(tokenizer)
        listOf(-1 to 1, 0 to 0, 0 to 9).forEach { (start, count) ->
            try { cursor.encodeWindow("我", start, count, encode = ::vector); fail("invalid bounds") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
