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
    @Test fun aNoonContinuationCarriesTheOldNightTimestampAtTheEndOfTheRealProviderPrompt(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val night = f.db.append(f.owner, chat.id, "user", "才十一点多，还早，晚些睡。")
            f.db.writableDatabase.update("messages", android.content.ContentValues().apply {
                put("created_at", "2026-10-18T15:20:00Z")
            }, "id=?", arrayOf(night.id))
            val oldReply = f.db.append(f.owner, chat.id, "assistant", "这么晚了，吃点东西再休息。")
            f.db.writableDatabase.update("messages", android.content.ContentValues().apply {
                put("created_at", "2026-10-18T15:20:05Z")
            }, "id=?", arrayOf(oldReply.id))
            f.now = Instant.parse("2026-10-19T04:53:00Z")
            f.answer = "吃好了呀。"
            f.repo.sendMessage(chat.id, "我吃完饭了") { submitted ->
                f.db.writableDatabase.update("messages", android.content.ContentValues().apply {
                    put("created_at", "2026-10-19T04:53:00Z")
                }, "id=?", arrayOf(submitted.id))
            }
            if (InstrumentationRegistry.getArguments().getString("temporal_export") == "true") {
                val status = android.os.Bundle().apply { putString("temporal_payload_b64",
                    android.util.Base64.encodeToString(checkNotNull(f.last).toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)) }
                InstrumentationRegistry.getInstrumentation().sendStatus(2, status)
            }
            assertTrue(f.system().contains("now_local=2026-10-19T12:53:00+08:00"))
            assertTrue(f.system().contains("sent_local=2026-10-18T23:20:00+08:00"))
            assertTrue(f.system().contains("different_local_date=true"))
            assertTrue(f.system().trimEnd().endsWith("</trusted_temporal_grounding>"))
            val messages = checkNotNull(f.last).getJSONArray("messages")
            val presentedNight = messages.getJSONObject(1).getString("content")
            assertTrue(presentedNight.startsWith("[本条消息发送时间：2026-10-18T23:20:00+08:00"))
            assertTrue(presentedNight.endsWith("才十一点多，还早，晚些睡。"))
            assertTrue(messages.getJSONObject(messages.length() - 1).getString("content")
                .startsWith("[本轮设备当前时间：2026-10-19T12:53:00+08:00；Asia/Shanghai；当前时段中午"))
            assertTrue(messages.getJSONObject(messages.length() - 1).getString("content")
                .contains("[本条消息发送时间：2026-10-19T12:53:00+08:00"))
            assertEquals("才十一点多，还早，晚些睡。", f.db.history(f.owner, chat.id).messages.first().content)
            assertEquals(1, f.calls)
        }
    }
    @Test fun ordinaryDialogueCannotCommitAnEightHourWrongExplicitCurrentClockClaim(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open(); f.now = Instant.parse("2026-10-19T01:07:09Z")
            f.answer = "现在凌晨一点了。新买的书是什么？"
            val reply = f.repo.sendMessage(chat.id, "我刚到书店").second
            assertFalse(reply.content.contains("凌晨一点"))
            assertTrue(reply.content.contains("上午09:07"))
            assertTrue(reply.content.endsWith("新买的书是什么？"))
            assertEquals(1, f.calls)
            assertEquals("我刚到书店", f.db.history(f.owner, chat.id).messages.first().content)
        }
    }
    @Test fun displayReceiptValidationDoesNotDependOnTheTwoMegabyteHistoryPage(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val u1 = f.db.append(f.owner, chat.id, "user", "甲".repeat(5_000))
            val a1 = f.db.append(f.owner, chat.id, "assistant", "乙".repeat(350_000))
            val u2 = f.db.append(f.owner, chat.id, "user", "丙".repeat(5_000))
            val a2 = f.db.append(f.owner, chat.id, "assistant", "丁".repeat(350_000))
            val before = f.db.history(f.owner, chat.id)
            assertTrue("Fixture must really exceed the history-page byte budget", before.messages.none { it.id == a1.id })
            f.repo.recordReplyTurn(chat.id, listOf(u1.id, u2.id), listOf(a1.id, a2.id))
            assertEquals(before, f.db.history(f.owner, chat.id))
            assertEquals(listOf(u1.id, u2.id, a1.id, a2.id), ReplyTurnDisplayStore(f.context)
                .order(chat.id, listOf(u1, a1, u2, a2)).map { it.id })
        }
    }
    @Test fun continuousTurnDisplaySurvivesReloadWithoutChangingTheRawArchive(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val first = f.repo.sendMessage(chat.id, "先说第一件事")
            val next = f.repo.sendMessage(chat.id, "再补充第二件事")
            val originals = f.db.history(f.owner, chat.id).messages
            f.repo.recordReplyTurn(chat.id, listOf(first.first.id, next.first.id), listOf(first.second.id, next.second.id))
            assertEquals(listOf("user", "user", "assistant", "assistant"), f.repo.history(chat.id).messages.map { it.role })
            assertEquals(originals, f.db.history(f.owner, chat.id).messages)
            val reloaded = ReplyTurnDisplayStore(f.context).order(chat.id, originals)
            assertEquals(listOf(first.first.id, next.first.id, first.second.id, next.second.id), reloaded.map { it.id })
        }
    }
    @Test fun standaloneClockQuestionDoesNotAskAModelToInventOrDenyTheTime(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val first = f.repo.sendMessage(chat.id, "现在几点")
            assertEquals("现在是21:07:09。", first.second.content)
            f.now = f.now.plusSeconds(61)
            val next = f.repo.sendMessage(chat.id, "现在几点")
            assertEquals("现在是21:08:10。", next.second.content)
            assertEquals(0, f.calls)
            assertEquals(listOf("user", "assistant", "user", "assistant"), f.db.history(f.owner, chat.id).messages.map { it.role })
        }
    }
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
        var answer = "听着呢。"
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
            JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("role", "assistant").put("content", answer)))).toString()
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
