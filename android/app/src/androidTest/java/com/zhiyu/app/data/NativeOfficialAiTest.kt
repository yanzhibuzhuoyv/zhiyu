package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit emulator-only probe. The operator supplies a transient private key via stdin. */
class NativeOfficialAiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.filesDir, "zhiyu_native_probe")
    private val keyFile = File(folder, "key")
    private val modelFile = File(folder, "model")
    private class ProbeStore : CredentialStore {
        private var saved: SavedConnection? = null
        private var selected: CompanionBinding? = null
        override fun read() = saved
        override fun write(connection: SavedConnection) { saved = connection }
        override fun clear() { saved = null }
        override fun readBinding() = selected
        override fun writeBinding(binding: CompanionBinding) { selected = binding }
    }
    @Test fun officialHttpsReturnsFinalAnswerWithoutPcBackend() = runBlocking {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
            "This synthetic probe is restricted to an emulator"
        }
        check(keyFile.isFile && modelFile.isFile) { "Transient probe credentials were not supplied" }
        val databaseName = "native_official_${UUID.randomUUID()}.sqlite3"
        val database = LocalChatDatabase(context, databaseName)
        val store = ProbeStore()
        try {
            val key = keyFile.readText().trim()
            val model = modelFile.readText().trim()
            val repository = LocalChatRepository(context, store, database = database)
            val started = System.nanoTime()
            withTimeout(900_000) { repository.login(LocalChatRepository.LOCAL_URL, "deepseek", key, model, CompanionProfile(style = "warm_v2")) }
            val validationMs = (System.nanoTime() - started) / 1_000_000.0
            val chat = repository.conversations().single()
            withTimeout(900_000) { repository.sendMessage(chat.id, "这是合成验证资料：我的生日是九月十八日，请用一句话记住。") }
            val answerStarted = System.nanoTime()
            val answer = withTimeout(900_000) { repository.sendMessage(chat.id,
                "你记得我生日吗？陪我聊聊那天可以怎么过，写约500字，可以慢慢答，不要编造我们过去共同经历。") }.second.content
            assertTrue("No final text", answer.isNotBlank())
            assertTrue("Explicit birthday omitted", answer.contains("九月十八") || answer.contains("9月18") || answer.contains("九月18"))
            assertFalse(listOf("数据库", "手头的记录", "手边的记录", "检索不到").any(answer::contains))
            val report = JSONObject().put("provider", "deepseek").put("model", model)
                .put("calls", 3).put("validation_ms", validationMs)
                .put("complete_answer_ms", (System.nanoTime() - answerStarted) / 1_000_000.0)
                .put("final_answer_characters", answer.length).put("birthday_fact", "passed")
                .put("computer_backend_used", false).put("final_answer", "passed")
                .put("real_repository_login", "passed").put("independent_saved_messages", repository.history(chat.id).messages.size)
            assertEquals(4, repository.history(chat.id).messages.size)
            assertEquals(LocalChatRepository.LOCAL_URL, repository.savedConnection()?.serverUrl)
            assertEquals("deepseek", repository.savedBinding()?.providerId)
            nativeProbeReport("NATIVE_OFFICIAL_AI_REPORT $report")
            NativeMemoryRecognitionCloudProbe.run(context, key, model)
        } finally {
            store.clear()
            database.close(); context.deleteDatabase(databaseName)
            check(!keyFile.exists() || keyFile.delete()) { "Transient key cleanup failed" }
            check(!modelFile.exists() || modelFile.delete()) { "Transient model cleanup failed" }
        }
    }
}
