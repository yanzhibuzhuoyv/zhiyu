package com.zhiyu.app.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID

class CurrentContextNativeTest {
    @Test fun everyRequestUsesTheNewClockAndLastIntentWithoutErasingEarlierOriginals(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            f.repo.sendMessage(chat.id, "现在八点了，我想加班")
            assertTrue(f.system().contains("2026-10-16 21:07:09"))
            f.now = f.now.plusSeconds(60)
            f.repo.sendMessage(chat.id, "先不聊工作了，今天买了一只新杯子")
            assertTrue(f.system().contains("21:08:09"))
            assertTrue(f.system().contains("topic_focus=new"))
            assertEquals(4, f.db.history(f.owner, chat.id).messages.size)
        }
    }
    @Test fun voiceIntentAndConfirmedOcOwnershipAreAvailableEvenWithoutANewPicture(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            LocalVisualRepository(f.context).import(chat.id, picture())
            // An optional creator file may be damaged; the confirmed visual reference is still valid.
            val broken = File(f.context.filesDir, "oc_identity_v1/${chat.id}.json")
            broken.parentFile!!.mkdirs(); broken.writeText("broken optional creator")
            val pair = f.repo.sendMessage(chat.id, "给我发条语音，你记得自己的样子吗")
            assertTrue(f.system().contains("voice_reply_planned=true"))
            assertTrue(f.system().contains("当前已确认的图片就是你的OC形象"))
            assertTrue(pair.second.origin.startsWith("voice_reply:"))
            assertEquals("broken optional creator", broken.readText())
        }
    }
    @Test fun twoConsecutiveOriginalsGetOneAnswerAndAnExactIdempotentReplay(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open(); val inputs = listOf(QueuedTextInput("a".repeat(32), "我刚刚下班。"), QueuedTextInput("b".repeat(32), "对了，想和你聊小猫。"))
            val callbacks = mutableListOf<String>()
            val result = f.repo.sendQueuedTextBatch(chat.id, inputs) { id, _ ->
                assertEquals(2, f.db.history(f.owner, chat.id).messages.count { it.role == "user" })
                callbacks += id
            }
            assertEquals(inputs.map { it.requestId }, callbacks)
            assertEquals(inputs.map { it.content }, result.submitted.map { it.message.content })
            assertEquals(1, f.calls)
            assertEquals(listOf("user", "user", "assistant"), f.db.history(f.owner, chat.id).messages.map { it.role })
            assertTrue(f.system().contains("连续输入"))
            val replay = f.repo.sendQueuedTextBatch(chat.id, inputs) { _, _ -> }
            assertEquals(result, replay); assertEquals(1, f.calls)
            assertTrue(runCatching { f.repo.sendQueuedTextBatch(chat.id, inputs.reversed()) { _, _ -> } }.isFailure)
            assertEquals(1, f.calls)
        }
    }
    @Test fun aCommittedBatchCannotTriggerAnotherGenerationAfterLosingTheCallback(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open(); val inputs = listOf(QueuedTextInput("c".repeat(32), "第一句"), QueuedTextInput("d".repeat(32), "第二句"))
            assertTrue(runCatching { f.repo.sendQueuedTextBatch(chat.id, inputs) { _, _ -> throw CancellationException("lost UI callback") } }.isFailure)
            assertEquals(2, f.db.history(f.owner, chat.id).messages.size)
            assertEquals(0, f.calls)
            assertTrue(runCatching { f.repo.sendQueuedTextBatch(chat.id, inputs) { _, _ -> } }.isFailure)
            assertEquals(0, f.calls)
            assertEquals(inputs.map { it.content }, f.db.history(f.owner, chat.id).messages.map { it.content })
        }
    }
    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = NativeV4Context(base)
        val db = LocalChatDatabase(context, "current_${UUID.randomUUID()}.sqlite3")
        val owner = "companion-" + LocalMemoryCodec.sha("deepseek\u0000deepseek-flash".toByteArray())
        var now = Instant.parse("2026-10-16T13:07:09Z")
        var calls = 0
        var last: JSONObject? = null
        private val store = object : CredentialStore {
            private var saved: SavedConnection? = null
            private var binding: CompanionBinding? = null
            override fun read() = saved
            override fun write(connection: SavedConnection) { saved = connection }
            override fun clear() { saved = null }
            override fun readBinding() = binding
            override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
        }
        val repo = LocalChatRepository(context, store, DirectProviderClient { _, _, body ->
            val parsed = JSONObject(body); val messages = parsed.getJSONArray("messages")
            if ((0 until messages.length()).any { messages.getJSONObject(it).getString("role") == "system" }) { last = parsed; calls++ }
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"听着呢。"}}]}"""
        }, database = db, semanticEncoder = object : LocalSemanticEncoder {
            override val modelIdentity = "current-no-encoder"; override val dimensions = 2
            override fun isAvailable() = false
            override fun documentChunkCount(text: String): Int = error("unused")
            override suspend fun encodeDocument(text: String): FloatArray = error("unused")
            override suspend fun encodeQuery(query: String): FloatArray = error("unused")
            override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> = error("unused")
            override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> = error("unused")
        }, daily = null, runtimeNow = { now }, runtimeZone = { java.time.ZoneId.of("Asia/Shanghai") })
        suspend fun open(): Conversation {
            repo.login(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-current-only", "deepseek-flash", CompanionProfile())
            return repo.conversations().single()
        }
        fun system(): String {
            val rows = checkNotNull(last).getJSONArray("messages")
            return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("role") == "system" }.getString("content")
        }
        override fun close() { db.close(); context.close(); val dir = context.filesDir.canonicalFile
            check(dir.parentFile == base.filesDir.canonicalFile && dir.name.startsWith("v4_")); check(dir.deleteRecursively()) }
    }
    private fun picture(): ByteArray {
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { image.recycle() }
    }
}
