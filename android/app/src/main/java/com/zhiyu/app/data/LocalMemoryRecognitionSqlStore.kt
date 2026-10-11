package com.zhiyu.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/** Short owner-scoped transactions only; no provider/Key/network operation occurs here. */
class LocalMemoryRecognitionSqlStore(
    private val database: LocalChatDatabase, private val now: () -> Long = System::currentTimeMillis,
) : LocalMemoryRecognitionStore {
    override fun enqueue(identity: LocalMemoryRecognitionIdentity, limit: Int) {
        require(limit in 1..64)
        transaction { db ->
            db.rawQuery("""SELECT m.id,m.sha256,m.revision,m.role,m.created_at FROM messages m
                JOIN conversations c ON c.id=m.conversation_id AND c.owner=m.owner
                WHERE m.owner=? AND m.conversation_id=? AND c.model=? AND NOT EXISTS (
                    SELECT 1 FROM native_memory_jobs j WHERE j.owner=m.owner AND j.message_id=m.id AND j.version=?)
                ORDER BY m.seq LIMIT $limit""", arrayOf(identity.owner, identity.conversationId, identity.model, LocalMemoryRecognitionRules.VERSION)).use { rows ->
                while (rows.moveToNext()) enqueueRow(db, identity, rows)
            }
        }
    }
    private fun enqueueRow(db: SQLiteDatabase, identity: LocalMemoryRecognitionIdentity, row: Cursor) {
        val id = LocalMemoryRecognitionRules.sha("${identity.owner}\u0000${row.memoryText("id")}\u0000${LocalMemoryRecognitionRules.VERSION}").take(32)
        db.insertWithOnConflict("native_memory_jobs", null, recognitionValues("id" to id, "owner" to identity.owner,
            "conversation_id" to identity.conversationId, "message_id" to row.memoryText("id"),
            "provider_id" to identity.providerId, "model" to identity.model, "source_sha" to row.memoryText("sha256"),
            "source_revision" to row.memoryInt("revision"), "source_role" to row.memoryText("role"),
            "source_created_at" to row.memoryText("created_at"), "version" to LocalMemoryRecognitionRules.VERSION,
            "status" to "pending", "updated_ms" to now()), SQLiteDatabase.CONFLICT_IGNORE)
    }
    override fun recover(identity: LocalMemoryRecognitionIdentity) {
        transaction { db ->
            db.execSQL("""UPDATE native_memory_jobs SET status=CASE WHEN attempts>=3 THEN 'failed' ELSE 'waiting_auth' END,
                lease=NULL,updated_ms=? WHERE owner=? AND conversation_id=? AND provider_id=? AND model=?
                AND status='running' AND updated_ms<?""", arrayOf<Any>(now(), identity.owner, identity.conversationId,
                identity.providerId, identity.model, now() - 180_000))
            db.execSQL("""UPDATE native_memory_jobs SET status='failed',lease=NULL WHERE owner=? AND conversation_id=?
                AND (source_role='' OR source_created_at='')""", arrayOf(identity.owner, identity.conversationId))
            db.execSQL("""UPDATE native_memory_jobs SET status='failed',lease=NULL WHERE owner=? AND conversation_id=?
                AND status IN ('pending','partial','waiting_auth') AND (version<>? OR NOT EXISTS (
                    SELECT 1 FROM messages m WHERE m.id=message_id AND m.owner=native_memory_jobs.owner
                    AND m.conversation_id=native_memory_jobs.conversation_id AND m.sha256=source_sha
                    AND m.role=source_role AND m.created_at=source_created_at))""",
                arrayOf(identity.owner, identity.conversationId, LocalMemoryRecognitionRules.VERSION))
        }
    }
    override fun claim(identity: LocalMemoryRecognitionIdentity): LocalMemoryRecognitionJob? = transaction { db ->
        if (db.rawQuery("SELECT 1 FROM native_memory_jobs WHERE owner=? AND conversation_id=? AND status='running' LIMIT 1",
                arrayOf(identity.owner, identity.conversationId)).use { it.moveToFirst() }) return@transaction null
        val lease = UUID.randomUUID().toString()
        val row = db.rawQuery("""SELECT j.*,m.revision current_revision FROM native_memory_jobs j JOIN messages m
            ON m.id=j.message_id AND m.owner=j.owner AND m.conversation_id=j.conversation_id
            WHERE j.owner=? AND j.conversation_id=? AND j.provider_id=? AND j.model=? AND j.version=?
              AND j.status IN ('pending','partial','waiting_auth') AND j.attempts<3
              AND j.source_sha=m.sha256 AND j.source_role=m.role AND j.source_created_at=m.created_at
            ORDER BY m.seq LIMIT 1""", arrayOf(identity.owner, identity.conversationId, identity.providerId, identity.model, LocalMemoryRecognitionRules.VERSION)).use {
            if (it.moveToFirst()) LocalMemoryRecognitionJob(it.memoryText("id"), identity.owner, identity.conversationId,
                it.memoryText("message_id"), identity.providerId, identity.model, it.memoryText("source_sha"),
                it.memoryInt("current_revision"), it.memoryInt("source_offset"), lease, it.memoryInt("attempts") + 1,
                it.memoryText("source_role"), it.memoryText("source_created_at")) else null
        } ?: return@transaction null
        db.update("native_memory_jobs", recognitionValues("status" to "running", "lease" to lease,
            "attempts" to row.attempts, "source_revision" to row.sourceRevision, "updated_ms" to now()), "id=?", arrayOf(row.id))
        row
    }
    override fun original(job: LocalMemoryRecognitionJob): LocalMemoryOriginal? =
        database.recognitionOriginal(job.owner, job.conversationId, job.messageId)?.takeIf {
            it.sha256 == job.sourceSha && it.revision == job.sourceRevision && it.role == job.role && it.createdAt == job.createdAt
        }
    override fun skipNoText(job: LocalMemoryRecognitionJob, source: LocalMemoryOriginal, authorized: () -> Boolean): Boolean {
        if (source.text.isNotBlank() || original(job) != source) return false
        return transaction { db ->
            if (!authorized() || !current(db, job) || !matches(db, source)) return@transaction false
            val updated = db.update("native_memory_jobs", recognitionValues("status" to "skipped", "source_offset" to 0,
                "attempts" to 0, "lease" to null, "updated_ms" to now()), "id=? AND lease=?", arrayOf(job.id, job.lease))
            if (!authorized()) throw LocalMemoryRecognitionPaused()
            updated == 1
        }
    }
    override fun catalogue(job: LocalMemoryRecognitionJob): List<LocalMemoryTopic> = database.readableDatabase.rawQuery("""SELECT DISTINCT a.kind,a.subject,a.topic_key
        FROM native_memory_annotations a JOIN messages m ON m.id=a.message_id AND m.owner=a.owner
        AND m.conversation_id=a.conversation_id AND m.sha256=a.source_sha AND m.role=a.role AND m.created_at=a.source_created_at
        WHERE a.owner=? AND a.conversation_id=? AND a.validity='current'
        AND m.seq<(SELECT seq FROM messages WHERE owner=? AND conversation_id=? AND id=?) ORDER BY m.seq DESC LIMIT 64""",
        arrayOf(job.owner, job.conversationId, job.owner, job.conversationId, job.messageId)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(LocalMemoryTopic(cursor.memoryText("kind"), cursor.memoryText("subject"), cursor.memoryText("topic_key"))) }
    }
    override fun finish(job: LocalMemoryRecognitionJob, window: LocalMemoryWindow, annotations: List<LocalMemoryAnnotation>, authorized: () -> Boolean): Boolean {
        val source = original(job) ?: return false
        require(window == LocalMemoryRecognitionRules.window(source, job.offset))
        require(window.end - window.start <= LocalMemoryRecognitionRules.MAX_WINDOW && annotations.size <= LocalMemoryRecognitionRules.MAX_CLAIMS)
        require(annotations.map { it.id }.distinct().size == annotations.size)
        annotations.forEach {
            require(it.start >= window.start && it.end <= window.end)
            LocalMemoryRecognitionRules.verify(source, it)
        }
        return transaction { db ->
            if (!authorized() || !current(db, job) || !matches(db, source)) return@transaction false
            LocalMemoryRecognitionAnnotations(database).write(db, source, annotations)
            val revision = db.rawQuery("SELECT revision FROM messages WHERE owner=? AND conversation_id=? AND id=?",
                arrayOf(job.owner, job.conversationId, job.messageId)).use { it.moveToFirst(); it.getInt(0) }
            db.update("native_memory_jobs", recognitionValues("status" to if (window.end == source.text.length) "ok" else "partial",
                "source_offset" to window.end, "source_revision" to revision, "attempts" to 0, "lease" to null, "updated_ms" to now()),
                "id=? AND lease=?", arrayOf(job.id, job.lease))
            if (!authorized()) throw LocalMemoryRecognitionPaused()
            true
        }
    }
    override fun release(job: LocalMemoryRecognitionJob, retry: Boolean) {
        transaction { db ->
            if (!current(db, job)) return@transaction
            val status = if (retry) "waiting_auth" else if (job.attempts >= 3) "failed" else "pending"
            db.update("native_memory_jobs", recognitionValues("status" to status, "lease" to null,
                "attempts" to if (retry) maxOf(0, job.attempts - 1) else job.attempts, "updated_ms" to now()),
                "id=? AND lease=?", arrayOf(job.id, job.lease))
        }
    }
    override fun pending(identity: LocalMemoryRecognitionIdentity): Boolean = database.readableDatabase.rawQuery("""SELECT 1 FROM native_memory_jobs
        WHERE owner=? AND conversation_id=? AND provider_id=? AND model=? AND version=? AND status IN ('pending','partial','waiting_auth','running') LIMIT 1""",
        arrayOf(identity.owner, identity.conversationId, identity.providerId, identity.model, LocalMemoryRecognitionRules.VERSION)).use { it.moveToFirst() }

    private fun current(db: SQLiteDatabase, job: LocalMemoryRecognitionJob): Boolean = db.rawQuery("""SELECT 1 FROM native_memory_jobs
        WHERE id=? AND owner=? AND conversation_id=? AND message_id=? AND provider_id=? AND model=?
        AND source_sha=? AND source_revision=? AND status='running' AND lease=? AND version=?""",
        arrayOf(job.id, job.owner, job.conversationId, job.messageId, job.providerId, job.model, job.sourceSha,
            job.sourceRevision.toString(), job.lease, LocalMemoryRecognitionRules.VERSION)).use { it.moveToFirst() }
    private fun matches(db: SQLiteDatabase, source: LocalMemoryOriginal): Boolean = db.rawQuery("""SELECT 1 FROM messages
        WHERE owner=? AND conversation_id=? AND id=? AND role=? AND created_at=? AND sha256=? AND revision=?""",
        arrayOf(source.owner, source.conversationId, source.messageId, source.role, source.createdAt, source.sha256, source.revision.toString())).use { it.moveToFirst() }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = database.writableDatabase
        db.beginTransaction()
        try { return block(db).also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }
}

internal fun Cursor.memoryText(name: String): String = getString(getColumnIndexOrThrow(name))
internal fun Cursor.memoryInt(name: String): Int = getInt(getColumnIndexOrThrow(name))
internal fun recognitionValues(vararg entries: Pair<String, Any?>) = ContentValues().apply {
    entries.forEach { (key, value) -> when (value) {
        null -> putNull(key)
        is String -> put(key, value)
        is Int -> put(key, value)
        is Long -> put(key, value)
        is Double -> put(key, value)
        is Boolean -> put(key, if (value) 1 else 0)
        else -> throw IllegalArgumentException("记忆标注存储字段无效")
    } }
}
