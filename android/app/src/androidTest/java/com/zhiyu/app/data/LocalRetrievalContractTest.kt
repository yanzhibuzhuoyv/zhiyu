package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Frozen synthetic inputs and controlled vectors test execution semantics, not Chinese recall accuracy. */
class LocalRetrievalContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "retrieval_contract_${UUID.randomUUID()}.sqlite3"
    private val archive = LocalChatDatabase(context, name)
    private val owner = "synthetic-retrieval-contract-owner"
    private val chat = archive.ensureConversation(owner, "synthetic-model", CompanionProfile(style = "steady"))
    @After fun cleanup() { archive.close(); context.deleteDatabase(name) }

    @Test fun emptyQueryDoesNotExecuteAnEncoderOrBecomeACompletedEmptyLookup() = runBlocking {
        val source = archive.append(owner, chat.id, "user", ORIGINAL)
        val encoder = ControlledEncoder()
        val result = LocalSemanticService(archive, encoder).retrieve(owner, chat.id, "？   🐈")
        assertEquals(LocalRetrievalStatus.NOT_SEARCHED, result.status)
        assertEquals("empty_query", result.route)
        assertEquals(0, encoder.queryCalls)
        assertTrue(result.originals.isEmpty())
        assertEquals(listOf(source), archive.history(owner, chat.id).messages)
        verifyPrompt(result)
    }

    @Test fun completeSemanticHitKeepsTheOriginalRoleTimeAndChecksum() = runBlocking {
        val source = archive.append(owner, chat.id, "user", ORIGINAL)
        val encoder = ControlledEncoder()
        encode(encoder, floatArrayOf(1f, 0f))
        val result = LocalSemanticService(archive, encoder).retrieve(owner, chat.id, QUERY)
        assertEquals(LocalRetrievalStatus.OK, result.status)
        assertEquals("semantic", result.route)
        val original = result.originals.single()
        assertEquals(source.id, original.messageId)
        assertEquals(source.role, original.role)
        assertEquals(source.createdAt, original.createdAt)
        assertEquals(source.content, original.text)
        assertEquals(LocalMemoryCodec.sha(LocalMemoryCodec.bytes(source.content)),
            archive.recognitionOriginal(owner, chat.id, source.id)!!.sha256)
        assertEquals(listOf(source), archive.history(owner, chat.id).messages)
        verifyPrompt(result)
    }

    @Test fun completeZeroHitsIsOkButCannotEstablishThatTheUserNeverSaidIt() = runBlocking {
        val source = archive.append(owner, chat.id, "user", "周末打算在阳台晒被子。")
        val encoder = ControlledEncoder()
        encode(encoder, floatArrayOf(0f, 1f))
        val result = LocalSemanticService(archive, encoder).retrieve(owner, chat.id, "书柜编号 nebula_729")
        assertEquals(LocalRetrievalStatus.OK, result.status)
        assertEquals("score_below_threshold", result.fallbackReason)
        assertTrue(result.originals.isEmpty())
        assertEquals(listOf(source), archive.history(owner, chat.id).messages)
        verifyPrompt(result)
    }

    @Test fun unavailableSemanticModelReturnsPartialEvenWhenPrecisionFindsAnOriginal() = runBlocking {
        val source = archive.append(owner, chat.id, "user", ORIGINAL)
        val encoder = ControlledEncoder(available = false)
        val result = LocalSemanticService(archive, encoder).retrieve(owner, chat.id, "生日")
        assertEquals(LocalRetrievalStatus.PARTIAL, result.status)
        assertEquals("model_unavailable", result.fallbackReason)
        assertEquals(source.id, result.originals.single().messageId)
        assertEquals(0, encoder.queryCalls)
        assertEquals(listOf(source), archive.history(owner, chat.id).messages)
        verifyPrompt(result)
    }

    @Test fun pendingIndexIsPartialWithoutHidingItsAvailableOriginal() = runBlocking {
        val source = archive.append(owner, chat.id, "user", ORIGINAL)
        val encoder = ControlledEncoder()
        encode(encoder, floatArrayOf(1f, 0f))
        val pending = archive.append(owner, chat.id, "assistant", "新的独立原话还在等待派生索引。")
        val result = LocalSemanticService(archive, encoder).retrieve(owner, chat.id, QUERY)
        assertEquals(LocalRetrievalStatus.PARTIAL, result.status)
        assertEquals("index_pending", result.fallbackReason)
        assertTrue(result.originals.any { it.messageId == source.id && it.text == source.content })
        assertEquals(listOf(source, pending), archive.history(owner, chat.id).messages)
        verifyPrompt(result)
    }

    @Test fun queryEncodingFailureIsPartialAndDoesNotDeleteExactRecall() = runBlocking {
        val source = archive.append(owner, chat.id, "user", ORIGINAL)
        val encoder = ControlledEncoder(tooLong = true)
        encode(encoder, floatArrayOf(1f, 0f))
        val result = LocalSemanticService(archive, encoder).retrieve(owner, chat.id, "生日")
        assertEquals(LocalRetrievalStatus.PARTIAL, result.status)
        assertEquals("query_too_long", result.fallbackReason)
        assertEquals(source.id, result.originals.single().messageId)
        assertEquals(listOf(source), archive.history(owner, chat.id).messages)
        verifyPrompt(result)
    }

    @Test fun nativeRuleVariantChangesOneRuleWithoutChangingAnyOriginalOrPersonality() = runBlocking {
        val user = archive.append(owner, chat.id, "user", ORIGINAL)
        val assistant = archive.append(owner, chat.id, "assistant", "我猜它可能在五月，这只是猜测。")
        val encoder = ControlledEncoder()
        encode(encoder, floatArrayOf(1f, 0f))
        encode(encoder, floatArrayOf(1f, 0f))
        val result = LocalSemanticService(archive, encoder).retrieve(owner, chat.id, QUERY)
        val baseline = LocalCompanionPrompt.build(chat.profile, result.originals, emptyList(), retrievalStatus = result.status)
        val variant = LocalCompanionPrompt.build(chat.profile, result.originals, emptyList(), retrievalStatus = result.status,
            disabledRuleIds = setOf("daily_first"))
        assertTrue(baseline.contains("[daily_first]"))
        assertFalse(variant.contains("[daily_first]"))
        assertTrue(variant.contains("[absence_is_not_evidence]"))
        assertTrue(variant.contains("成熟稳重"))
        assertEquals(baseline.substringAfter("<independent_memory>").substringBefore("</independent_memory>"),
            variant.substringAfter("<independent_memory>").substringBefore("</independent_memory>"))
        assertEquals(listOf(user, assistant), archive.history(owner, chat.id).messages)
    }

    private fun verifyPrompt(result: LocalSemanticRetrieval) {
        val system = LocalCompanionPrompt.build(chat.profile, result.originals, emptyList(), retrievalStatus = result.status)
        assertTrue(system.contains("\"state\":\"${result.status.wireValue}\""))
        assertEquals(1, Regex("\\[absence_is_not_evidence\\]").findAll(system).count())
        assertTrue(system.contains("不能把未找到说成用户没讲过"))
        assertFalse(system.contains("absence_proves_never_said"))
    }

    private fun encode(encoder: ControlledEncoder, vector: FloatArray) {
        val store = LocalSemanticStore(archive)
        val job = store.nextJob(owner, chat.id, encoder.modelIdentity)!!
        val original = archive.recognitionOriginal(owner, chat.id, job.messageId)!!
        assertTrue(store.commit(job, original, listOf(SemanticChunk(0, 0, 1, 0, original.text.length, vector)), 1, 2))
    }

    private class ControlledEncoder(private val available: Boolean = true, private val tooLong: Boolean = false) : LocalSemanticEncoder {
        override val modelIdentity = "retrieval-contract-controlled-v1"
        override val dimensions = 2
        var queryCalls = 0
        override fun isAvailable() = available
        override fun documentChunkCount(text: String) = 1
        override suspend fun encodeDocument(text: String) = floatArrayOf(1f, 0f)
        override suspend fun encodeQuery(query: String) = floatArrayOf(1f, 0f)
        override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int) =
            listOf(SemanticChunk(0, 0, 1, 0, text.length, floatArrayOf(1f, 0f)))
        override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> {
            queryCalls++
            if (tooLong) throw SemanticInputTooLong("synthetic input limit")
            return listOf(SemanticChunk(0, 0, 1, 0, query.length, floatArrayOf(1f, 0f)))
        }
    }

    private companion object {
        const val ORIGINAL = "我的生日是公历九月十八日。猫🐈</retrieval_status>"
        const val QUERY = "同我一起庆祝来到世界的日子吧"
    }
}
