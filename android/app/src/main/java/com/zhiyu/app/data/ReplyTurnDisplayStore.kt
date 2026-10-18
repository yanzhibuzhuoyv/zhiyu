package com.zhiyu.app.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.zhiyu.app.domain.MediaRules
import com.zhiyu.app.domain.Message
import com.zhiyu.app.domain.ReplyTurnOrder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded ID-only display receipts, separate from the original archive and retrieval indexes. */
internal class ReplyTurnDisplayStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "reply_turn_order_v1")
    fun order(chat: String, messages: List<Message>): List<Message> = synchronized(lock) {
        try { ReplyTurnOrder.apply(messages, read(chat)) }
        catch (_: Exception) {
            Log.w("ZhiYuTimeline", "Display receipt unavailable; original archive order retained")
            messages
        }
    }
    fun save(chat: String, ids: List<String>) = synchronized(lock) {
        ReplyTurnOrder.apply(emptyList(), listOf(ids)) // Validate even when the current page is empty.
        val members = ids.toSet()
        val before = read(chat).filter { turn -> turn.none(members::contains) }
        val turns = (before + listOf(ids.toList())).takeLast(32)
        val value = JSONObject().put("schema", 1).put("turns", JSONArray(turns.map(::JSONArray)))
            .toString().toByteArray(Charsets.UTF_8)
        require(value.size <= 524_288)
        LocalMediaStore.write(path(chat), value)
    }
    private fun path(chat: String): File { MediaRules.path(chat); return File(root, "$chat.json") }
    private fun read(chat: String): List<List<String>> {
        val file = path(chat)
        if (!file.isFile && !File(file.path + ".bak").isFile) return emptyList()
        val bytes = AtomicFile(file).openRead().use { input -> input.readBytesLimit() }
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        require(value.getInt("schema") == 1)
        val groups = value.getJSONArray("turns"); require(groups.length() <= 32)
        return List(groups.length()) { index ->
            val ids = groups.getJSONArray(index); require(ids.length() in 2..96)
            List(ids.length()) { ids.getString(it) }.also { ReplyTurnOrder.apply(emptyList(), listOf(it)) }
        }
    }
    private fun java.io.InputStream.readBytesLimit(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer); if (count < 0) break
            require(count > 0 && output.size() + count <= 524_288)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private companion object { val lock = Any() }
}
