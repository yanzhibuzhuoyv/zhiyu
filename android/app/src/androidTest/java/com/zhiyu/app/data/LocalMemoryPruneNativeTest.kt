package com.zhiyu.app.data

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.CompanionProfile
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.LocalMemoryPruneRules
import com.zhiyu.app.domain.LocalSemanticVectorRules
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Real private SQLite: retention is capped, old-only, and removes derived rows in one transaction. */
class LocalMemoryPruneNativeTest {
    @Test fun capIsTwoAndAHalfPercentAndDerivedRowsDisappearWithTheOriginal() {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val name = "memory_prune_${UUID.randomUUID()}.sqlite3"
            val database = LocalChatDatabase(context, name)
            try {
                val owner = "synthetic-prune-owner"
                val chat = database.ensureConversation(owner, "synthetic-model", CompanionProfile())
                val now = Instant.parse("2026-10-19T00:00:00Z")
                val oldTime = now.minusSeconds(60L * 86400).toString()
                val oldIds = (0 until 150).map { index ->
                    val row = database.append(owner, chat.id, if (index % 2 == 0) "user" else "assistant", "旧记忆$index")
                    database.writableDatabase.update("messages", ContentValues().apply { put("created_at", oldTime) },
                        "id=?", arrayOf(row.id))
                    row
                }
                (0 until 100).forEach { index ->
                    database.append(owner, chat.id, "user", "近期记忆$index")
                }
                val cutoff = now.minusSeconds(LocalMemoryPruneRules.MIN_AGE_DAYS * 86400)
                assertEquals(150, database.oldMemoryCandidates(owner, chat.id, cutoff).size)
                val deleted = oldIds.take(3)
                deleted.forEach { row ->
                    val vector = LocalSemanticVectorRules.encode(floatArrayOf(1f, 0f), 2)
                    val rawSha = LocalMemoryCodec.sha(LocalMemoryCodec.bytes(row.content))
                    database.writableDatabase.insertOrThrow("semantic_vectors", null, ContentValues().apply {
                        put("owner", owner); put("conversation_id", chat.id); put("message_id", row.id)
                        put("model_id", "synthetic-model"); put("raw_sha", rawSha); put("revision", 0)
                        put("chunk_index", 0); put("start_offset", 0); put("end_offset", row.content.length)
                        put("original_chars", row.content.length); put("dimensions", 2)
                        put("format", LocalSemanticVectorRules.FORMAT)
                        put("vector_sha", MessageDigest.getInstance("SHA-256").digest(vector)); put("vector", vector)
                    })
                    database.writableDatabase.execSQL("INSERT INTO memory_fts(body_tokens,owner,message_id,document_id,part_index,start_offset,end_offset,original_chars) VALUES(?,?,?,?,?,?,?,?)",
                        arrayOf<Any>("旧记忆", owner, row.id, "", 0, 0, row.content.length, row.content.length))
                }
                assertEquals(3, database.deleteOldMemories(owner, chat.id, deleted.map { it.id }, cutoff))
                deleted.forEach { row ->
                    assertEquals(0, count(database, "select count(*) from messages where id=?", arrayOf(row.id)))
                    assertEquals(0, count(database, "select count(*) from semantic_vectors where message_id=?", arrayOf(row.id)))
                    assertEquals(0, count(database, "select count(*) from memory_fts where message_id=?", arrayOf(row.id)))
                }
                assertEquals(147, database.oldMemoryCandidates(owner, chat.id, cutoff).size)
                assertEquals(147 + 100, count(database, "select count(*) from messages where owner=?", arrayOf(owner)))
            } finally {
                database.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test fun semanticServiceDeletesOnlyConfidentDuplicatesWithinTheCap() = kotlinx.coroutines.runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val name = "memory_prune_service_${UUID.randomUUID()}.sqlite3"
            val database = LocalChatDatabase(context, name)
            try {
                val owner = "synthetic-prune-service-owner"
                val chat = database.ensureConversation(owner, "synthetic-model", CompanionProfile())
                val now = Instant.parse("2026-10-19T00:00:00Z")
                val oldTime = now.minusSeconds(60L * 86400).toString()
                (0 until 150).forEach { index ->
                    appendWithVector(database, owner, chat.id, "user", "重复旧记忆$index", oldTime)
                }
                (0 until 100).forEach { index ->
                    appendWithVector(database, owner, chat.id, "user", "保留的新记忆$index", now.toString())
                }
                val encoder = object : LocalSemanticEncoder {
                    override val modelIdentity = "synthetic-model"
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
                val report = LocalMemoryPruneService(database, encoder) { now }.run(owner, chat.id)
                assertEquals(150, report.oldCandidates)
                assertEquals(3, report.requested)
                assertEquals(3, report.deleted)
                assertEquals(247, count(database, "select count(*) from messages where owner=?", arrayOf(owner)))
            } finally {
                database.close()
                context.deleteDatabase(name)
            }
        }
    }

    private fun appendWithVector(database: LocalChatDatabase, owner: String, chat: String,
        role: String, content: String, at: String) {
        val row = database.append(owner, chat, role, content)
        database.writableDatabase.update("messages", ContentValues().apply { put("created_at", at) },
            "id=?", arrayOf(row.id))
        val vector = LocalSemanticVectorRules.encode(floatArrayOf(1f, 0f), 2)
        database.writableDatabase.insertOrThrow("semantic_vectors", null, ContentValues().apply {
            put("owner", owner); put("conversation_id", chat); put("message_id", row.id)
            put("model_id", "synthetic-model")
            put("raw_sha", LocalMemoryCodec.sha(LocalMemoryCodec.bytes(content))); put("revision", 0)
            put("chunk_index", 0); put("start_offset", 0); put("end_offset", content.length)
            put("original_chars", content.length); put("dimensions", 2)
            put("format", LocalSemanticVectorRules.FORMAT)
            put("vector_sha", MessageDigest.getInstance("SHA-256").digest(vector)); put("vector", vector)
        })
    }

    private fun count(database: LocalChatDatabase, sql: String, args: Array<String>): Int =
        database.readableDatabase.rawQuery(sql, args).use { it.moveToFirst(); it.getInt(0) }
}
