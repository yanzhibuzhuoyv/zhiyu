package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.ProactiveForeground
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Controlled vectors verify storage/routing only; real language accuracy belongs to the BGE tests. */
class LocalSemanticStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "local_semantic_test_${UUID.randomUUID()}.sqlite3"
    private var archive = LocalChatDatabase(context,name)
    private val owner = "synthetic-semantic-owner"
    private val chat by lazy { archive.ensureConversation(owner,"synthetic-model",CompanionProfile()) }
    @After fun cleanup() { ProactiveForeground.setVisible(false); archive.close(); context.deleteDatabase(name) }
    private fun encode(store:LocalSemanticStore,id:String,vector:FloatArray= floatArrayOf(1f,0f),model:String="controlled-math-v1") {
        val job = store.nextJob(owner,id,model)!!
        val original = archive.recognitionOriginal(owner,id,job.messageId)!!
        assertTrue(store.commit(job,original,listOf(SemanticChunk(0,0,1,0,original.text.length,vector)),1,2))
    }
    @Test fun controlledVectorsReturnOwnedIndependentOriginalsAndRespectExclusion() = runBlocking {
        val user = archive.append(owner,chat.id,"user","原话：生日九月十八日")
        val assistant = archive.append(owner,chat.id,"assistant","以前AI的猜测：可能六月")
        val store = LocalSemanticStore(archive)
        encode(store,chat.id); encode(store,chat.id)
        val results = store.search(owner,chat.id,"controlled-math-v1",2,listOf(floatArrayOf(1f,0f)),emptySet())
        assertEquals(setOf(user.id,assistant.id),results.map { it.messageId }.toSet())
        results.forEach { hit ->
            val source = if (hit.messageId == user.id) user else assistant
            val raw = archive.semanticRecall(owner,chat.id,hit)
            assertEquals(source.role,raw.role); assertEquals(source.createdAt,raw.createdAt)
            assertEquals(source.content,raw.text)
        }
        assertEquals(listOf(user.id),store.search(owner,chat.id,"controlled-math-v1",2,listOf(floatArrayOf(1f,0f)),setOf(assistant.id)).map { it.messageId })
        assertTrue(store.search("foreign",chat.id,"controlled-math-v1",2,listOf(floatArrayOf(1f,0f)),emptySet()).isEmpty())
    }
    @Test fun metadataColdTransitionRejectsDelayedCommitButPreservesAlreadyEncodedRawSource() = runBlocking {
        val source = archive.append(owner,chat.id,"user","过去的独立原文")
        val store = LocalSemanticStore(archive)
        val job = store.nextJob(owner,chat.id,"controlled-math-v1")!!
        val original = archive.recognitionOriginal(owner,chat.id,source.id)!!
        archive.changeMemory(owner,chat.id,source.id,MemoryChange(0,indexState="cold"))
        assertFalse(store.commit(job,original,listOf(SemanticChunk(0,0,1,0,original.text.length,floatArrayOf(1f,0f))),1,2))
        assertFalse(store.hasVectors(owner,chat.id,"controlled-math-v1"))
        archive.changeMemory(owner,chat.id,source.id,MemoryChange(1,indexState="active"))
        encode(store,chat.id)
        archive.changeMemory(owner,chat.id,source.id,MemoryChange(2,indexState="cold"))
        assertTrue(store.search(owner,chat.id,"controlled-math-v1",2,listOf(floatArrayOf(1f,0f)),emptySet()).isEmpty())
        assertEquals(source.id,store.search(owner,chat.id,"controlled-math-v1",2,listOf(floatArrayOf(1f,0f)),emptySet(),"cold").single().messageId)
        assertEquals(source.content,archive.history(owner,chat.id).messages.single().content)
    }
    @Test fun chunkCursorAndVectorIdentitySurviveRestartWithoutDuplicateOrMergedOriginals() {
        val source = archive.append(owner,chat.id,"user","第一片原文。第二片原文。")
        val store = LocalSemanticStore(archive)
        val job = store.nextJob(owner,chat.id,"controlled-math-v1")!!
        val original = archive.recognitionOriginal(owner,chat.id,source.id)!!
        assertTrue(store.commit(job,original,listOf(SemanticChunk(0,0,1,0,6,floatArrayOf(1f,0f))),2,2))
        val id = chat.id
        archive.close(); archive = LocalChatDatabase(context,name)
        val resumed = LocalSemanticStore(archive)
        val next = resumed.nextJob(owner,id,"controlled-math-v1")!!
        assertEquals(1,next.nextChunk)
        assertEquals(2,next.totalChunks)
        assertEquals(2L,resumed.statistics(owner,id,"controlled-math-v1").knownTotalChunks)
        assertTrue(resumed.commit(next,archive.recognitionOriginal(owner,id,source.id)!!,
            listOf(SemanticChunk(1,1,2,6,source.content.length,floatArrayOf(0f,1f))),2,2))
        assertNull(resumed.nextJob(owner,id,"controlled-math-v1"))
        assertTrue(resumed.isComplete(owner,id,"controlled-math-v1",emptySet()))
        assertFalse(resumed.hasVectors(owner,id,"other-model-v2"))
        assertEquals(listOf(source),archive.history(owner,id).messages)
        archive.readableDatabase.rawQuery("SELECT COUNT(*) FROM semantic_vectors",null).use { assertTrue(it.moveToFirst()); assertEquals(2,it.getInt(0)) }
    }
    @Test fun v1UpgradeAddsIndexesWithoutChangingOldOriginalsOrFts() {
        val source = archive.append(owner,chat.id,"user","生日九月十八日。🐈")
        val id = chat.id
        archive.writableDatabase.execSQL("DROP TABLE semantic_vectors")
        archive.writableDatabase.execSQL("DROP TABLE semantic_jobs")
        archive.writableDatabase.execSQL("DROP TABLE memory_cold_fts")
        archive.writableDatabase.version = 1
        archive.close(); archive = LocalChatDatabase(context,name)
        assertEquals(listOf(source),archive.history(owner,id).messages)
        assertEquals(source.id,archive.recall(owner,id,"生日").single().messageId)
        assertEquals(3,archive.readableDatabase.version)
        assertNotNull(LocalSemanticStore(archive).nextJob(owner,id,"controlled-math-v1"))
    }
    @Test fun readySemanticSearchRunsForQueriesWithoutLiteralRecallTriggers() = runBlocking {
        val source = archive.append(owner,chat.id,"user","我的生日九月十八日")
        val encoder = ControlledEncoder()
        val store = LocalSemanticStore(archive)
        encode(store,chat.id)
        val query = "同我一起庆祝来到世界的日子吧"
        assertFalse(LocalMemoryRules.shouldRetrieve(query))
        val result = LocalSemanticService(archive,encoder).retrieve(owner,chat.id,query)
        assertEquals("semantic",result.route)
        assertNull(result.fallbackReason)
        assertEquals(source.id,result.originals.single().messageId)
        assertEquals(1,encoder.queryCalls)
    }
    @Test fun pendingOrWrongModelAndOversizedQueriesFallBackHonestly() = runBlocking {
        val source = archive.append(owner,chat.id,"user","生日九月十八日")
        val encoder = ControlledEncoder()
        val service = LocalSemanticService(archive,encoder)
        assertEquals("index_pending",service.retrieve(owner,chat.id,"生日").fallbackReason)
        assertEquals(0,encoder.queryCalls)
        encode(LocalSemanticStore(archive),chat.id)
        encoder.tooLong = true
        val result = service.retrieve(owner,chat.id,"生日")
        assertEquals("query_too_long",result.fallbackReason)
        assertEquals(source.id,result.originals.single().messageId)
        encoder.available = false
        assertEquals("model_unavailable",service.retrieve(owner,chat.id,"生日").fallbackReason)
    }
    @Test fun corruptedVectorCannotReplaceTheOriginalOrInventASemanticMatch() = runBlocking {
        val source = archive.append(owner,chat.id,"user","生日九月十八日")
        val store = LocalSemanticStore(archive); encode(store,chat.id)
        archive.writableDatabase.execSQL("UPDATE semantic_vectors SET vector=?",arrayOf(LocalSemanticVectorRules.encode(floatArrayOf(0f,1f),2)))
        val result = LocalSemanticService(archive,ControlledEncoder()).retrieve(owner,chat.id,"生日")
        assertEquals("semantic_failed",result.fallbackReason)
        assertEquals(source.content,result.originals.single().text)
        assertFalse(store.isComplete(owner,chat.id,"controlled-math-v1",emptySet()))
        assertNotNull(store.nextJob(owner,chat.id,"controlled-math-v1"))
        encode(store,chat.id)
        assertEquals("semantic",LocalSemanticService(archive,ControlledEncoder()).retrieve(owner,chat.id,"生日").route)
    }
    @Test fun coldExactFallbackReadsTheArchivedRawSourceWithoutReactivatingIt() = runBlocking {
        val source = archive.append(owner,chat.id,"user","我的生日九月十八日")
        archive.changeMemory(owner,chat.id,source.id,MemoryChange(0,indexState="cold"))
        val encoder = ControlledEncoder().apply { available = false }
        val result = LocalSemanticService(archive,encoder).retrieve(owner,chat.id,"生日")
        assertEquals(source.id,result.originals.single().messageId)
        assertEquals("cold",archive.memoryEntry(owner,chat.id,source.id).indexState)
    }
    @Test fun currentPinnedPromiseProtectsMidMemoryButSupersededOrForeignShaDoesNot() {
        val source = archive.append(owner,chat.id,"assistant","我答应周六陪你一起吃火锅。")
        annotation(source,"mid",true)
        assertEquals("mid",archive.memoryEntry(owner,chat.id,source.id).tier)
        assertTrue(archive.memoryEntry(owner,chat.id,source.id).pinned)
        archive.writableDatabase.execSQL("UPDATE native_memory_annotations SET validity='superseded'")
        assertFalse(archive.memoryEntry(owner,chat.id,source.id).pinned)
        archive.writableDatabase.execSQL("UPDATE native_memory_annotations SET validity='current',source_sha=?",arrayOf("0".repeat(64)))
        assertFalse(archive.memoryEntry(owner,chat.id,source.id).pinned)
        archive.writableDatabase.execSQL("UPDATE native_memory_annotations SET source_sha=?,role='user'",arrayOf(LocalMemoryCodec.sha(LocalMemoryCodec.bytes(source.content))))
        assertFalse(archive.memoryEntry(owner,chat.id,source.id).pinned)
        assertEquals(source,archive.history(owner,chat.id).messages.single())
    }
    @Test fun recognitionMetadataOnlyReportsJobsForTheVerifiedOwnedOriginal() {
        val source = archive.append(owner,chat.id,"user","我的生日是四月二十三号。")
        assertEquals("none",archive.memoryEntry(owner,chat.id,source.id).recognitionMode)
        assertEquals("unavailable",archive.memoryEntry(owner,chat.id,source.id).recognitionStatus)
        LocalMemoryRecognitionSqlStore(archive).enqueue(LocalMemoryRecognitionIdentity(owner,chat.id,"deepseek",chat.model))
        assertEquals("semantic",archive.memoryEntry(owner,chat.id,source.id).recognitionMode)
        assertEquals("pending",archive.memoryEntry(owner,chat.id,source.id).recognitionStatus)
        archive.writableDatabase.execSQL("UPDATE native_memory_jobs SET source_sha=?",arrayOf("0".repeat(64)))
        assertEquals("unavailable",archive.memoryEntry(owner,chat.id,source.id).recognitionStatus)
    }
    @Test fun boundedBatchActuallyConsumesQueueAndRestartContinuesPendingMessages() = runBlocking {
        ProactiveForeground.setVisible(false)
        val originals = (1..3).map { archive.append(owner,chat.id,"user","第${it}条独立原文") }
        val id = chat.id
        val service = LocalSemanticService(archive,ControlledEncoder())
        assertEquals(3L,service.statistics(owner,id).pendingMessages)
        assertEquals(1,service.indexBatch(owner,id,1).completedMessages)
        assertEquals(1L,service.statistics(owner,id).readyMessages)
        archive.close(); archive = LocalChatDatabase(context,name)
        val resumed = LocalSemanticService(archive,ControlledEncoder())
        assertEquals(2,resumed.indexBatch(owner,id,2).completedMessages)
        val progress = resumed.statistics(owner,id)
        assertEquals(3L,progress.readyMessages); assertEquals(3L,progress.vectorChunks)
        assertEquals(0L,progress.pendingMessages); assertEquals(0L,progress.failedMessages)
        assertEquals(3L,progress.knownTotalChunks); assertEquals(0L,progress.unplannedMessages)
        assertEquals(originals,archive.history(owner,id).messages)
        assertEquals(0L,resumed.statistics("another-owner",id).totalMessages)
    }
    @Test fun annotationBonusRanksRelevantIndependentSourcesWithoutRaisingTheirAcceptanceScore() = runBlocking {
        val important = archive.append(owner,chat.id,"user","要记住这是独立的生日原话。")
        val ordinary = archive.append(owner,chat.id,"user","这是另一条聊天原话。")
        annotation(important,"permanent",true)
        val store = LocalSemanticStore(archive)
        repeat(2) {
            val job = store.nextJob(owner,chat.id,"controlled-math-v1")!!
            val source = archive.recognitionOriginal(owner,chat.id,job.messageId)!!
            val vector = if (job.messageId == important.id) floatArrayOf(.96f,.28f) else floatArrayOf(1f,0f)
            assertTrue(store.commit(job,source,listOf(SemanticChunk(0,0,1,0,source.text.length,vector)),1,2))
        }
        val first = store.search(owner,chat.id,"controlled-math-v1",2,listOf(floatArrayOf(1f,0f)),emptySet()).first()
        assertEquals(important.id,first.messageId); assertEquals(.96,first.score,.0001)
        assertEquals(1.15,first.relevanceFactor,.0001)
        assertTrue(store.search(owner,chat.id,"controlled-math-v1",2,listOf(floatArrayOf(1f,0f)),setOf(ordinary.id),minimumScore=.98).isEmpty())
        archive.writableDatabase.execSQL("UPDATE native_memory_annotations SET validity='superseded'")
        assertEquals(ordinary.id,store.search(owner,chat.id,"controlled-math-v1",2,listOf(floatArrayOf(1f,0f)),emptySet()).first().messageId)
    }
    @Test fun eightUncertainVectorsCannotCrowdOutAnIndependentPreciseOriginal() = runBlocking {
        val medium = (1..8).map { archive.append(owner,chat.id,"user","古老的独立信息第${it}条") }
        val precise = archive.append(owner,chat.id,"user","我的生日是四月二十四号。")
        val store = LocalSemanticStore(archive)
        repeat(9) {
            val job = store.nextJob(owner,chat.id,"controlled-math-v1")!!
            val source = archive.recognitionOriginal(owner,chat.id,job.messageId)!!
            val vector = if (source.messageId == precise.id) floatArrayOf(0f,1f) else floatArrayOf(.5f,.8660254f)
            assertTrue(store.commit(job,source,listOf(SemanticChunk(0,0,1,0,source.text.length,vector)),1,2))
        }
        val result = LocalSemanticService(archive,ControlledEncoder()).retrieve(owner,chat.id,"生日日期")
        assertEquals("semantic_then_lexical",result.route)
        assertEquals("score_below_threshold",result.fallbackReason)
        assertEquals(8,result.originals.size)
        val exact = result.originals.single { it.messageId == precise.id }
        assertEquals(precise.content,exact.text); assertEquals(precise.createdAt,exact.createdAt)
        assertTrue(result.originals.any { candidate -> medium.any { it.id == candidate.messageId } })
        assertEquals(result.originals.size,result.originals.map { it.messageId }.toSet().size)
    }
    @Test fun v2UpgradeRetainsPartialVectorsAndCursorWhenTotalWasNotYetPersisted() {
        val source = archive.append(owner,chat.id,"user","第一片原文。第二片原文。")
        val store = LocalSemanticStore(archive)
        val job = store.nextJob(owner,chat.id,"controlled-math-v1")!!
        assertTrue(store.commit(job,archive.recognitionOriginal(owner,chat.id,source.id)!!,
            listOf(SemanticChunk(0,0,1,0,6,floatArrayOf(1f,0f))),2,2))
        archive.writableDatabase.execSQL("ALTER TABLE semantic_jobs RENAME TO semantic_jobs_newer")
        archive.writableDatabase.execSQL("CREATE TABLE semantic_jobs (owner TEXT NOT NULL,conversation_id TEXT NOT NULL,message_id TEXT NOT NULL REFERENCES messages(id),model_id TEXT NOT NULL,raw_sha TEXT NOT NULL,revision INTEGER NOT NULL,next_chunk INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'pending',attempts INTEGER NOT NULL DEFAULT 0,retry_at INTEGER NOT NULL DEFAULT 0,processed_at INTEGER NOT NULL DEFAULT 0,error_code TEXT,PRIMARY KEY(owner,message_id,model_id))")
        val fields = "owner,conversation_id,message_id,model_id,raw_sha,revision,next_chunk,state,attempts,retry_at,processed_at,error_code"
        archive.writableDatabase.execSQL("INSERT INTO semantic_jobs($fields) SELECT $fields FROM semantic_jobs_newer")
        archive.writableDatabase.execSQL("DROP TABLE semantic_jobs_newer")
        archive.writableDatabase.version = 2
        val id = chat.id
        archive.close(); archive = LocalChatDatabase(context,name)
        val resumed = LocalSemanticStore(archive)
        val next = resumed.nextJob(owner,id,"controlled-math-v1")!!
        assertEquals(1,next.nextChunk); assertNull(next.totalChunks)
        assertTrue(resumed.commit(next,archive.recognitionOriginal(owner,id,source.id)!!,
            listOf(SemanticChunk(1,1,2,6,source.content.length,floatArrayOf(0f,1f))),2,2))
        assertEquals(3,archive.readableDatabase.version)
        assertEquals(2L,resumed.statistics(owner,id,"controlled-math-v1").vectorChunks)
        assertEquals(listOf(source),archive.history(owner,id).messages)
    }
    @Test fun croppedStrongSemanticSpanUsesPrecisionToRecoverTheSameOriginalTail() = runBlocking {
        val source = archive.append(owner,chat.id,"user","a".repeat(15000) + "我的生日九月十八日")
        val store = LocalSemanticStore(archive)
        encode(store,chat.id)
        val result = LocalSemanticService(archive,ControlledEncoder()).retrieve(owner,chat.id,"生日")
        assertEquals("semantic_then_lexical",result.route)
        assertEquals("semantic_context_incomplete",result.fallbackReason)
        val recovered = result.originals.single()
        assertEquals(source.id,recovered.messageId); assertTrue(recovered.text.contains("九月十八日"))
        assertEquals(source.content.substring(recovered.startOffset,recovered.endOffset),recovered.text)
        assertEquals(source.content,archive.history(owner,chat.id).messages.single().content)
    }
    @Test fun uncertainWrongSpanDoesNotExcludeAPreciseSpanFromTheSameMessage() = runBlocking {
        val source = archive.append(owner,chat.id,"user","今天心情还好。" + "之后闲聊。".repeat(1000) + "我的生日九月十八日")
        val store = LocalSemanticStore(archive)
        val job = store.nextJob(owner,chat.id,"controlled-math-v1")!!
        assertTrue(store.commit(job,archive.recognitionOriginal(owner,chat.id,source.id)!!,
            listOf(SemanticChunk(0,0,1,0,7,floatArrayOf(.5f,.8660254f)),
                SemanticChunk(1,1,2,7,source.content.length,floatArrayOf(0f,1f))),2,2))
        val result = LocalSemanticService(archive,ControlledEncoder()).retrieve(owner,chat.id,"生日")
        assertEquals("semantic_then_lexical",result.route)
        assertTrue(result.originals.single().text.contains("九月十八日"))
        assertEquals(source.id,result.originals.single().messageId)
        assertTrue(result.originals.single().startOffset > 7)
    }
    @Test fun oversizedSemanticQueryStillRecoversTheTailQuestionWithinOwnerScope() = runBlocking {
        val source = archive.append(owner,chat.id,"user","我的生日是九月十八日。")
        val foreign = archive.ensureConversation("foreign-long-query-owner","synthetic-model",CompanionProfile())
        archive.append("foreign-long-query-owner",foreign.id,"user","我的生日是四月二十四号。")
        encode(LocalSemanticStore(archive),chat.id)
        val encoder = ControlledEncoder().apply { tooLong = true }
        val query = "河边天气闲聊。".repeat(1800) + "你记得我的生日吗？"
        val result = LocalSemanticService(archive,encoder).retrieve(owner,chat.id,query)
        assertEquals(1,encoder.queryCalls)
        assertEquals("query_too_long",result.fallbackReason)
        assertEquals("lexical_fallback",result.route)
        assertEquals(source.id,result.originals.single().messageId)
        assertEquals(source.content,result.originals.single().text)
        assertEquals(source.role,result.originals.single().role); assertEquals(source.createdAt,result.originals.single().createdAt)
        assertEquals(listOf(source),archive.history(owner,chat.id).messages)
    }
    @Test fun modelUnavailableColdFallbackKeepsTheBirthdayAcrossAQueryWindowBoundary() = runBlocking {
        val source = archive.append(owner,chat.id,"user","我的生日是九月十八日。")
        archive.changeMemory(owner,chat.id,source.id,MemoryChange(0,indexState="cold"))
        val encoder = ControlledEncoder().apply { available = false }
        val result = LocalSemanticService(archive,encoder).retrieve(owner,chat.id,"a".repeat(1023) + "生日")
        assertEquals("model_unavailable",result.fallbackReason)
        assertEquals(source.id,result.originals.single().messageId)
        assertEquals(source.content,result.originals.single().text)
        assertEquals("cold",archive.memoryEntry(owner,chat.id,source.id).indexState)
        assertEquals(0,encoder.queryCalls)
    }
    private fun annotation(source:Message,tier:String,pinned:Boolean) {
        val rawSha = LocalMemoryCodec.sha(LocalMemoryCodec.bytes(source.content))
        archive.writableDatabase.execSQL("""INSERT INTO native_memory_annotations
            (id,owner,conversation_id,message_id,role,source_created_at,source_sha,source_revision,
             span_start,span_end,span_sha,kind,subject,polarity,topic_key,confidence,explicit_keep,
             correction,validity,tier,pinned,version)
            VALUES(?,?,?,?,?,?,?,0,0,?,?, 'promise',?,'affirm','synthetic-topic',1,0,0,'current',?,?, 'synthetic-v1')""",
            arrayOf<Any>(UUID.randomUUID().toString(),owner,chat.id,source.id,source.role,source.createdAt,rawSha,
                source.content.length,rawSha,source.role,tier,if (pinned) 1 else 0))
    }
    private class ControlledEncoder:LocalSemanticEncoder {
        override val modelIdentity = "controlled-math-v1"
        override val dimensions = 2
        var available = true; var queryCalls = 0; var tooLong = false
        override fun isAvailable() = available
        override fun documentChunkCount(text:String) = 1
        override suspend fun encodeDocument(text:String) = floatArrayOf(1f,0f)
        override suspend fun encodeQuery(query:String) = floatArrayOf(1f,0f)
        override suspend fun encodeDocumentChunks(text:String,startChunk:Int,maxChunks:Int) = listOf(SemanticChunk(0,0,1,0,text.length,floatArrayOf(1f,0f)))
        override suspend fun encodeQueryChunks(query:String):List<SemanticChunk> {
            queryCalls++; if (tooLong) throw SemanticInputTooLong("synthetic overlong query")
            return listOf(SemanticChunk(0,0,1,0,query.length,floatArrayOf(1f,0f)))
        }
    }
}
