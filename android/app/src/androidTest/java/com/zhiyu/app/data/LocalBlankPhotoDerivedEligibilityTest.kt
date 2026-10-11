package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.ProactiveForeground
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Empty/whitespace photo captions remain exact originals; only synthetic UUID archives and encoders. */
class LocalBlankPhotoDerivedEligibilityTest {
    private val context = NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext)
    private val name = "blank_photo_derived_${UUID.randomUUID()}.sqlite3"
    private val archive = LocalChatDatabase(context, name)
    private val owner = "synthetic-photo-eligibility-owner"
    private val chat = archive.ensureConversation(owner, "synthetic-bound-model", null)
    private val foregroundBefore = ProactiveForeground.isVisible()
    @After fun cleanup() { ProactiveForeground.setVisible(foregroundBefore); archive.close(); context.deleteDatabase(name); context.close() }
    private fun photo(caption: String): Message {
        val bitmap = android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output); bitmap.recycle(); output.toByteArray()
        }
        val attachment = LocalMediaStore(context).ingest("image",bytes,"你发送的图片",purpose="chat")
        return archive.append(owner, chat.id, "user", caption, attachments = listOf(attachment))
    }

    @Test fun blankPhotoCaptionsDoNotPoisonSemanticCompletionOrCreateAnyFakeVectors() = runBlocking {
        ProactiveForeground.setVisible(false)
        val empty = photo(""); val spaced = photo(" \n\t\u3000 ")
        val source = archive.append(owner, chat.id, "user", "星河暗号是碧绿松针。")
        val before = archive.history(owner, chat.id).messages
        val encoder = Encoder(); val service = LocalSemanticService(archive, encoder)
        val batch = service.indexBatch(owner, chat.id, 8)
        assertNull("无文字的原图应跳过而非编码失败", batch.reason)
        assertEquals(1, batch.encodedChunks); assertEquals(1, batch.completedMessages)
        assertEquals(2, batch.skippedMessages)
        assertEquals(listOf(source.content), encoder.documents)
        val stats = service.statistics(owner, chat.id)
        assertEquals(3L, stats.totalMessages); assertEquals(1L, stats.readyMessages)
        assertEquals(0L, stats.failedMessages); assertEquals(0L, stats.pendingMessages)
        assertEquals(2L, stats.skippedMessages)
        assertEquals(1L, stats.knownTotalChunks); assertEquals(0L, stats.unplannedMessages)
        assertTrue(LocalSemanticStore(archive).isComplete(owner, chat.id, encoder.modelIdentity, emptySet()))
        assertEquals(LocalRetrievalStatus.OK, service.retrieve(owner, chat.id, source.content).status)
        listOf(empty, spaced).forEach { item ->
            assertEquals(0L, count("semantic_vectors", item.id))
            assertEquals("skipped", state("semantic_jobs", "state", item.id))
        }
        assertEquals(before, archive.history(owner, chat.id).messages)
        val repeated = service.indexBatch(owner, chat.id, 8)
        assertEquals(0, repeated.encodedChunks); assertEquals(0, repeated.skippedMessages)
        assertEquals(listOf(source.content), encoder.documents)
    }

    @Test fun imageOnlyArchiveFinishesWithZeroTextVectorsAndDoesNotPretendToHaveAPendingIndex() = runBlocking {
        ProactiveForeground.setVisible(false)
        val source = photo(""); val encoder = Encoder(); val service = LocalSemanticService(archive, encoder)
        val batch = service.indexBatch(owner, chat.id, 4)
        assertNull(batch.reason); assertEquals(0, batch.encodedChunks); assertEquals(0, batch.completedMessages)
        assertEquals(1, batch.skippedMessages)
        assertTrue(encoder.documents.isEmpty())
        val stats = service.statistics(owner, chat.id)
        assertEquals(0L, stats.failedMessages); assertEquals(0L, stats.pendingMessages)
        assertEquals(0L, stats.knownTotalChunks); assertEquals(0L, stats.vectorChunks)
        assertTrue(LocalSemanticStore(archive).isComplete(owner, chat.id, encoder.modelIdentity, emptySet()))
        val result = service.retrieve(owner, chat.id, "暗号是什么")
        assertEquals(LocalRetrievalStatus.OK, result.status)
        assertTrue(result.originals.isEmpty()); assertEquals(0, encoder.queryCalls)
        assertEquals("no_text_sources", result.fallbackReason)
        assertEquals(listOf(source), archive.history(owner, chat.id).messages)
    }

    @Test fun recognitionSkipsBlankCaptionsWithoutACloudCallAndStillProcessesTheNextText() = runBlocking {
        val first = photo(""); val second = photo("  \n\t")
        val text = archive.append(owner, chat.id, "user", "今天慢慢散步。")
        val originals = archive.history(owner, chat.id).messages
        val store = LocalMemoryRecognitionSqlStore(archive)
        val identity = LocalMemoryRecognitionIdentity(owner, chat.id, "synthetic-provider", chat.model)
        val calls = mutableListOf<String>()
        val engine = LocalMemoryRecognitionEngine(store, LocalMemoryRecognizer { window, _ ->
            calls += window.text; emptyList()
        }, MutableStateFlow(false)) { true }
        assertEquals(3, engine.run(identity, 8))
        assertEquals(listOf(text.content), calls)
        assertFalse(store.pending(identity))
        assertEquals(0, engine.run(identity, 8)); assertEquals(listOf(text.content), calls)
        listOf(first, second).forEach { item ->
            assertEquals("skipped", state("native_memory_jobs", "status", item.id))
            assertEquals(0L, count("native_memory_annotations", item.id))
        }
        assertEquals(originals, archive.history(owner, chat.id).messages)
    }

    private fun count(table: String, message: String): Long = archive.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $table WHERE owner=? AND conversation_id=? AND message_id=?", arrayOf(owner, chat.id, message)).use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private fun state(table: String, column: String, message: String): String = archive.readableDatabase.rawQuery(
        "SELECT $column FROM $table WHERE owner=? AND conversation_id=? AND message_id=?", arrayOf(owner, chat.id, message)).use {
        assertTrue(it.moveToFirst()); it.getString(0)
    }
    private class Encoder : LocalSemanticEncoder {
        override val modelIdentity = "blank-photo-eligibility-controlled-v1"
        override val dimensions = 2
        val documents = mutableListOf<String>(); var queryCalls = 0
        override fun isAvailable() = true
        override fun documentChunkCount(text: String): Int { require(text.isNotBlank()); return 1 }
        override suspend fun encodeDocument(text: String) = floatArrayOf(1f, 0f)
        override suspend fun encodeQuery(query: String) = floatArrayOf(1f, 0f)
        override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> {
            require(text.isNotBlank()); documents += text
            return listOf(SemanticChunk(0, 0, 1, 0, text.length, floatArrayOf(1f, 0f)))
        }
        override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> {
            queryCalls++; return listOf(SemanticChunk(0, 0, 1, 0, query.length, floatArrayOf(1f, 0f)))
        }
    }
}
