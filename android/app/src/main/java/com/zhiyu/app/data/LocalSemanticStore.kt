package com.zhiyu.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.zhiyu.app.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class LocalSemanticJob(val owner: String, val conversationId: String, val messageId: String, val modelIdentity: String,
    val rawSha: String, val revision: Int, val nextChunk: Int, val attempts: Int,val totalChunks:Int? = null)
data class LocalSemanticHit(val messageId: String, val role: String, val createdAt: String, val tier: String,
    val rawSha: String, val start: Int, val end: Int, val originalChars: Int, val score: Double, val sequence: Long,
    val relevanceFactor:Double = 1.0) {
    val rankingScore:Double get() = score * relevanceFactor
}
data class LocalSemanticStatistics(val totalMessages:Long,val readyMessages:Long,val failedMessages:Long,
    val pendingMessages:Long,val vectorChunks:Long,val knownTotalChunks:Long?,val unplannedMessages:Long,
    val plannedChunks:Long,val skippedMessages:Long = 0)

/** Tasks and vectors share the original database transaction boundary; plaintext is never duplicated. */
class LocalSemanticStore(private val archive: LocalChatDatabase) {
    companion object {
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS semantic_jobs (owner TEXT NOT NULL,conversation_id TEXT NOT NULL,message_id TEXT NOT NULL REFERENCES messages(id),model_id TEXT NOT NULL,raw_sha TEXT NOT NULL,revision INTEGER NOT NULL,next_chunk INTEGER NOT NULL DEFAULT 0,total_chunks INTEGER,state TEXT NOT NULL DEFAULT 'pending',attempts INTEGER NOT NULL DEFAULT 0,retry_at INTEGER NOT NULL DEFAULT 0,processed_at INTEGER NOT NULL DEFAULT 0,error_code TEXT,PRIMARY KEY(owner,message_id,model_id))")
            val columns = db.rawQuery("PRAGMA table_info(semantic_jobs)",null).use { cursor -> buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            } }
            if ("total_chunks" !in columns) db.execSQL("ALTER TABLE semantic_jobs ADD COLUMN total_chunks INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS semantic_jobs_scope ON semantic_jobs(owner,conversation_id,model_id,state,retry_at,processed_at)")
            db.execSQL("CREATE TABLE IF NOT EXISTS semantic_vectors (owner TEXT NOT NULL,conversation_id TEXT NOT NULL,message_id TEXT NOT NULL REFERENCES messages(id),model_id TEXT NOT NULL,raw_sha TEXT NOT NULL,revision INTEGER NOT NULL,chunk_index INTEGER NOT NULL,start_offset INTEGER NOT NULL,end_offset INTEGER NOT NULL,original_chars INTEGER NOT NULL,dimensions INTEGER NOT NULL,format TEXT NOT NULL,vector_sha BLOB NOT NULL,vector BLOB NOT NULL,PRIMARY KEY(owner,message_id,model_id,chunk_index))")
            db.execSQL("CREATE INDEX IF NOT EXISTS semantic_vectors_scope ON semantic_vectors(owner,conversation_id,model_id)")
        }
    }
    fun nextJob(owner: String, id: String, model: String, now: Long = System.currentTimeMillis()): LocalSemanticJob? = transaction { db ->
        require(model.isNotBlank() && model.length <= 2048)
        pendingJob(db,owner,id,model,now)?.let { return@transaction it }
        // Seed only a few metadata rows, including upgraded archives. No original bodies or vectors here.
        db.rawQuery("SELECT m.id,m.sha256,m.revision FROM messages m LEFT JOIN semantic_jobs j ON j.owner=m.owner AND j.message_id=m.id AND j.model_id=? WHERE m.owner=? AND m.conversation_id=? AND m.index_state IN ('active','cold') AND (j.message_id IS NULL OR j.raw_sha<>m.sha256 OR (j.revision<>m.revision AND j.state<>'ready' AND NOT(j.state='skipped' AND j.error_code='no_text'))) ORDER BY CASE m.index_state WHEN 'active' THEN 0 ELSE 1 END,m.seq DESC LIMIT 4", arrayOf(model, owner, id)).use {
            while (it.moveToNext()) db.insertWithOnConflict("semantic_jobs", null, values("owner" to owner,"conversation_id" to id,
                "message_id" to it.getString(0),"model_id" to model,"raw_sha" to it.getString(1),"revision" to it.getInt(2)), SQLiteDatabase.CONFLICT_REPLACE)
        }
        pendingJob(db,owner,id,model,now)
    }
    private fun pendingJob(db:SQLiteDatabase,owner:String,id:String,model:String,now:Long):LocalSemanticJob? =
        db.rawQuery("SELECT j.message_id,j.raw_sha,j.revision,j.next_chunk,j.attempts,j.total_chunks FROM semantic_jobs j JOIN messages m ON m.id=j.message_id AND m.owner=j.owner AND m.conversation_id=j.conversation_id WHERE j.owner=? AND j.conversation_id=? AND j.model_id=? AND j.state IN ('pending','failed') AND j.retry_at<=? AND j.raw_sha=m.sha256 AND j.revision=m.revision AND m.index_state IN ('active','cold') ORDER BY j.processed_at,j.message_id LIMIT 1", arrayOf(owner,id,model,now.toString())).use {
            if (!it.moveToFirst()) null else LocalSemanticJob(owner,id,it.getString(0),model,it.getString(1),it.getInt(2),it.getInt(3),it.getInt(4),if (it.isNull(5)) null else it.getInt(5))
        }
    /** No tokenizer/vector is needed for a verified empty caption; only this derived job becomes terminal. */
    fun skipNoText(job:LocalSemanticJob,original:LocalMemoryOriginal):Boolean = transaction { db ->
        if (original.text.isNotBlank() || original.owner != job.owner || original.conversationId != job.conversationId ||
            original.messageId != job.messageId || original.sha256 != job.rawSha || original.revision != job.revision) return@transaction false
        if (archive.recognitionOriginal(job.owner,job.conversationId,job.messageId) != original) return@transaction false
        val current = db.rawQuery("SELECT 1 FROM messages WHERE owner=? AND conversation_id=? AND id=? AND sha256=? AND revision=? AND role=? AND created_at=? AND index_state=?",
            arrayOf(job.owner,job.conversationId,job.messageId,job.rawSha,job.revision.toString(),original.role,original.createdAt,original.indexState)).use { it.moveToFirst() }
        if (!current) return@transaction false
        val updated = db.update("semantic_jobs",values("state" to "skipped","error_code" to "no_text","next_chunk" to 0,
            "total_chunks" to 0,"attempts" to 0,"retry_at" to 0L,"processed_at" to System.currentTimeMillis()),
            "owner=? AND conversation_id=? AND message_id=? AND model_id=? AND raw_sha=? AND revision=? AND next_chunk=? AND state IN ('pending','failed')",
            arrayOf(job.owner,job.conversationId,job.messageId,job.modelIdentity,job.rawSha,job.revision.toString(),job.nextChunk.toString()))
        if (updated != 1) return@transaction false
        db.delete("semantic_vectors","owner=? AND conversation_id=? AND message_id=? AND model_id=? AND raw_sha=?",
            arrayOf(job.owner,job.conversationId,job.messageId,job.modelIdentity,job.rawSha))
        true
    }
    fun commit(job: LocalSemanticJob, original: LocalMemoryOriginal, chunks: List<SemanticChunk>, totalChunks: Int, dimensions: Int): Boolean = transaction { db ->
        require(chunks.isNotEmpty() && chunks.size <= 8 && totalChunks >= job.nextChunk + chunks.size)
        require(job.totalChunks == null || job.totalChunks == totalChunks)
        require(original.owner == job.owner && original.conversationId == job.conversationId && original.messageId == job.messageId)
        val current = db.rawQuery("SELECT sha256,revision,index_state FROM messages WHERE owner=? AND conversation_id=? AND id=?", arrayOf(job.owner,job.conversationId,job.messageId)).use {
            it.moveToFirst() && it.getString(0) == job.rawSha && it.getInt(1) == job.revision && it.getString(2) == original.indexState
        }
        if (!current || original.sha256 != job.rawSha || original.revision != job.revision) return@transaction false
        val expected = db.rawQuery("SELECT next_chunk FROM semantic_jobs WHERE owner=? AND message_id=? AND model_id=? AND raw_sha=? AND revision=? AND state IN ('pending','failed')", arrayOf(job.owner,job.messageId,job.modelIdentity,job.rawSha,job.revision.toString())).use {
            it.moveToFirst() && it.getInt(0) == job.nextChunk
        }
        if (!expected) return@transaction false
        chunks.forEachIndexed { offset, chunk ->
            require(chunk.chunkIndex == job.nextChunk + offset && chunk.textStart >= 0 && chunk.textEndExclusive > chunk.textStart && chunk.textEndExclusive <= original.text.length)
            require(!Character.isLowSurrogate(original.text[chunk.textStart]) && !Character.isHighSurrogate(original.text[chunk.textEndExclusive - 1])) { "语义片段不能切开Unicode字符" }
            val encoded = LocalSemanticVectorRules.encode(chunk.embedding, dimensions)
            db.insertWithOnConflict("semantic_vectors", null, values("owner" to job.owner,"conversation_id" to job.conversationId,
                "message_id" to job.messageId,"model_id" to job.modelIdentity,"raw_sha" to job.rawSha,"revision" to job.revision,
                "chunk_index" to chunk.chunkIndex,"start_offset" to chunk.textStart,"end_offset" to chunk.textEndExclusive,
                "original_chars" to original.text.length,"dimensions" to dimensions,"format" to LocalSemanticVectorRules.FORMAT,
                "vector_sha" to java.security.MessageDigest.getInstance("SHA-256").digest(encoded),"vector" to encoded), SQLiteDatabase.CONFLICT_REPLACE)
        }
        db.update("semantic_jobs", values("next_chunk" to job.nextChunk + chunks.size,"total_chunks" to totalChunks,"state" to if (job.nextChunk + chunks.size == totalChunks) "ready" else "pending",
            "attempts" to 0,"retry_at" to 0L,"processed_at" to System.currentTimeMillis(),"error_code" to null), "owner=? AND message_id=? AND model_id=?", arrayOf(job.owner,job.messageId,job.modelIdentity))
        true
    }
    fun fail(job: LocalSemanticJob, code: String) {
        require(code.matches(Regex("[a-z_]{1,64}")))
        val attempts = minOf(job.attempts + 1, 10)
        archive.writableDatabase.update("semantic_jobs", values("state" to "failed","attempts" to attempts,"error_code" to code,
            "processed_at" to System.currentTimeMillis(),"retry_at" to System.currentTimeMillis() + minOf(86400000L, 300000L * (1L shl attempts))),
            "owner=? AND message_id=? AND model_id=? AND raw_sha=? AND revision=? AND next_chunk=? AND state IN ('pending','failed')", arrayOf(job.owner,job.messageId,job.modelIdentity,job.rawSha,job.revision.toString(),job.nextChunk.toString()))
    }
    fun isComplete(owner: String, id: String, model: String, exclude: Set<String>): Boolean {
        require(exclude.size <= 100)
        val exclusions = if (exclude.isEmpty()) "" else " AND m.id NOT IN (${exclude.joinToString(",") { "?" }})"
        val args = listOf(owner,id) + exclude.toList() + model
        return archive.readableDatabase.rawQuery("SELECT EXISTS(SELECT 1 FROM messages m WHERE m.owner=? AND m.conversation_id=? AND m.index_state IN ('active','cold')$exclusions AND NOT EXISTS(SELECT 1 FROM semantic_jobs j WHERE j.owner=m.owner AND j.message_id=m.id AND j.model_id=? AND j.raw_sha=m.sha256 AND (j.state='ready' OR (j.state='skipped' AND j.error_code='no_text'))))", args.toTypedArray()).use {
            it.moveToFirst() && it.getInt(0) == 0
        }
    }
    fun hasVectors(owner:String,id:String,model:String):Boolean = archive.readableDatabase.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM semantic_vectors v JOIN messages m ON m.id=v.message_id AND m.owner=v.owner AND m.conversation_id=v.conversation_id WHERE v.owner=? AND v.conversation_id=? AND v.model_id=? AND v.raw_sha=m.sha256)", arrayOf(owner,id,model)).use {
        it.moveToFirst() && it.getInt(0) != 0
    }
    /** Metadata-only progress: real vectors and no-text skips are distinct; failures never count as complete. */
    fun statistics(owner:String,id:String,model:String):LocalSemanticStatistics {
        val counts = archive.readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE j.state WHEN 'ready' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE j.state WHEN 'failed' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN j.total_chunks IS NOT NULL OR j.state='ready' THEN 0 ELSE 1 END),0),COALESCE(SUM(COALESCE(j.total_chunks,CASE j.state WHEN 'ready' THEN j.next_chunk ELSE 0 END)),0),COALESCE(SUM(CASE WHEN j.state='skipped' AND j.error_code='no_text' THEN 1 ELSE 0 END),0) FROM messages m LEFT JOIN semantic_jobs j ON j.owner=m.owner AND j.conversation_id=m.conversation_id AND j.message_id=m.id AND j.model_id=? AND j.raw_sha=m.sha256 WHERE m.owner=? AND m.conversation_id=? AND m.index_state IN ('active','cold')",arrayOf(model,owner,id)).use {
            check(it.moveToFirst()); LongArray(6) { index -> it.getLong(index) }
        }
        val vectors = archive.readableDatabase.rawQuery("SELECT COUNT(*) FROM semantic_vectors v JOIN messages m ON m.id=v.message_id AND m.owner=v.owner AND m.conversation_id=v.conversation_id WHERE v.owner=? AND v.conversation_id=? AND v.model_id=? AND v.raw_sha=m.sha256 AND m.index_state IN ('active','cold')",arrayOf(owner,id,model)).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        return LocalSemanticStatistics(counts[0],counts[1],counts[2],counts[0] - counts[1] - counts[5],vectors,if (counts[3] == 0L) counts[4] else null,counts[3],counts[4],counts[5])
    }
    fun firstVector(owner: String, id: String, messageId: String, model: String): FloatArray? =
        archive.readableDatabase.rawQuery(
            "SELECT v.vector,v.dimensions FROM semantic_vectors v JOIN messages m ON m.id=v.message_id AND m.owner=v.owner AND m.conversation_id=v.conversation_id " +
                "WHERE v.owner=? AND v.conversation_id=? AND v.message_id=? AND v.model_id=? AND v.raw_sha=m.sha256 AND m.index_state IN ('active','cold') ORDER BY v.chunk_index LIMIT 1",
            arrayOf(owner, id, messageId, model)).use {
            if (!it.moveToFirst()) null else LocalSemanticVectorRules.decode(it.getBlob(0), it.getInt(1))
        }

    suspend fun search(owner: String, id: String, model: String, dimensions: Int, queries: List<FloatArray>, exclude: Set<String>, state: String = "active",minimumScore:Double = -1.0): List<LocalSemanticHit> {
        require(exclude.size <= 100 && state in setOf("active","cold") && minimumScore in -1.0..1.0)
        val exclusions = if (exclude.isEmpty()) "" else " AND m.id NOT IN (${exclude.joinToString(",") { "?" }})"
        val args = listOf(owner,id,model,dimensions.toString(),LocalSemanticVectorRules.FORMAT,state) + exclude.toList()
        val best = mutableMapOf<String,LocalSemanticHit>()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val factors = linkedMapOf<String,Double>()
        val ordering = compareByDescending<LocalSemanticHit> { it.rankingScore }.thenBy { if (it.role == "user") 0 else 1 }
            .thenBy { if (it.tier == "permanent") 0 else if (it.tier == "long") 1 else 2 }.thenByDescending { it.sequence }
        archive.readableDatabase.rawQuery("SELECT v.message_id,v.start_offset,v.end_offset,v.original_chars,v.raw_sha,v.vector,m.role,m.created_at,m.tier,m.seq,v.vector_sha FROM semantic_vectors v CROSS JOIN messages m WHERE m.id=v.message_id AND m.owner=v.owner AND m.conversation_id=v.conversation_id AND v.owner=? AND v.conversation_id=? AND v.model_id=? AND v.dimensions=? AND v.format=? AND m.index_state=? AND v.raw_sha=m.sha256$exclusions", args.toTypedArray()).use { cursor ->
            var rows = 0
            while (cursor.moveToNext()) {
                if (rows++ % 128 == 0) currentCoroutineContext().ensureActive()
                val vector = cursor.getBlob(5)
                if (!digest.digest(vector).contentEquals(cursor.getBlob(10))) {
                    rebuildCorruptSource(owner,id,model,cursor.getString(0),cursor.getString(4))
                    throw IllegalStateException("语义索引校验失败，已安排从独立原文恢复")
                }
                val score = LocalSemanticVectorRules.score(vector,queries,dimensions)
                // Metadata cannot turn weak similarity into accepted semantic evidence.
                if (score < minimumScore) continue
                val messageId = cursor.getString(0)
                val factor = factors[messageId] ?: annotationFactor(owner,id,messageId,cursor.getString(4),cursor.getString(6),cursor.getString(7)).also {
                    if (factors.size >= 128) factors.remove(factors.keys.first())
                    factors[messageId] = it
                }
                val hit = LocalSemanticHit(messageId,cursor.getString(6),cursor.getString(7),cursor.getString(8),cursor.getString(4),
                    cursor.getInt(1),cursor.getInt(2),cursor.getInt(3),score,cursor.getLong(9),factor)
                val previous = best[hit.messageId]
                if (previous == null || ordering.compare(hit, previous) < 0) best[hit.messageId] = hit
                if (best.size > 8) best.remove(best.values.maxWith(ordering).messageId)
            }
        }
        return best.values.sortedWith(ordering)
    }
    private fun annotationFactor(owner:String,id:String,messageId:String,sha:String,role:String,createdAt:String):Double = archive.readableDatabase.rawQuery(
        "SELECT COALESCE(MAX(1.0 + CASE tier WHEN 'long' THEN 0.05 WHEN 'permanent' THEN 0.10 ELSE 0.0 END + CASE pinned WHEN 1 THEN 0.05 ELSE 0.0 END),1.0) FROM native_memory_annotations WHERE owner=? AND conversation_id=? AND message_id=? AND source_sha=? AND role=? AND source_created_at=? AND validity='current'",
        arrayOf(owner,id,messageId,sha,role,createdAt)).use { check(it.moveToFirst()); it.getDouble(0).coerceIn(1.0,1.15) }
    private fun rebuildCorruptSource(owner:String,id:String,model:String,messageId:String,sha:String):Unit = transaction { db ->
        val revision = db.rawQuery("SELECT revision FROM messages WHERE owner=? AND conversation_id=? AND id=? AND sha256=?",arrayOf(owner,id,messageId,sha)).use {
            if (it.moveToFirst()) it.getInt(0) else return@transaction
        }
        db.delete("semantic_vectors","owner=? AND conversation_id=? AND message_id=? AND model_id=? AND raw_sha=?",arrayOf(owner,id,messageId,model,sha))
        db.insertWithOnConflict("semantic_jobs",null,values("owner" to owner,"conversation_id" to id,"message_id" to messageId,
            "model_id" to model,"raw_sha" to sha,"revision" to revision,"error_code" to "vector_checksum_failed"),SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }
    private fun values(vararg entries: Pair<String,Any?>) = ContentValues().apply { entries.forEach { (key,value) -> when(value) {
        null -> putNull(key); is String -> put(key,value); is Int -> put(key,value); is Long -> put(key,value); is ByteArray -> put(key,value)
        else -> throw IllegalArgumentException("语义索引类型不支持")
    } } }
    private fun <T> transaction(block:(SQLiteDatabase)->T):T {
        val db = archive.writableDatabase; db.beginTransaction()
        return try { block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
}
