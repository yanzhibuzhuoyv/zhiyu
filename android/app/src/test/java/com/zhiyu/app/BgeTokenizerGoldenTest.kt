package com.zhiyu.app

import com.zhiyu.app.data.WordPieceTokenizer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class BgeTokenizerGoldenTest {
    @Test fun tokenIdsAndExactUtf16OffsetsMatchPinnedOfficialTokenizer() {
        val vocab = resource("semantic/vocab.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() }
        val tokenizer = WordPieceTokenizer(vocab)
        val rows = resource("semantic/tokenizer-golden.tsv").bufferedReader(Charsets.UTF_8).use { it.readLines() }
        assertEquals(6, rows.size)
        rows.forEachIndexed { case, line ->
            val columns = line.split('\t')
            val text = String(Base64.getDecoder().decode(columns[0]), Charsets.UTF_8)
            val ids = columns[1].split(',').map { it.toInt() }
            val spans = columns[2].split(',').map { it.split(':').let { pair -> pair[0].toInt() to pair[1].toInt() } }
            val tokens = tokenizer.tokenize(text).toList()
            assertEquals("token IDs case=$case", ids, tokens.map { it.id })
            assertEquals("UTF16 offsets case=$case", spans, tokens.map { it.start to it.endExclusive })
        }
    }
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)
        ?: throw IllegalStateException("missing $name")
}
