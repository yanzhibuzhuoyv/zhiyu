package com.zhiyu.app.data

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Review-only synthetic fixtures. Assertions express the expected safety behavior. */
class RepoReviewPruneNativeTest {
    private val now = Instant.parse("2026-10-21T00:00:00Z")
    private val oldTime = now.minusSeconds(60L * 86400).toString()
    private val encoder = object : LocalSemanticEncoder {
        override val modelIdentity = "review-model"
        override val dimensions = 2
        override fun isAvailable() = true
        override fun documentChunkCount(text: String) = 1
        override suspend fun encodeDocument(text: String) = floatArrayOf(1f, 0f)
        override suspend fun encodeQuery(query: String) = floatArrayOf(1f, 0f)
        override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int) =
            listOf(SemanticChunk(0, 0, 1, 0, text.length, floatArrayOf(1f, 0f)))
        override suspend fun encodeQueryChunks(query: String) =
            listOf(SemanticChunk(0, 0, 1, 0, query.length, floatArrayOf(1f, 0f)))
    }

    @Test fun sharedFirstChunkMustNotDeleteUniqueTail() = runBlocking {
        withDatabase { db, owner, chat ->
            val prefix = "今天路过公园，天气很好。".repeat(80)
            val unique = "只有这条旧消息提过的约定：周五去松林路接姐姐。"
            val original = append(db, owner, chat, prefix + unique, oldTime)
            vector(db, owner, chat, original, 0, 0, prefix.length, floatArrayOf(1f, 0f))
            vector(db, owner, chat, original, 1, prefix.length, original.content.length, floatArrayOf(0f, 1f))
            repeat(39) { append(db, owner, chat, "没有重复内容的旧话$it", oldTime) }
            val newer = append(db, owner, chat, prefix, now.minusSeconds(86400).toString())
            vector(db, owner, chat, newer, 0, 0, prefix.length, floatArrayOf(1f, 0f))
            repeat(100) { append(db, owner, chat, "近期消息$it", now.toString()) }
            val report = LocalMemoryPruneService(db, encoder) { now }.run(owner, chat)
            assertEquals("A matching first chunk cannot justify deleting the unmatched tail: $report",
                1, count(db, "SELECT count(*) FROM messages WHERE id=?", arrayOf(original.id)))
        }
    }

    @Test fun scanningMustEventuallyReachEligibleRowsPastFirst400() = runBlocking {
        withDatabase { db, owner, chat ->
            repeat(400) {
                val row = append(db, owner, chat, "没有更新代表的早期消息$it", oldTime)
                vector(db, owner, chat, row, 0, 0, row.content.length, floatArrayOf(1f, 0f))
            }
            repeat(40) {
                val row = append(db, owner, chat, "可以找到完整副本的内容", oldTime)
                vector(db, owner, chat, row, 0, 0, row.content.length, floatArrayOf(0f, 1f))
            }
            repeat(100) {
                val row = append(db, owner, chat, "可以找到完整副本的内容", now.toString())
                vector(db, owner, chat, row, 0, 0, row.content.length, floatArrayOf(0f, 1f))
            }
            val service = LocalMemoryPruneService(db, encoder) { now }
            val first = service.run(owner, chat)
            val second = service.run(owner, chat)
            assertTrue("Both scans revisit the same first 400: $first / $second", first.deleted + second.deleted > 0)
        }
    }

    private suspend fun withDatabase(block: suspend (LocalChatDatabase, String, String) -> Unit) {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val name = "review_${UUID.randomUUID()}.sqlite3"
            val db = LocalChatDatabase(context, name)
            try {
                val owner = "synthetic-review-owner"
                val chat = db.ensureConversation(owner, "review-model", CompanionProfile())
                block(db, owner, chat.id)
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    private fun append(db: LocalChatDatabase, owner: String, chat: String, content: String, at: String): Message {
        val row = db.append(owner, chat, "user", content)
        db.writableDatabase.update("messages", ContentValues().apply { put("created_at", at) }, "id=?", arrayOf(row.id))
        return row.copy(createdAt = at)
    }

    private fun vector(db: LocalChatDatabase, owner: String, chat: String, row: Message,
        chunk: Int, start: Int, end: Int, embedding: FloatArray) {
        val bytes = LocalSemanticVectorRules.encode(embedding, 2)
        db.writableDatabase.insertOrThrow("semantic_vectors", null, ContentValues().apply {
            put("owner", owner); put("conversation_id", chat); put("message_id", row.id)
            put("model_id", encoder.modelIdentity); put("raw_sha", LocalMemoryCodec.sha(LocalMemoryCodec.bytes(row.content)))
            put("revision", 0); put("chunk_index", chunk); put("start_offset", start); put("end_offset", end)
            put("original_chars", row.content.length); put("dimensions", 2); put("format", LocalSemanticVectorRules.FORMAT)
            put("vector_sha", MessageDigest.getInstance("SHA-256").digest(bytes)); put("vector", bytes)
        })
    }

    private fun count(db: LocalChatDatabase, sql: String, args: Array<String>): Int =
        db.readableDatabase.rawQuery(sql, args).use { it.moveToFirst(); it.getInt(0) }
}
