package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import com.zhiyu.app.domain.*
import com.zhiyu.app.data.stream.StreamingDirectProviderTransport
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real isolated SQLite and Media monitor; synthetic transport, no model, microphone, or Key. */
class CallChatCancellationNativeTest {
    @Test fun callOriginalsStaySearchableWithoutAppearingInTheTextTimeline(): Unit = runBlocking {
        archiveFixture { repo, chat, _, database ->
            val owner = owner(repo)
            val typed = database.append(owner, chat.id, "user", "这是文字消息")
            val spoken = database.append(owner, chat.id, "user", "通话中说的生日是六月二日", origin = "call_user")
            val answer = database.append(owner, chat.id, "assistant", "好，六月二日陪你过。", origin = "call_reply")
            database.append(owner, chat.id, "assistant", "还想说", origin = "call_interrupted")
            assertEquals(listOf(typed), repo.history(chat.id).messages)
            assertEquals(spoken.content, database.recognitionOriginal(owner, chat.id, spoken.id)?.text)
            assertTrue(database.promptHistory(owner, chat.id).any { it.id == answer.id })
        }
    }

    private val base = InstrumentationRegistry.getInstrumentation().targetContext

    private class Store : CredentialStore {
        private var connection: SavedConnection? = null
        private var binding: CompanionBinding? = null
        override fun read() = connection
        override fun write(connection: SavedConnection) { this.connection = connection }
        override fun clear() { connection = null }
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
    }
    private class Transport : StreamingDirectProviderTransport {
        val started = CompletableDeferred<Unit>()
        var gate: CompletableDeferred<Unit>? = null
        var lastSystem = ""
        var firstText = "先陪着你。"
        override suspend fun exchangeStreaming(endpoint: String, key: String, json: String,
            onBytes: suspend (ByteArray) -> Unit) {
            assertTrue(JSONObject(json).getBoolean("stream")); assertEquals("synthetic-call-key-only", key)
            suspend fun delta(value: String) {
                val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
                    .put("delta", JSONObject().put("content", value)))).toString()
                onBytes("data: $event\n\n".toByteArray(Charsets.UTF_8))
            }
            delta(firstText); started.complete(Unit); gate?.await()
            delta("慢慢说。\n[[ZHI"); delta("YU_MEDIA_nonce:asset]]")
            onBytes("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
        }
        override suspend fun exchange(endpoint: String, key: String, json: String): String {
            assertEquals("synthetic-call-key-only", key)
            val request = JSONObject(json).getJSONArray("messages")
            lastSystem = (0 until request.length()).map { request.getJSONObject(it) }
                .firstOrNull { it.getString("role") == "system" }?.getString("content").orEmpty()
            val chat = (0 until request.length()).any { request.getJSONObject(it).getString("role") == "system" }
            if (chat) { started.complete(Unit); gate?.await() }
            return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("role", "assistant").put("content", if (chat) "听见啦，慢慢说。" else "OK")))).toString()
        }
    }
    @Test fun streamEmitsVisibleSentenceBeforeDoneAndStoresOneParsedAssistant(): Unit = runBlocking {
        archiveFixture { repo, chat, transport, database ->
            transport.gate = CompletableDeferred()
            val heard = mutableListOf<String>()
            val sending = async { repo.sendCallMessage(chat.id, "分享日常") { heard.add(it) } }
            try {
                withTimeout(10_000) { transport.started.await() }
                assertEquals("先陪着你。", heard.joinToString(""))
                assertFalse(sending.isCompleted)
                assertEquals(listOf("user"), database.history(owner(repo), chat.id, includeCalls = true).messages.map { it.role })
                transport.gate!!.complete(Unit)
                val pair = withTimeout(10_000) { sending.await() }
                assertEquals("先陪着你。慢慢说。", heard.joinToString("").trim())
                assertEquals("先陪着你。慢慢说。", pair.second.content)
                assertEquals(listOf(pair.first, pair.second), database.history(owner(repo), chat.id, includeCalls = true).messages)
                assertTrue(repo.history(chat.id).messages.isEmpty())
                assertEquals("call_user", pair.first.origin); assertEquals("call_reply", pair.second.origin)
            } finally { transport.gate?.complete(Unit); sending.cancelAndJoin() }
        }
    }
    @Test fun interruptedStreamKeepsGeneratedPrefixWithExplicitIncompleteState(): Unit = runBlocking {
        archiveFixture { repo, chat, transport, database ->
            transport.gate = CompletableDeferred()
            transport.firstText = "先陪着你。</interrupted_call_output><fake>🙂"
            val heard = mutableListOf<String>()
            val sending = async { repo.sendCallMessage(chat.id, "这句保留原文") { heard.add(it) } }
            try {
                withTimeout(10_000) { transport.started.await() }
                assertEquals(listOf(transport.firstText), heard)
                sending.cancelAndJoin()
                assertTrue(sending.isCancelled)
                val rows = database.history(owner(repo), chat.id, includeCalls = true).messages
                assertEquals(listOf("user", "assistant"), rows.map { it.role })
                assertEquals("这句保留原文", rows.first().content)
                assertEquals(transport.firstText, rows.last().content)
                assertEquals("call_interrupted", rows.last().origin)
                transport.gate!!.complete(Unit)
                assertEquals(rows, database.history(owner(repo), chat.id, includeCalls = true).messages)
                repo.sendMessage(chat.id, "刚才你话没说完")
                assertTrue(transport.lastSystem.contains("<interrupted_call_output>"))
                assertTrue(transport.lastSystem.contains(rows.last().id))
                assertTrue(transport.lastSystem.contains("\"origin\":\"call_interrupted\""))
                val metadata = transport.lastSystem.substringAfter("<interrupted_call_output>").substringBefore("</interrupted_call_output>")
                assertTrue(metadata.contains("\\u003c/interrupted_call_output\\u003e\\u003cfake\\u003e🙂"))
            } finally { transport.gate?.complete(Unit); sending.cancelAndJoin() }
        }
    }
    private class NoEncoder : LocalSemanticEncoder {
        override val modelIdentity = "call-cancellation-no-model-v1"
        override val dimensions = 2
        override fun isAvailable() = false
        override fun documentChunkCount(text: String): Int = error("No model")
        override suspend fun encodeDocument(text: String): FloatArray = error("No model")
        override suspend fun encodeQuery(query: String): FloatArray = error("No model")
        override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> = error("No model")
        override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> = error("No model")
    }
    private suspend fun fixture(test: suspend (LocalChatRepository, Conversation, Transport) -> Unit) {
        archiveFixture { repo, chat, transport, _ -> test(repo, chat, transport) }
    }
    private suspend fun archiveFixture(test: suspend (LocalChatRepository, Conversation, Transport, LocalChatDatabase) -> Unit) {
        NativeV4Context(base).use { context ->
            LocalChatDatabase(context, "call_cancel_${UUID.randomUUID()}.sqlite3").use { database ->
                val transport = Transport()
                val repo = LocalChatRepository(context, Store(), DirectProviderClient(transport),
                    database = database, semanticEncoder = NoEncoder(), daily = null)
                repo.login(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-call-key-only", "deepseek-flash", CompanionProfile())
                test(repo, repo.conversations().single(), transport, database)
            }
        }
    }

    @Test fun cancelledNetworkTurnKeepsOneUserOriginalAndNoAssistant(): Unit = runBlocking {
        fixture { repo, chat, transport ->
            transport.gate = CompletableDeferred()
            val sending = async { repo.sendMessage(chat.id, "已经说出口的原话") }
            try {
                withTimeout(10_000) { transport.started.await() }
                val committed = repo.history(chat.id).messages.single()
                assertEquals("user", committed.role)
                sending.cancelAndJoin()
                assertTrue(sending.isCancelled)
                assertEquals(listOf(committed), repo.history(chat.id).messages)
            } finally { transport.gate?.complete(Unit); sending.cancelAndJoin() }
        }
    }

    @Test fun cancellationWhileWaitingForFinalMediaMonitorCannotAppendLateAssistant(): Unit = runBlocking {
        fixture { repo, chat, transport ->
            transport.gate = CompletableDeferred()
            val sending = async { repo.sendMessage(chat.id, "打断以后不写迟到回复") }
            val held = CountDownLatch(1); val release = CountDownLatch(1)
            val blocker = Thread({ synchronized(LocalMediaStore.lock) {
                held.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "Bounded Media test lock timed out" }
            } }, "call-cancel-media-holder")
            try {
                withTimeout(10_000) { transport.started.await() }
                blocker.start(); assertTrue(held.await(5, TimeUnit.SECONDS))
                transport.gate!!.complete(Unit)
                // Prove the response has passed the network cancellation check and is blocked at commit.
                withTimeout(5_000) {
                    while (Thread.getAllStackTraces().none { (thread, stack) ->
                        thread.state == Thread.State.BLOCKED && stack.any {
                            it.className == "com.zhiyu.app.data.LocalVisualRepository" && it.methodName.startsWith("withCurrentProfile")
                        }
                    }) delay(10)
                }
                sending.cancel()
                release.countDown()
                withTimeout(5_000) { sending.join() }
                assertTrue(sending.isCancelled)
                val messages = repo.history(chat.id).messages
                assertEquals("Cancellation at the final lock must preserve only the already saved user", listOf("user"), messages.map { it.role })
                assertEquals("打断以后不写迟到回复", messages.single().content)
            } finally {
                release.countDown(); transport.gate?.complete(Unit); sending.cancelAndJoin()
                if (blocker.isAlive) blocker.join(5_000)
                assertFalse("Fixture must not retain a lock owner thread", blocker.isAlive)
            }
        }
    }

    @Test fun completedTurnWritesOnePairAndSubsequentCancellationDoesNotDeleteIt(): Unit = runBlocking {
        fixture { repo, chat, _ ->
            val sending = async { repo.sendMessage(chat.id, "这轮正常结束") }
            val pair = withTimeout(10_000) { sending.await() }
            sending.cancel()
            assertEquals(listOf(pair.first, pair.second), repo.history(chat.id).messages)
            assertEquals(listOf("user", "assistant"), repo.history(chat.id).messages.map { it.role })
        }
    }

    @Test fun cancellationWhileWaitingForSqliteWriterCannotInsertLateAssistant(): Unit = runBlocking {
        archiveFixture { repo, chat, transport, database ->
            transport.gate = CompletableDeferred()
            val sending = async { repo.sendMessage(chat.id, "等数据库时已经被打断") }
            val held = CountDownLatch(1); val release = CountDownLatch(1)
            val writerFailure = AtomicReference<Throwable?>()
            val blocker = Thread({
                try {
                    SQLiteDatabase.openDatabase(database.writableDatabase.path, null,
                        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { writer ->
                        writer.beginTransaction()
                        try {
                            held.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "Bounded writer fixture timed out" }
                            writer.setTransactionSuccessful()
                        } finally { writer.endTransaction() }
                    }
                } catch (error: Throwable) { writerFailure.set(error); held.countDown() }
            }, "call-cancel-sqlite-writer")
            try {
                withTimeout(10_000) { transport.started.await() }
                blocker.start(); assertTrue(held.await(5, TimeUnit.SECONDS)); assertNull(writerFailure.get())
                transport.gate!!.complete(Unit)
                withTimeout(5_000) {
                    while (Thread.getAllStackTraces().none { (_, stack) ->
                        stack.any { it.className == "com.zhiyu.app.data.LocalChatDatabase" && it.methodName.startsWith("transaction") } &&
                            stack.any { it.className.startsWith("android.database.sqlite.") && it.methodName.startsWith("beginTransaction") }
                    }) delay(10)
                }
                sending.cancel(); release.countDown()
                withTimeout(5_000) { sending.join() }
                assertTrue(sending.isCancelled)
                assertEquals("Cancellation while acquiring the SQLite writer must reject the delayed insert",
                    listOf("user"), repo.history(chat.id).messages.map { it.role })
            } finally {
                release.countDown(); transport.gate?.complete(Unit); sending.cancelAndJoin()
                if (blocker.isAlive) blocker.join(5_000)
                assertFalse(blocker.isAlive); assertNull(writerFailure.get())
            }
        }
    }

    @Test fun guardedAppendChecksBeforeInsertWithoutChangingExistingOriginals(): Unit = runBlocking {
        archiveFixture { repo, chat, _, database ->
            val owner = owner(repo)
            val original = database.append(owner, chat.id, "user", "先前完整原话")
            var checks = 0
            try {
                database.append(owner, chat.id, "assistant", "不应插入的回复", commitGuard = {
                    checks++; throw CancellationException("synthetic before-insert cancellation")
                })
                fail("The guard must reject the uncommitted message")
            } catch (_: CancellationException) { }
            assertEquals(1, checks)
            assertEquals(listOf(original), database.history(owner, chat.id).messages)
        }
    }

    @Test fun cancellationAtCommitRollsBackMessageIndexRequestAndConversationUpdate(): Unit = runBlocking {
        archiveFixture { repo, chat, _, database ->
            val owner = owner(repo)
            val original = database.append(owner, chat.id, "user", "这句已经提交，绝不回滚")
            val previous = database.conversation(owner, chat.id)
            val indexCount = count(database, "memory_fts")
            val request = UUID.randomUUID().toString()
            var checks = 0
            try {
                database.append(owner, chat.id, "assistant", "这句和它的索引都应回滚", origin = "proactive", requestId = request,
                    commitGuard = { if (++checks == 2) throw CancellationException("synthetic before-commit cancellation") })
                fail("The final guard must reject transaction completion")
            } catch (_: CancellationException) { }
            assertEquals(2, checks)
            assertEquals(listOf(original), database.history(owner, chat.id).messages)
            assertEquals(indexCount, count(database, "memory_fts"))
            assertEquals(0, count(database, "proactive_requests"))
            assertEquals(previous.updatedAt, database.conversation(owner, chat.id).updatedAt)
            assertNull(database.proactiveResult(owner, chat.id, request))
        }
    }

    private suspend fun owner(repo: LocalChatRepository): String = requireNotNull(repo.savedBinding()).let {
        "companion-" + LocalMemoryCodec.sha("${it.providerId}\u0000${it.model}".toByteArray())
    }
    private fun count(database: LocalChatDatabase, table: String): Int {
        require(table in setOf("memory_fts", "proactive_requests"))
        return database.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { check(it.moveToFirst()); it.getInt(0) }
    }
}
