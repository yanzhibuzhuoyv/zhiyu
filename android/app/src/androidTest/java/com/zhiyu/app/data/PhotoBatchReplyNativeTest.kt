package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real isolated chat repository and SQLite; only the provider response is synthetic. */
class PhotoBatchReplyNativeTest {
    @Test fun aSingleCompoundPhotoRequestKeepsItsOtherQuestion(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val text = "给我发张照片，今天考试没考好，我现在该怎么调整？"
            val result = f.repo.sendMessage(chat.id, text)
            assertEquals("A second intent in one original also needs the actual provider", 1, f.calls)
            assertTrue(result.second.content.contains("今晚先休息"))
            assertEquals(text, result.first.content)
            assertTrue(result.second.mediaAttachments.isEmpty())
        }
    }
    @Test fun aPendingPhotoCannotSwallowTheOtherQuestionInTheSameBatch(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val inputs = listOf(QueuedTextInput("1".repeat(32), "给我发张照片"),
                QueuedTextInput("2".repeat(32), "今天考试没考好，我现在该怎么调整？"))
            val result = f.repo.sendQueuedTextBatch(chat.id, inputs)
            assertEquals("Mixed input needs one actual answer to the later question", 1, f.calls)
            assertTrue("Photo pending must not replace the generated advice", result.assistant.content.contains("今晚先休息"))
            assertTrue(result.assistant.mediaAttachments.isEmpty())
            assertFalse(result.assistant.content.contains("已经发"))
            assertEquals(inputs.map { it.content }, result.submitted.map { it.message.content })
            assertEquals(inputs.map { it.content }, f.db.history(f.owner, chat.id).messages.filter { it.role == "user" }.map { it.content })
            val users = checkNotNull(f.last).getJSONArray("messages").let { rows ->
                (0 until rows.length()).map(rows::getJSONObject).filter { it.getString("role") == "user" }
            }
            assertEquals(inputs.map { it.content }, users.map { it.getString("content") })

            val single = f.repo.sendMessage(chat.id, "发张照片")
            assertEquals("A standalone photo request keeps its quick natural pending reply", 1, f.calls)
            assertTrue(single.second.mediaAttachments.isEmpty())
            assertFalse(single.second.content.contains("今晚先休息"))
        }
    }

    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = NativeV4Context(base)
        val db = LocalChatDatabase(context, "photo_batch_${UUID.randomUUID()}.sqlite3")
        val owner = "companion-" + LocalMemoryCodec.sha("deepseek\u0000deepseek-flash".toByteArray())
        var calls = 0
        var last: JSONObject? = null
        private val credentials = object : CredentialStore {
            private var saved: SavedConnection? = null
            private var companion: CompanionBinding? = null
            override fun read() = saved
            override fun write(connection: SavedConnection) { saved = connection }
            override fun clear() { saved = null }
            override fun readBinding() = companion
            override fun writeBinding(binding: CompanionBinding) { companion = binding }
        }
        val repo = LocalChatRepository(context, credentials, DirectProviderClient { _, key, body ->
            assertEquals("synthetic-photo-batch-only", key)
            val request = JSONObject(body); val rows = request.getJSONArray("messages")
            if ((0 until rows.length()).any { rows.getJSONObject(it).getString("role") == "system" }) {
                calls++; last = request
            }
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"考试没发挥好先别急，今晚先休息，明天再一起看看哪里需要调整。照片等我准备好再给你。"}}]}"""
        }, database = db, semanticEncoder = object : LocalSemanticEncoder {
            override val modelIdentity = "photo-batch-no-encoder"
            override val dimensions = 2
            override fun isAvailable() = false
            override fun documentChunkCount(text: String): Int = error("unused")
            override suspend fun encodeDocument(text: String): FloatArray = error("unused")
            override suspend fun encodeQuery(query: String): FloatArray = error("unused")
            override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> = error("unused")
            override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> = error("unused")
        }, daily = null)
        suspend fun open(): Conversation {
            repo.login(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-photo-batch-only", "deepseek-flash", CompanionProfile())
            return repo.conversations().single()
        }
        override fun close() {
            db.close(); context.close()
            val folder = context.filesDir.canonicalFile
            check(folder.parentFile == base.filesDir.canonicalFile && folder.name.startsWith("v4_"))
            check(folder.deleteRecursively())
        }
    }
}
