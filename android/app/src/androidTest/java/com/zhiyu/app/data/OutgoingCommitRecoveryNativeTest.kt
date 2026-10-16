package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Real isolated SQLite; cancellation is forced at the user-commit return boundary, without cloud. */
class OutgoingCommitRecoveryNativeTest {
    @Test fun aRecordedVoiceIsSentOnTheFirstAttemptAndKeepsItsPlayableOriginal(): Unit = runBlocking {
        Fixture().use { test ->
            val chat = test.open()
            val voices = com.zhiyu.app.data.voicemessages.VoiceMessageStore(test.context)
            val draft = voices.recordDraft(FloatArray(1600) { .25f }, 16000, "今天到家了")
            val pair = test.repository.sendQueuedMessage(chat.id, "今天到家了", "d".repeat(32), null, draft.id) { }
            assertEquals("voice_user:${draft.id}", pair.first.origin)
            assertNotNull(com.zhiyu.app.data.voicemessages.VoiceMessageOrigins.clipId(pair.second.origin))
            assertEquals(pair.first.id, voices.find(draft.id)?.messageId)
            assertEquals(test.owner, voices.find(draft.id)?.owner)
            test.repository.discardVoiceDraft(draft.id)
            assertEquals(1600, voices.read(draft.id).samples.size)
            assertEquals(.25f, voices.read(draft.id).samples.first(), .00004f)
            assertEquals(pair, test.repository.sendQueuedMessage(chat.id, "今天到家了", "d".repeat(32), null, draft.id) { })
            assertEquals(1, test.calls.get())
        }
    }
    @Test fun aVoiceCannotBeSentWithAnUnrelatedTranscript(): Unit = runBlocking {
        Fixture().use { test ->
            val chat = test.open()
            val voices = com.zhiyu.app.data.voicemessages.VoiceMessageStore(test.context)
            val draft = voices.recordDraft(FloatArray(1600) { .1f }, 16000, "录音对应的原话")
            assertTrue(runCatching {
                test.repository.sendQueuedMessage(chat.id, "被改掉的原话", "e".repeat(32), null, draft.id) { fail() }
            }.isFailure)
            assertEquals(0, test.calls.get()); assertTrue(test.repository.history(chat.id).messages.isEmpty())
            assertEquals(1600, voices.read(draft.id).samples.size)
        }
    }
    @Test fun aBriefCallUsesRecentContextWhileMemoryQuestionsKeepRetrieval(): Unit = runBlocking {
        Fixture().use { test ->
            val chat = test.open()
            test.repository.sendCallMessage(chat.id, "喂") { }
            assertEquals("call_recent_only", test.repository.lastRetrievalRoute?.route)
            assertEquals(LocalRetrievalStatus.NOT_SEARCHED, test.repository.lastRetrievalRoute?.status)
            assertTrue(test.repository.history(chat.id).messages.isEmpty())
            assertEquals(2, test.database.promptHistory(test.owner, chat.id).size)
            test.repository.sendCallMessage(chat.id, "你还记得我生日是哪天吗") { }
            assertNotEquals("call_recent_only", test.repository.lastRetrievalRoute?.route)
        }
    }
    private class Store : CredentialStore {
        private var connection: SavedConnection? = null
        private var binding: CompanionBinding? = null
        override fun read() = connection
        override fun write(connection: SavedConnection) { this.connection = connection }
        override fun clear() { connection = null }
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
    }
    private class NoEncoder : LocalSemanticEncoder {
        override val modelIdentity = "outgoing-recovery-no-model-v1"
        override val dimensions = 2
        override fun isAvailable() = false
        override fun documentChunkCount(text: String): Int = error("No model")
        override suspend fun encodeDocument(text: String): FloatArray = error("No model")
        override suspend fun encodeQuery(query: String): FloatArray = error("No model")
        override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> = error("No model")
        override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> = error("No model")
    }
    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = NativeV4Context(base)
        val database = LocalChatDatabase(context, "outgoing_${UUID.randomUUID()}.sqlite3")
        val calls = AtomicInteger()
        var lastChat: JSONObject? = null
        val owner = "companion-" + LocalMemoryCodec.sha("deepseek\u0000deepseek-flash".toByteArray())
        val repository = LocalChatRepository(context, Store(), DirectProviderClient { _, key, json ->
            assertEquals("synthetic-outgoing-key-only", key)
            val rows = JSONObject(json).getJSONArray("messages")
            if ((0 until rows.length()).any { rows.getJSONObject(it).getString("role") == "system" }) {
                calls.incrementAndGet(); lastChat = JSONObject(json)
            }
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"听见啦。"}}]}"""
        }, database = database, semanticEncoder = NoEncoder(), daily = null)
        suspend fun open(): Conversation {
            repository.login(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-outgoing-key-only", "deepseek-flash", CompanionProfile())
            return repository.conversations().single()
        }
        override fun close() {
            database.close(); context.close()
            val folder = context.filesDir.canonicalFile
            check(folder.parentFile == base.filesDir.canonicalFile && folder.name.startsWith("v4_"))
            check(folder.deleteRecursively())
        }
    }

    @Test fun cancelledReturnAfterUserCommitCannotRepeatTheOriginalOrProviderRequest(): Unit = runBlocking {
        Fixture().use { test ->
            val chat = test.open()
            val request = "a".repeat(32)
            val armed = AtomicBoolean(true)
            val cancelledAfterCommit = AtomicBoolean(false)
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    Dispatchers.Default.dispatch(context, Runnable {
                        if (armed.get() && test.database.history(test.owner, chat.id).messages.any { it.role == "user" } &&
                            armed.compareAndSet(true, false)) {
                            cancelledAfterCommit.set(true)
                            context[Job]?.cancel(CancellationException("synthetic cancellation after durable user commit"))
                        }
                        block.run()
                    })
                }
            }
            val first = async(dispatcher) {
                // Deliberately lose the in-memory callback, as in process/epoch abandonment.
                test.repository.sendQueuedMessage(chat.id, "只说一次的完整原话🙂", request, null, null) { }
            }
            try {
                withTimeout(10_000) { first.join() }
                assertTrue("The fixture must observe a real committed row before cancelling", cancelledAfterCommit.get())
                assertTrue(first.isCancelled)
                val original = test.database.history(test.owner, chat.id).messages.single()
                assertEquals("只说一次的完整原话🙂", original.content)
                assertEquals(0, test.calls.get())
                val submitted = mutableListOf<Message>()
                val retry = runCatching {
                    test.repository.sendQueuedMessage(chat.id, original.content, request, null, null, submitted::add)
                }
                assertTrue("An already committed original must pause rather than start a new generation", retry.isFailure)
                assertEquals(listOf(original), submitted)
                assertEquals(listOf(original), test.database.history(test.owner, chat.id).messages)
                assertEquals("A retry must not repeat a possibly paid provider request", 0, test.calls.get())
            } finally { first.cancelAndJoin() }
        }
    }

    @Test fun completedRequestReturnsItsExactPairWithoutAnotherOriginalOrGeneration(): Unit = runBlocking {
        Fixture().use { test ->
            val chat = test.open(); val request = "b".repeat(32)
            val first = test.repository.sendQueuedMessage(chat.id, "正常完成的一句", request, null, null) { }
            val submitted = mutableListOf<Message>()
            val replay = test.repository.sendQueuedMessage(chat.id, first.first.content, request, null, null, submitted::add)
            assertEquals(first, replay)
            assertEquals(listOf(first.first), submitted)
            assertEquals(listOf(first.first, first.second), test.database.history(test.owner, chat.id).messages)
            assertEquals(1, test.calls.get())
        }
    }

    @Test fun aQueueIdCannotBeReusedForDifferentOriginalText(): Unit = runBlocking {
        Fixture().use { test ->
            val chat = test.open(); val request = "c".repeat(32)
            val first = test.repository.sendQueuedMessage(chat.id, "已确认原话", request, null, null) { }
            val rejected = runCatching {
                test.repository.sendQueuedMessage(chat.id, "别的原话", request, null, null) { fail("Mismatched original cannot publish a submission") }
            }
            assertTrue(rejected.isFailure)
            assertEquals(listOf(first.first, first.second), test.database.history(test.owner, chat.id).messages)
            assertEquals(1, test.calls.get())
        }
    }

    @Test fun missingOptionalOcReferenceMetadataDoesNotBlockThePrimaryPhoto(): Unit = runBlocking {
        optionalDamageDoesNotBlockPrimaryPhoto { test, _, reference ->
            val file = File(test.context.filesDir, "local_media/image/$reference.json")
            check(file.isFile && file.delete())
        }
    }

    @Test fun corruptOptionalCreatorManifestDoesNotBlockThePrimaryPhotoOrGetOverwritten(): Unit = runBlocking {
        optionalDamageDoesNotBlockPrimaryPhoto { test, chat, _ ->
            val file = File(test.context.filesDir, "oc_identity_v1/${chat.id}.json")
            check(file.parentFile!!.mkdirs())
            file.writeText("synthetic corrupt optional creator manifest")
        }
    }

    private suspend fun optionalDamageDoesNotBlockPrimaryPhoto(damage: (Fixture, Conversation, String) -> Unit) {
        Fixture().use { test ->
            val chat = test.open()
            val reference = LocalVisualRepository(test.context).import(chat.id, png(0xff883366.toInt()))
            val original = png(0xff2255aa.toInt())
            val draft = ChatPhotoStore(test.context).importBytes(original)
            damage(test, chat, checkNotNull(reference.referenceId))
            val pair = test.repository.sendPhotoMessage(chat.id, "", draft.attachment)
            assertEquals("", pair.first.content)
            assertEquals(listOf(draft.attachment), pair.first.mediaAttachments)
            assertEquals(listOf(pair.first, pair.second), test.database.history(test.owner, chat.id).messages)
            assertArrayEquals(original, LocalMediaStore(test.context).download(draft.attachment.id, draft.attachment.sha256))
            assertEquals(1, test.calls.get())
            val outgoing = checkNotNull(test.lastChat).getJSONArray("messages")
            val imageParts = (0 until outgoing.length()).sumOf { index ->
                outgoing.getJSONObject(index).optJSONArray("content")?.let { parts ->
                    (0 until parts.length()).count { parts.getJSONObject(it).getString("type") == "image_url" }
                } ?: 0
            }
            assertEquals("The usable user photo must still reach the synthetic vision provider", 1, imageParts)
            assertFalse(outgoing.getJSONObject(0).getString("content").contains("同一份原图"))
            val badCreator = File(test.context.filesDir, "oc_identity_v1/${chat.id}.json")
            if (badCreator.exists()) assertEquals("synthetic corrupt optional creator manifest", badCreator.readText())
        }
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try { ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
        } } finally { bitmap.recycle() }
    }
}
