package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real isolated SQLite and contact state; the provider is synthetic and never uses a real Key. */
class ContinuousBatchRepositoryNativeTest {
    @Test fun replayingAnOldBatchCannotUndoANewerExplicitContactPause(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val inputs = listOf(QueuedTextInput("a".repeat(32), "先别主动联系我"),
                QueuedTextInput("b".repeat(32), "可以主动联系我了"))
            val first = f.repo.sendQueuedTextBatch(chat.id, inputs) { _, _ -> }
            val pauses = ContactPauseStore(f.context)
            assertFalse(pauses.read(chat.id).paused)
            val latest = pauses.apply(chat.id, ContactPauseCommand.PAUSE, "newer-user-pause")
            assertTrue(latest.paused)
            val replay = f.repo.sendQueuedTextBatch(chat.id, inputs) { _, _ -> }
            assertEquals(first, replay)
            assertEquals(1, f.calls)
            assertEquals("An old batch replay must not revoke the user's newer pause", latest, pauses.read(chat.id))
            assertEquals(3, f.db.history(f.owner, chat.id).messages.size)
        }
    }

    @Test fun rejectingASingleBatchMemberCannotApplyItsOldResumeCommand(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val inputs = listOf(QueuedTextInput("1".repeat(32), "先别主动联系我"),
                QueuedTextInput("2".repeat(32), "可以主动联系我了"))
            f.repo.sendQueuedTextBatch(chat.id, inputs) { _, _ -> }
            val pauses = ContactPauseStore(f.context)
            val latest = pauses.apply(chat.id, ContactPauseCommand.PAUSE, "newer-pause-before-rejected-member")
            var callbacks = 0
            val rejected = runCatching {
                val member = inputs.last()
                f.repo.sendQueuedMessage(chat.id, member.content, member.requestId) { callbacks++ }
            }
            assertTrue("A committed batch member must still be rejected by the single-message API", rejected.isFailure)
            assertEquals(0, callbacks)
            assertEquals(1, f.calls)
            assertEquals(3, f.db.history(f.owner, chat.id).messages.size)
            assertEquals("Rejecting an old member must not revoke a newer explicit pause", latest, pauses.read(chat.id))
        }
    }

    @Test fun replayingACompleteSingleMessageCannotUndoANewerExplicitContactPause(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val input = QueuedTextInput("3".repeat(32), "可以主动联系我了")
            val first = f.repo.sendQueuedMessage(chat.id, input.content, input.requestId)
            val pauses = ContactPauseStore(f.context)
            val latest = pauses.apply(chat.id, ContactPauseCommand.PAUSE, "newer-pause-before-single-replay")
            val replay = f.repo.sendQueuedMessage(chat.id, input.content, input.requestId)
            assertEquals(first, replay)
            assertEquals(1, f.calls)
            assertEquals(2, f.db.history(f.owner, chat.id).messages.size)
            assertEquals("Replaying an old single message must not revoke a newer explicit pause", latest, pauses.read(chat.id))
        }
    }

    @Test fun aLaterExplicitTopicChangeCancelsTheEarlierSelfieActionButKeepsBothOriginals(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val inputs = listOf(QueuedTextInput("c".repeat(32), "给我发张自拍"),
                QueuedTextInput("d".repeat(32), "不用发了，换个话题，聊聊今天的新杯子"))
            val result = f.repo.sendQueuedTextBatch(chat.id, inputs) { _, _ -> }
            assertEquals(inputs.map { it.content }, result.submitted.map { it.message.content })
            result.submitted.zip(inputs).forEach { (submitted, input) ->
                assertEquals(input.content, f.db.recognitionOriginal(f.owner, chat.id, submitted.message.id)?.text)
            }
            assertEquals("An abandoned selfie request must not force a missing-inventory reply instead of the new topic", 1, f.calls)
            assertTrue(result.assistant.mediaAttachments.isEmpty())
            assertEquals("我们聊杯子。", result.assistant.content)
            val request = checkNotNull(f.lastRequest).getJSONArray("messages")
            val users = (0 until request.length()).map { request.getJSONObject(it) }.filter { it.getString("role") == "user" }
            assertEquals(inputs.map { it.content }, users.map { it.getString("content") })
            assertTrue(request.getJSONObject(0).getString("content").contains("topic_focus=new"))
        }
    }

    @Test fun cancellationAfterTheFirstInsertRollsBackAllBatchOriginalsIndexesAndAliases(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val preserved = f.db.append(f.owner, chat.id, "user", "这句已经独立保存，不能回滚")
            val before = f.db.conversation(f.owner, chat.id)
            val indexCount = f.count("memory_fts")
            val inputs = listOf(QueuedTextInput("e".repeat(32), "准备插入的第一句"), QueuedTextInput("f".repeat(32), "准备插入的第二句"))
            var sawUncommittedFirst = false
            val outcome = runCatching {
                f.db.appendQueuedTextBatch(f.owner, chat.id, inputs) {
                    if (f.db.history(f.owner, chat.id).messages.any { it.content == inputs.first().content }) {
                        sawUncommittedFirst = true
                        throw CancellationException("synthetic cancellation with the first batch insert visible in the transaction")
                    }
                }
            }
            assertTrue("The guard must run after a real first insert, not only before beginning", sawUncommittedFirst)
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            assertEquals(listOf(preserved), f.db.history(f.owner, chat.id).messages)
            assertEquals(indexCount, f.count("memory_fts"))
            assertEquals(0, f.count("proactive_requests"))
            assertEquals(before.updatedAt, f.db.conversation(f.owner, chat.id).updatedAt)
            inputs.forEach { input ->
                assertNull(f.db.proactiveResult(f.owner, chat.id, "outgoing-${input.requestId}"))
                assertNull(f.db.proactiveResult(f.owner, chat.id, "outgoing-member-${input.requestId}"))
            }
            assertNull(f.db.proactiveResult(f.owner, chat.id, "outgoing-batch-${queuedBatchFingerprint(inputs)}"))
            assertEquals(0, f.calls)
        }
    }

    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = NativeV4Context(base)
        val db = LocalChatDatabase(context, "continuous_batch_${UUID.randomUUID()}.sqlite3")
        val owner = "companion-" + LocalMemoryCodec.sha("deepseek\u0000deepseek-flash".toByteArray())
        var calls = 0
        var lastRequest: JSONObject? = null
        private val store = object : CredentialStore {
            private var saved: SavedConnection? = null
            private var companion: CompanionBinding? = null
            override fun read() = saved
            override fun write(connection: SavedConnection) { saved = connection }
            override fun clear() { saved = null }
            override fun readBinding() = companion
            override fun writeBinding(binding: CompanionBinding) { companion = binding }
        }
        val repo = LocalChatRepository(context, store, DirectProviderClient { _, key, body ->
            assertEquals("synthetic-continuous-batch-only", key)
            val request = JSONObject(body); val messages = request.getJSONArray("messages")
            if ((0 until messages.length()).any { messages.getJSONObject(it).getString("role") == "system" }) {
                calls++; lastRequest = request
            }
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"我们聊杯子。"}}]}"""
        }, database = db, semanticEncoder = object : LocalSemanticEncoder {
            override val modelIdentity = "continuous-batch-no-encoder"
            override val dimensions = 2
            override fun isAvailable() = false
            override fun documentChunkCount(text: String): Int = error("Unused")
            override suspend fun encodeDocument(text: String): FloatArray = error("Unused")
            override suspend fun encodeQuery(query: String): FloatArray = error("Unused")
            override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> = error("Unused")
            override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> = error("Unused")
        }, daily = null)
        suspend fun open(): Conversation {
            repo.login(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-continuous-batch-only", "deepseek-flash", CompanionProfile())
            return repo.conversations().single()
        }
        fun count(table: String): Int {
            require(table in setOf("memory_fts", "proactive_requests"))
            return db.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { check(it.moveToFirst()); it.getInt(0) }
        }
        override fun close() {
            db.close(); context.close()
            val folder = context.filesDir.canonicalFile
            check(folder.parentFile == base.filesDir.canonicalFile && folder.name.startsWith("v4_"))
            check(folder.deleteRecursively())
        }
    }
}
