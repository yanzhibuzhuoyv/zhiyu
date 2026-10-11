package com.zhiyu.app.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.zhiyu.app.domain.MemoryChange
import java.time.Instant

/** Caller owns one write transaction; metadata updates never replace a source payload. */
internal class LocalMemoryRecognitionAnnotations(private val database: LocalChatDatabase) {
    fun write(db: SQLiteDatabase, source: LocalMemoryOriginal, annotations: List<LocalMemoryAnnotation>) {
        check(db.inTransaction())
        annotations.forEach { annotation ->
            insert(db, annotation)
            supersede(db, annotation)
        }
        val affected = annotations.flatMap { replay(db, it) }.toSet() + source.messageId
        affected.forEach { message -> updateSource(db, source.owner, source.conversationId, message) }
    }
    private fun insert(db: SQLiteDatabase, a: LocalMemoryAnnotation) {
        db.insertWithOnConflict("native_memory_annotations", null, recognitionValues("id" to a.id, "owner" to a.owner,
            "conversation_id" to a.conversationId, "message_id" to a.messageId, "role" to a.role, "source_created_at" to a.createdAt,
            "source_sha" to a.sourceSha, "source_revision" to a.sourceRevision, "span_start" to a.start, "span_end" to a.end,
            "span_sha" to a.spanSha, "kind" to a.kind, "subject" to a.subject, "polarity" to a.polarity, "topic_key" to a.topicKey,
            "confidence" to a.confidence, "explicit_keep" to a.explicitKeep, "correction" to a.correction,
            "promise_state" to a.promiseState, "date_basis" to a.dateBasis, "event_date" to a.eventDate,
            "version" to LocalMemoryRecognitionRules.VERSION), SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun supersede(db: SQLiteDatabase, a: LocalMemoryAnnotation) {
        val resolved = a.kind == "promise" && a.promiseState in setOf("fulfilled", "cancelled")
        if (a.kind == "promise" && !resolved || !resolved && !a.correction) return
        val older = db.rawQuery("""SELECT a.id,a.promise_state,a.event_date FROM native_memory_annotations a
            JOIN messages m ON m.id=a.message_id AND m.owner=a.owner AND m.conversation_id=a.conversation_id
            AND m.sha256=a.source_sha AND m.role=a.role AND m.created_at=a.source_created_at
            WHERE a.owner=? AND a.conversation_id=? AND a.kind=? AND a.subject=? AND a.topic_key=? AND a.role=?
            AND a.validity='current' AND m.seq<(SELECT seq FROM messages WHERE owner=? AND conversation_id=? AND id=?)
            ORDER BY m.seq LIMIT 1001""", arrayOf(a.owner, a.conversationId, a.kind, a.subject, a.topicKey, a.role,
            a.owner, a.conversationId, a.messageId)).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val state = cursor.getString(1)
                val date = cursor.getString(2)
                if (!resolved || state in setOf("pending", "overdue") && (a.eventDate == null || a.eventDate == date)) add(cursor.getString(0))
            } }
        }
        require(older.size <= 1000) { "记忆主题超过安全重放上限" }
        if (resolved && older.size != 1) return
        older.forEach { id ->
            db.update("native_memory_annotations", recognitionValues("validity" to "superseded"), "id=? AND owner=?", arrayOf(id, a.owner))
            db.insertWithOnConflict("native_memory_annotation_links", null, recognitionValues("newer_id" to a.id, "older_id" to id, "relation" to "supersedes"), SQLiteDatabase.CONFLICT_IGNORE)
        }
    }
    private fun replay(db: SQLiteDatabase, a: LocalMemoryAnnotation): List<String> {
        val values = db.rawQuery("""SELECT a.* FROM native_memory_annotations a JOIN messages m
            ON m.id=a.message_id AND m.owner=a.owner AND m.conversation_id=a.conversation_id
            AND m.sha256=a.source_sha AND m.role=a.role AND m.created_at=a.source_created_at
            WHERE a.owner=? AND a.conversation_id=? AND a.kind=? AND a.subject=? AND a.topic_key=? ORDER BY m.seq LIMIT 1001""",
            arrayOf(a.owner, a.conversationId, a.kind, a.subject, a.topicKey)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(annotation(cursor)) }
        }
        require(values.size <= 1000) { "记忆主题超过安全重放上限" }
        LocalMemoryLifecycle.evaluate(values, Instant.now()).values.forEach { decision ->
            db.update("native_memory_annotations", recognitionValues("tier" to decision.tier, "pinned" to decision.pinned,
                "reason" to decision.reason, "evidence_count" to decision.independentEvidence), "id=? AND owner=?", arrayOf(decision.id, a.owner))
        }
        return values.map { it.messageId }
    }
    private fun updateSource(db: SQLiteDatabase, owner: String, chat: String, message: String) {
        val result = db.rawQuery("""SELECT MAX(CASE a.tier WHEN 'permanent' THEN 2 WHEN 'long' THEN 1 ELSE 0 END),MAX(a.pinned)
            FROM native_memory_annotations a JOIN messages m ON m.id=a.message_id AND m.owner=a.owner
            AND m.conversation_id=a.conversation_id AND m.sha256=a.source_sha AND m.role=a.role AND m.created_at=a.source_created_at
            WHERE a.owner=? AND a.conversation_id=? AND a.message_id=? AND a.validity='current'""", arrayOf(owner, chat, message)).use {
            it.moveToFirst(); (if (it.isNull(0)) 0 else it.getInt(0)) to (if (it.isNull(1)) 0 else it.getInt(1))
        }
        val desired = when (result.first) { 2 -> "permanent"; 1 -> "long"; else -> "mid" }
        db.execSQL("UPDATE messages SET tier=?,revision=revision+1 WHERE owner=? AND conversation_id=? AND id=? AND manual_tier IS NULL AND tier!=?",
            arrayOf(desired, owner, chat, message, desired))
        val metadata = db.rawQuery("SELECT tier,index_state,revision FROM messages WHERE owner=? AND conversation_id=? AND id=?", arrayOf(owner, chat, message)).use {
            if (!it.moveToFirst()) return
            Triple(it.getString(0), it.getString(1), it.getInt(2))
        }
        if (metadata.second == "cold" && (metadata.first == "permanent" || result.second == 1))
            database.changeMemory(owner, chat, message, MemoryChange(metadata.third, indexState = "active"))
    }
    private fun annotation(c: Cursor) = LocalMemoryAnnotation(c.memoryText("id"), c.memoryText("owner"), c.memoryText("conversation_id"),
        c.memoryText("message_id"), c.memoryText("role"), c.memoryText("source_created_at"), c.memoryText("source_sha"), c.memoryInt("source_revision"),
        c.memoryInt("span_start"), c.memoryInt("span_end"), c.memoryText("span_sha"), c.memoryText("kind"), c.memoryText("subject"),
        c.memoryText("polarity"), c.memoryText("topic_key"), c.getDouble(c.getColumnIndexOrThrow("confidence")), c.memoryInt("explicit_keep") == 1,
        c.memoryInt("correction") == 1, c.getString(c.getColumnIndexOrThrow("promise_state")), c.getString(c.getColumnIndexOrThrow("date_basis")),
        c.getString(c.getColumnIndexOrThrow("event_date")), c.memoryText("validity"), c.memoryText("tier"), c.memoryInt("pinned") == 1)
}
