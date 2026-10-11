package com.zhiyu.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import java.util.UUID

/** Two synthetic originals exercise the actual official classifier and durable lifecycle. */
internal object NativeMemoryRecognitionCloudProbe {
    suspend fun run(context: Context, key: String, model: String) {
        val name = "native_cloud_labels_${UUID.randomUUID()}.sqlite3"
        var database = LocalChatDatabase(context, name)
        val owner = "synthetic-cloud-labels"
        try {
            val chat = database.ensureConversation(owner, model, null)
            val texts = listOf("我的生日是六月十日。", "之前生日那个日期说错了，我的生日实际是九月十八日。")
            val messages = texts.map { database.append(owner, chat.id, "user", it) }
            val hashes = messages.map { database.recognitionOriginal(owner, chat.id, it.id)!!.sha256 }
            val identity = LocalMemoryRecognitionIdentity(owner, chat.id, "deepseek", model)
            val delegate = LocalMemoryOfficialRecognizer("deepseek", key, model)
            var calls = 0
            val recognizer = LocalMemoryRecognizer { window, catalogue ->
                calls++
                delegate.recognize(window, catalogue)
            }
            val started = System.nanoTime()
            val completed = LocalMemoryRecognitionEngine(LocalMemoryRecognitionSqlStore(database), recognizer,
                MutableStateFlow(false), { true }).run(identity, 2)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            database.close(); database = LocalChatDatabase(context, name)
            val originalsIntact = messages.indices.all { index ->
                val source = database.recognitionOriginal(owner, chat.id, messages[index].id)!!
                source.text == texts[index] && source.sha256 == hashes[index] &&
                    source.role == "user" && source.createdAt == messages[index].createdAt
            }
            val oldState = validity(database, messages[0].id)
            val currentState = validity(database, messages[1].id)
            val permanent = database.memoryEntry(owner, chat.id, messages[1].id).tier == "permanent"
            val links = database.readableDatabase.rawQuery("SELECT COUNT(*) FROM native_memory_annotation_links", null)
                .use { it.moveToFirst(); it.getInt(0) }
            nativeProbeReport("NATIVE_MEMORY_RECOGNITION_REPORT " + JSONObject().put("provider", "deepseek")
                .put("calls", calls).put("completed_windows", completed).put("elapsed_ms", elapsedMs)
                .put("old_state", oldState).put("current_state", currentState).put("permanent", permanent)
                .put("correction_links", links).put("restart_originals_intact", originalsIntact)
                .put("computer_backend_used", false).put("samples", 2))
            assertEquals(2, calls); assertEquals(2, completed)
            assertTrue(originalsIntact); assertTrue(permanent)
            assertEquals("superseded", oldState); assertEquals("current", currentState); assertEquals(1, links)
        } finally {
            database.close(); context.deleteDatabase(name)
        }
    }
    private fun validity(database: LocalChatDatabase, id: String): String = database.readableDatabase.rawQuery(
        "SELECT validity FROM native_memory_annotations WHERE message_id=? AND kind='profile' AND topic_key='user:birthday'",
        arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else "missing" }
}
