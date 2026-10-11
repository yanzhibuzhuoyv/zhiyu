package com.zhiyu.app.data

/** The owner serializes calls. Only one document and at most one token chunk are retained. */
class SemanticDocumentCursor(private val tokenizer: WordPieceTokenizer) {
    private data class Window(val text: String, val chunks: Iterator<SemanticTokenChunk>, var nextChunk: Int)
    private var window: Window? = null

    fun encodeWindow(text: String, startChunk: Int, maxChunks: Int,
        encode: (SemanticTokenChunk) -> SemanticChunk, ensureActive: () -> Unit = {}): List<SemanticChunk> {
        require(startChunk >= 0 && maxChunks in 1..8) { "语义索引批次范围无效" }
        try {
            ensureActive()
            val cursor = window?.takeIf { it.text == text && it.nextChunk == startChunk }
                ?: Window(text, tokenizer.chunks(text).drop(startChunk).iterator(), startChunk).also { window = it }
            val result = ArrayList<SemanticChunk>(maxChunks)
            while (result.size < maxChunks && cursor.chunks.hasNext()) {
                ensureActive()
                val chunk = cursor.chunks.next()
                result.add(encode(chunk))
                cursor.nextChunk = chunk.chunkIndex + 1
                ensureActive()
            }
            if (!cursor.chunks.hasNext()) window = null
            return result
        } catch (failure: Throwable) {
            // next() already advances the iterator. Invalidate before propagating every failure,
            // including cancellation and fatal errors, so retry never silently skips that span.
            window = null
            throw failure
        }
    }
}
