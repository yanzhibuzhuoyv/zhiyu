package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Mathematical vectors deliberately miss the correction: this verifies actual original-pointer safety. */
class LocalSemanticCorrectionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "semantic_correction_${UUID.randomUUID()}.sqlite3"
    private val archive = LocalChatDatabase(context,name)
    private val owner = "synthetic-correction-owner"
    private val chat = archive.ensureConversation(owner,"synthetic-model",CompanionProfile())
    @After fun cleanup() { archive.close(); context.deleteDatabase(name) }
    @Test fun currentCorrectionIsPresentEvenWhenOnlyOldSourceHasHighCosine() = runBlocking {
        val messages = prepare(listOf("我的生日是九月十八日。","不是那个日期，是四月二十四号。"))
        val result = LocalSemanticService(archive,Encoder()).retrieve(owner,chat.id,"生日")
        assertEquals("semantic",result.route)
        val correction = result.originals.single()
        assertEquals(messages.last().id,correction.messageId)
        assertEquals(messages.last().content,correction.text)
        assertEquals(messages.last().role,correction.role); assertEquals(messages.last().createdAt,correction.createdAt)
        assertEquals("current_correction",correction.evidenceState)
        assertEquals(listOf(messages.first().id),correction.correctionOf)
        assertEquals(messages,archive.history(owner,chat.id).messages)
    }
    @Test fun transitiveCorrectionLinksReachTheCurrentIndependentOriginal() = runBlocking {
        val messages = prepare(listOf("我的生日是九月十八日。","不是那个日期，是四月二十四号。",
            "我又说错了，不是四月二十四号，是五月十号。"))
        val result = LocalSemanticService(archive,Encoder()).retrieve(owner,chat.id,"生日")
        assertEquals(messages.last().id,result.originals.single().messageId)
        assertEquals(messages.last().content,result.originals.single().text)
        assertEquals(messages,archive.history(owner,chat.id).messages)
    }
    @Test fun missingOrWrongShaLinkKeepsTheOldSourceExplicitlyUnresolved() = runBlocking {
        val messages = prepare(listOf("我的生日是九月十八日。","不是那个日期，是四月二十四号。"))
        archive.writableDatabase.execSQL("UPDATE native_memory_annotations SET source_sha=? WHERE message_id=?",
            arrayOf("0".repeat(64),messages.last().id))
        val result = LocalSemanticService(archive,Encoder()).retrieve(owner,chat.id,"生日")
        assertEquals("semantic_then_lexical",result.route)
        assertEquals("correction_unresolved",result.fallbackReason)
        assertEquals("superseded_unresolved",result.originals.single().evidenceState)
        assertEquals(messages.first().id,result.originals.single().messageId)
        val prompt = LocalCompanionPrompt.build(CompanionProfile(),result.originals,emptyList())
        assertTrue(prompt.contains("不能把那段旧属性当作现行事实"))
        assertEquals(messages,archive.history(owner,chat.id).messages)
    }
    @Test fun aLongCorrectionIsRecoveredByPrecisionWithoutBreakingTheTotalOriginalBudget() = runBlocking {
        val messages = prepare(listOf("我的生日是九月十八日。","说明。".repeat(4500) + "不是九月十八日，我的生日是四月二十四号。"))
        val result = LocalSemanticService(archive,Encoder()).retrieve(owner,chat.id,"生日")
        assertEquals("semantic_then_lexical",result.route)
        val correction = result.originals.single()
        assertEquals(messages.last().id,correction.messageId)
        assertTrue(correction.text.contains("四月二十四号"))
        assertEquals(messages.last().content.substring(correction.startOffset,correction.endOffset),correction.text)
        assertTrue(result.originals.sumOf { it.text.length } <= 12000)
        assertEquals(messages,archive.history(owner,chat.id).messages)
    }
    @Test fun aNarrowDenialMustRetainTheCorrectValueLaterInTheSameIndependentOriginal() = runBlocking {
        val texts = listOf("我的生日是九月十八日。","不是九月十八日。正确的生日是四月二十四号。")
        val messages = prepare(texts,listOf(texts.first(),"不是九月十八日"))
        val result = LocalSemanticService(archive,Encoder()).retrieve(owner,chat.id,"生日")
        assertEquals("semantic",result.route)
        val correction = result.originals.single()
        assertEquals(messages.last().id,correction.messageId)
        assertTrue("A grounded denial must not discard the corrected value in its original context",
            correction.text.contains("四月二十四号"))
        assertEquals(messages.last().content.substring(correction.startOffset,correction.endOffset),correction.text)
        assertEquals("current_correction",correction.evidenceState)
        assertTrue(result.originals.sumOf { it.text.length } <= 12000)
        assertEquals(messages,archive.history(owner,chat.id).messages)
    }
    @Test fun aNarrowAnnotationMustNotReplaceAnAlreadyRetrievedWiderCorrectionOriginal() = runBlocking {
        val texts = listOf("我的生日是九月十八日。","不是九月十八日。正确的生日是四月二十四号。")
        val messages = prepare(texts,listOf(texts.first(),"不是九月十八日"),.5f)
        val result = LocalSemanticService(archive,Encoder()).retrieve(owner,chat.id,"生日")
        assertEquals("semantic_then_lexical",result.route)
        val correction = result.originals.single()
        assertEquals(messages.last().id,correction.messageId)
        assertEquals("Precision supplied the complete source; annotation closure must preserve it",
            messages.last().content,correction.text)
        assertEquals(messages.last().role,correction.role)
        assertEquals(messages.last().createdAt,correction.createdAt)
        assertTrue(result.originals.sumOf { it.text.length } <= 12000)
        assertEquals(messages,archive.history(owner,chat.id).messages)
    }
    @Test fun aLongNarrowCorrectionForcesPrecisionAndPreservesTheCurrentRawSpan() = runBlocking {
        val text = "说明。".repeat(4200) + "不是九月十八日。正确的生日是四月二十四号。" + "结尾。".repeat(1000)
        val texts = listOf("我的生日是九月十八日。",text)
        val messages = prepare(texts,listOf(texts.first(),"不是九月十八日"))
        val result = LocalSemanticService(archive,Encoder()).retrieve(owner,chat.id,"生日")
        assertEquals("semantic_then_lexical",result.route)
        assertEquals("correction_unresolved",result.fallbackReason)
        val correction = result.originals.single()
        assertEquals(messages.last().id,correction.messageId)
        assertTrue(correction.text.contains("四月二十四号"))
        assertEquals(text.substring(correction.startOffset,correction.endOffset),correction.text)
        assertEquals(messages.last().createdAt,correction.createdAt)
        assertEquals(messages.last().role,correction.role)
        assertEquals("current_correction",correction.evidenceState)
        assertTrue(result.originals.sumOf { it.text.length } <= 12000)
        assertEquals(messages,archive.history(owner,chat.id).messages)
    }
    private fun prepare(texts:List<String>,quotes:List<String> = texts,oldScore:Float = 1f):List<Message> {
        require(quotes.size == texts.size)
        val originals = texts.map { archive.append(owner,chat.id,"user",it) }
        val recognition = LocalMemoryRecognitionSqlStore(archive)
        val identity = LocalMemoryRecognitionIdentity(owner,chat.id,"deepseek",chat.model)
        recognition.enqueue(identity)
        repeat(originals.size) {
            val job = recognition.claim(identity)!!
            val source = recognition.original(job)!!
            val window = LocalMemoryRecognitionRules.window(source,0)
            val quote = quotes[originals.indexOfFirst { it.id == source.messageId }]
            val raw = LocalMemoryRawClaim(quote,"profile","user",if (quote == source.text) "affirm" else "deny","user:birthday",1.0,
                false,source.messageId != originals.first().id,null,null,null)
            val annotation = LocalMemoryRecognitionRules.ground(window,raw)!!
            assertTrue(recognition.finish(job,window,listOf(annotation)) { true })
        }
        val vectors = LocalSemanticStore(archive)
        repeat(originals.size) {
            val job = vectors.nextJob(owner,chat.id,"controlled-correction-v1")!!
            val source = archive.recognitionOriginal(owner,chat.id,job.messageId)!!
            val vector = if (source.messageId == originals.first().id)
                floatArrayOf(oldScore,kotlin.math.sqrt(1f - oldScore * oldScore)) else floatArrayOf(.1f,.9949874f)
            assertTrue(vectors.commit(job,source,listOf(SemanticChunk(0,0,1,0,source.text.length,vector)),1,2))
        }
        return originals
    }
    private class Encoder:LocalSemanticEncoder {
        override val modelIdentity = "controlled-correction-v1"
        override val dimensions = 2
        override fun isAvailable() = true
        override fun documentChunkCount(text:String) = 1
        override suspend fun encodeDocument(text:String) = floatArrayOf(1f,0f)
        override suspend fun encodeQuery(query:String) = floatArrayOf(1f,0f)
        override suspend fun encodeDocumentChunks(text:String,startChunk:Int,maxChunks:Int) = listOf(SemanticChunk(0,0,1,0,text.length,floatArrayOf(1f,0f)))
        override suspend fun encodeQueryChunks(query:String) = listOf(SemanticChunk(0,0,1,0,query.length,floatArrayOf(1f,0f)))
    }
}
