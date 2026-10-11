package com.zhiyu.app

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.BgeLocalSemanticEncoder
import com.zhiyu.app.data.SemanticInputTooLong
import com.zhiyu.app.data.WordPieceTokenizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** CPU and public synthetic text only. Does not open phone chat databases or provider keys. */
@RunWith(AndroidJUnit4::class)
class LocalSemanticEncoderInstrumentedTest {
    private fun encoder() = BgeLocalSemanticEncoder(InstrumentationRegistry.getInstrumentation().targetContext)
    @Test fun modelPackageIsPresentAndIdentityCoversModelAndTokenizer() {
        val encoder = encoder()
        assertTrue(encoder.isAvailable())
        assertEquals(512, encoder.dimensions)
        assertTrue(encoder.modelIdentity.contains(BgeLocalSemanticEncoder.MODEL_SHA))
        assertTrue(encoder.modelIdentity.contains(BgeLocalSemanticEncoder.VOCAB_SHA))
        assertTrue(encoder.modelIdentity.contains(BgeLocalSemanticEncoder.TOKENIZER_CONFIG_SHA))
        assertTrue(encoder.modelIdentity.contains("prefix-sha256:"))
    }
    @Test fun realModelOutputsFiniteUnitVectorsAndDeterministicReencoding() = runBlocking {
        val encoder = encoder()
        val first = encoder.encodeDocument("我一口辣的都受不了，吃火锅得选清汤。")
        val second = encoder.encodeDocument("我一口辣的都受不了，吃火锅得选清汤。")
        assertEquals(512, first.size)
        assertTrue(first.all { it.isFinite() })
        assertEquals(1.0, first.sumOf { it.toDouble() * it }, .00001)
        assertArrayEquals(first, second, .000001f)
    }
    @Test fun naturalSynonymsRetrieveFactsWithoutExactKeywordTrigger() = runBlocking {
        val encoder = encoder()
        val documents = listOf("我一口辣的都受不了，吃火锅得选清汤。", "我的生日是四月二十三号。",
            "最近晚上翻来覆去睡不着，白天特别没精神。", "昨天我读了一本关于天文的书。")
        val vectors = documents.map { encoder.encodeDocument(it) }
        val queries = listOf("我在饮食上有什么忌口？", "我是哪一天出生的？", "我的作息和精神状态最近怎么样？")
        queries.forEachIndexed { index, query ->
            val encoded = encoder.encodeQuery(query)
            assertEquals("synonym query $index", index, vectors.indices.maxBy { dot(encoded, vectors[it]) })
        }
    }
    @Test fun windowsKeepGlobalChunkIndexAndExactOriginalTail() = runBlocking {
        val encoder = encoder()
        val text = "我".repeat(1021) + "我碰到花生会过敏，千万别让我吃。"
        assertEquals(3, encoder.documentChunkCount(text))
        val first = encoder.encodeDocumentChunks(text, 0, 1).single()
        val second = encoder.encodeDocumentChunks(text, 1, 1).single()
        val third = encoder.encodeDocumentChunks(text, 2, 1).single()
        assertEquals(listOf(0, 1, 2), listOf(first.chunkIndex, second.chunkIndex, third.chunkIndex))
        assertEquals(510, first.tokenEndExclusive)
        assertEquals(510, second.tokenStart)
        assertEquals(1020, third.tokenStart)
        assertEquals(text.length, third.textEndExclusive)
        assertTrue(text.substring(third.textStart, third.textEndExclusive).endsWith("千万别让我吃。"))
        assertTrue(encoder.encodeDocumentChunks(text, 3, 1).isEmpty())
    }
    @Test fun semanticQueryCanFindOnlyTheFinalChunkOfALongOriginal() = runBlocking {
        val encoder = encoder()
        val text = "我们沿河边慢慢走，顺便讨论天气。".repeat(90) + "我碰到花生会过敏，千万别让我吃。"
        val chunks = encoder.encodeDocumentChunks(text, 0, 8)
        assertEquals(3, chunks.size)
        val query = encoder.encodeQuery("我的食物禁忌是什么？")
        assertEquals(2, chunks.indices.maxBy { dot(query, chunks[it].embedding) })
        assertEquals(text.length, chunks.last().textEndExclusive)
    }
    @Test fun singleDocumentNeverSilentlyTruncatesAndQueryLimitIsExplicit() = runBlocking {
        val encoder = encoder()
        try { encoder.encodeDocument("我".repeat(511)); fail("long single document accepted") }
        catch (_: SemanticInputTooLong) { }
        try { encoder.encodeQuery("我".repeat(510)); fail("query prefix budget omitted") }
        catch (_: SemanticInputTooLong) { }
        try { encoder.encodeQueryChunks("我".repeat(5000)); fail("more than eight query chunks accepted") }
        catch (_: SemanticInputTooLong) { }
    }
    @Test fun multipleQueryChunksKeepTailAndIndependentUnitVectors() = runBlocking {
        val encoder = encoder()
        val text = "我".repeat(600) + "过敏"
        val chunks = encoder.encodeQueryChunks(text)
        assertEquals(2, chunks.size)
        assertEquals(text.length, chunks.last().textEndExclusive)
        chunks.forEach { assertEquals(1.0, it.embedding.sumOf { value -> value.toDouble() * value }, .00001) }
    }
    @Test fun invalidBatchBoundsAreRejectedBeforeAnyIndexWrite() = runBlocking {
        val encoder = encoder()
        for ((start, count) in listOf(-1 to 1, 0 to 0, 0 to 9)) {
            try { encoder.encodeDocumentChunks("测试", start, count); fail("invalid batch accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun cancelledWindowCanBeRetriedFromTheSameOriginalOffset() = runBlocking {
        val encoder = encoder()
        val original = "我".repeat(1021)
        val cancelled = Job().apply { cancel() }
        try {
            withContext(cancelled) { encoder.encodeDocumentChunks(original, 0, 1) }
            fail("cancelled indexing continued")
        } catch (_: CancellationException) { }
        val restarted = encoder.encodeDocumentChunks(original, 0, 1).single()
        assertEquals(0, restarted.chunkIndex)
        assertEquals(0, restarted.textStart)
        assertEquals(510, restarted.textEndExclusive)
    }
    @Test fun reportsActualDeviceCpuLatencyFor510ContentTokens() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val vocabulary = instrumentation.targetContext.assets.open("semantic/vocab.txt").bufferedReader().use { it.readLines() }
        val tokenizer = WordPieceTokenizer(vocabulary)
        val longText = "走在晚霞下，风吹过树梢，我想起你说过的每一句话。".repeat(40)
        val text = longText.substring(0, tokenizer.tokenize(longText).take(510).last().endExclusive)
        assertEquals(510, tokenizer.tokenize(text).count())
        val encoder = encoder()
        val firstStarted = SystemClock.elapsedRealtimeNanos()
        val first = encoder.encodeDocument(text)
        val firstMs = (SystemClock.elapsedRealtimeNanos() - firstStarted) / 1_000_000.0
        assertEquals(1.0, first.sumOf { it.toDouble() * it }, .00001)
        val warmMs = List(10) {
            val started = SystemClock.elapsedRealtimeNanos()
            encoder.encodeDocument(text)
            (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        }
        val report = JSONObject().put("content_tokens", 510).put("sequence_tokens", 512)
            .put("dimensions", 512).put("first_call_ms", firstMs).put("warm_min_ms", warmMs.min())
            .put("warm_mean_ms", warmMs.average()).put("warm_max_ms", warmMs.max())
            .put("device_abi", android.os.Build.SUPPORTED_ABIS.joinToString(","))
            .put("model_sha", BgeLocalSemanticEncoder.MODEL_SHA)
            .put("note", "First call may reuse the process session; this is not an isolated cold-start measurement")
        instrumentation.sendStatus(2, Bundle().apply { putString("semantic_cpu_benchmark", report.toString()) })
    }
    @Test fun longAsciiUnknownWordNeverHidesTheBirthdayAtTheEvidenceTail() = runBlocking {
        val encoder = encoder()
        val text = "a".repeat(17000) + "我的生日是十月二日。"
        val chunks = encoder.encodeDocumentChunks(text, 0, 8)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.textEndExclusive - it.textStart <= 8192 })
        assertEquals(listOf(0, 8192, 16384), chunks.map { it.textStart })
        assertEquals(text.length, chunks.last().textEndExclusive)
        assertTrue(text.substring(chunks.last().textStart, chunks.last().textEndExclusive).endsWith("我的生日是十月二日。"))
        val query = encoder.encodeQuery("我是哪一天出生的？")
        assertEquals(2, chunks.indices.maxBy { dot(query, chunks[it].embedding) })
        assertEquals(3, encoder.documentChunkCount(text))
        assertTrue(encoder.modelIdentity.endsWith("chunk510-source8192-v2"))
    }
    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { a[it].toDouble() * b[it] }
}
