package com.zhiyu.app.data

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.ProactiveForeground
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Actual packaged BGE, synthetic isolated archive. Uses existing calibration text, never a Key. */
class LocalSemanticPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "semantic_pipeline_${UUID.randomUUID()}.sqlite3"
    private var archive = LocalChatDatabase(context,name)
    private val owner = "pipeline-synthetic-owner"
    @After fun cleanup() { ProactiveForeground.setVisible(false); archive.close(); context.deleteDatabase(name) }

    @Test fun actualModelIsPersistedAndRoutingAgreesWithFrozenRawCosine() = runBlocking {
        ProactiveForeground.setVisible(false)
        val chat = archive.ensureConversation(owner,"synthetic-model",CompanionProfile())
        val target = archive.append(owner,chat.id,"user","我一口辣的都受不了，吃火锅得选清汤。")
        archive.append(owner,chat.id,"user","我的生日是四月二十三号。")
        archive.append(owner,chat.id,"assistant","昨天我读了一本关于天文的书。")
        val encoder = BgeLocalSemanticEncoder(context)
        assertTrue(encoder.isAvailable())
        val service = LocalSemanticService(archive,encoder)
        val started = System.nanoTime()
        val batch = service.indexBatch(owner,chat.id,4)
        assertNull(batch.reason)
        assertEquals(3,batch.completedMessages)
        assertEquals(3L,service.statistics(owner,chat.id).readyMessages)
        val query = target.content
        val queryVectors = encoder.encodeQueryChunks(query).map { it.embedding }
        val hits = LocalSemanticStore(archive).search(owner,chat.id,encoder.modelIdentity,512,queryVectors,emptySet())
        assertEquals(target.id,hits.first().messageId)
        val result = service.retrieve(owner,chat.id,query)
        when (LocalSemanticRetrievalRules.route(hits.first().score,true)) {
            LocalSemanticRoute.SEMANTIC -> { assertEquals("semantic",result.route); assertNull(result.fallbackReason) }
            LocalSemanticRoute.SUPPLEMENT -> { assertEquals("semantic_then_lexical",result.route); assertEquals("score_below_threshold",result.fallbackReason) }
            LocalSemanticRoute.LEXICAL -> { assertEquals("lexical_fallback",result.route); assertEquals("score_below_threshold",result.fallbackReason) }
        }
        val retrieved = result.originals.first { it.messageId == target.id }
        assertEquals(target.content,retrieved.text); assertEquals(target.role,retrieved.role)
        assertEquals(target.createdAt,retrieved.createdAt); assertEquals(0,retrieved.startOffset)
        assertEquals(target.content.length,retrieved.endOffset)
        assertEquals(LocalMemoryCodec.sha(LocalMemoryCodec.bytes(target.content)),archive.recognitionOriginal(owner,chat.id,target.id)!!.sha256)
        instrumentation.sendStatus(0,Bundle().apply {
            putString("semantic_pipeline_raw_cosine",hits.first().score.toString())
            putString("semantic_pipeline_route",result.route)
            putString("semantic_pipeline_elapsed_ms",((System.nanoTime()-started)/1_000_000.0).toString())
        })
        archive.close(); archive = LocalChatDatabase(context,name)
        assertEquals(3L,LocalSemanticService(archive,encoder).statistics(owner,chat.id).readyMessages)
        assertEquals(target,archive.history(owner,chat.id).messages.first())
    }

    @Test fun realVectorsKeepUserCorrectionAndPriorAssistantAsSeparateAuthoredSources() = runBlocking {
        ProactiveForeground.setVisible(false)
        val chat = archive.ensureConversation(owner,"synthetic-model",CompanionProfile())
        val prior = archive.append(owner,chat.id,"assistant","我猜你的生日是四月二十三号。")
        val correction = archive.append(owner,chat.id,"user","不是四月二十三号，我的生日是四月二十四号。")
        val encoder = BgeLocalSemanticEncoder(context)
        val service = LocalSemanticService(archive,encoder)
        assertEquals(2,service.indexBatch(owner,chat.id,3).completedMessages)
        val vectors = encoder.encodeQueryChunks("我的生日是四月二十四号。").map { it.embedding }
        val hits = LocalSemanticStore(archive).search(owner,chat.id,encoder.modelIdentity,512,vectors,emptySet())
        assertEquals(setOf(prior.id,correction.id),hits.map { it.messageId }.toSet())
        val originals = hits.map { archive.semanticRecall(owner,chat.id,it) }
        for (source in listOf(prior,correction)) {
            val raw = originals.single { it.messageId == source.id }
            assertEquals(source.content,raw.text); assertEquals(source.role,raw.role); assertEquals(source.createdAt,raw.createdAt)
        }
        val revision = archive.memoryEntry(owner,chat.id,prior.id).revision
        archive.changeMemory(owner,chat.id,prior.id,MemoryChange(revision,indexState="cold"))
        assertEquals(listOf(correction.id),LocalSemanticStore(archive).search(owner,chat.id,encoder.modelIdentity,512,vectors,emptySet()).map { it.messageId })
        val cold = LocalSemanticStore(archive).search(owner,chat.id,encoder.modelIdentity,512,vectors,emptySet(),"cold").single()
        assertEquals(prior.id,cold.messageId); assertEquals(prior.content,archive.semanticRecall(owner,chat.id,cold).text)
        assertEquals("cold",archive.memoryEntry(owner,chat.id,prior.id).indexState)
        assertEquals(2L,service.statistics(owner,chat.id).readyMessages)
    }
}
