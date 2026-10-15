package com.zhiyu.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.zhiyu.app.domain.*

data class StoredImageJob(val job: LocalGenerationJob, val assetId: String?)
data class SceneJobPage(val items: List<StoredImageJob>, val nextCursor: String?)

/** Durable charge ledger, separate from unmodified chat originals. */
class LocalImageJobStore(context: Context, name: String = "local_image_jobs.sqlite3") :
    SQLiteOpenHelper(context.applicationContext, name, null, 2) {
    companion object { private val lock = Any(); private val recovered = mutableSetOf<String>() }
    private val identity = context.getDatabasePath(name).absolutePath
    init { require(Regex("[a-zA-Z0-9_-]+\\.sqlite3").matches(name)) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs(request_id TEXT PRIMARY KEY,chat_id TEXT NOT NULL,kind TEXT NOT NULL," +
            "version INTEGER NOT NULL,day TEXT NOT NULL,prompt_sha TEXT NOT NULL,state TEXT NOT NULL," +
            "asset_id TEXT,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX jobs_owner ON jobs(chat_id,kind,state)")
        createSceneReceipts(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        check(old == 1 && new == 2) { "未知生成记录版本" }
        createSceneReceipts(db)
    }
    private fun createSceneReceipts(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE scene_retries(source_id TEXT NOT NULL UNIQUE,retry_id TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE INDEX jobs_scene_page ON jobs(chat_id,kind,created_at DESC,request_id DESC)")
    }

    fun scenePage(chatId: String, limit: Int = 30, cursor: String? = null): SceneJobPage = synchronized(lock) {
        MediaRules.path(chatId); require(limit in 1..100) { "生活照任务页大小无效" }; recover()
        val anchor = cursor?.let { sceneCursor(chatId, it) }
        val args = mutableListOf(chatId)
        val after = if (anchor == null) "" else {
            args.add(anchor.first.toString()); args.add(anchor.first.toString()); args.add(anchor.second)
            " AND (created_at < ? OR (created_at = ? AND request_id < ?))"
        }
        args.add((limit + 1).toString())
        val rows = writableDatabase.rawQuery("SELECT request_id,chat_id,kind,version,day,prompt_sha,state,asset_id,created_at " +
            "FROM jobs WHERE chat_id=? AND kind='scene'$after ORDER BY created_at DESC,request_id DESC LIMIT ?", args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) {
                val value = LocalGenerationRules.validate(LocalGenerationJob(c.getString(0), c.getString(1), c.getString(2),
                    c.getInt(3), c.getString(4), c.getString(5), c.getString(6)))
                val asset = if (c.isNull(7)) null else c.getString(7).also(MediaRules::path)
                val createdAt = c.getLong(8); check(createdAt >= 0) { "生活照任务时间无效" }
                add(StoredImageJob(value, asset) to createdAt)
            } }
        }
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.last().let { "scene-v1:$chatId:${it.second}:${it.first.job.requestId}" } else null
        SceneJobPage(page.map { it.first }, next)
    }

    private fun sceneCursor(chatId: String, cursor: String): Pair<Long, String> {
        require(cursor.length <= 128) { "生活照任务游标过长" }
        val parts = cursor.split(':')
        require(parts.size == 4 && parts[0] == "scene-v1" && parts[1] == chatId) { "生活照任务游标归属无效" }
        require(parts[2].matches(Regex("0|[1-9][0-9]{0,18}"))) { "生活照任务游标时间无效" }
        val createdAt = parts[2].toLongOrNull() ?: throw IllegalArgumentException("生活照任务游标时间无效")
        MediaRules.path(parts[3])
        writableDatabase.rawQuery("SELECT 1 FROM jobs WHERE request_id=? AND chat_id=? AND kind='scene' AND created_at=?",
            arrayOf(parts[3], chatId, createdAt.toString())).use { require(it.moveToFirst()) { "生活照任务游标已失效" } }
        return createdAt to parts[3]
    }

    fun retryFor(sourceId: String): String? = synchronized(lock) {
        MediaRules.path(sourceId); recover(); receipt("source_id", "retry_id", sourceId)
    }
    fun retrySource(retryId: String): String? = synchronized(lock) {
        MediaRules.path(retryId); recover(); receipt("retry_id", "source_id", retryId)
    }
    private fun receipt(column: String, result: String, id: String): String? =
        writableDatabase.rawQuery("SELECT $result FROM scene_retries WHERE $column=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getString(0).also(MediaRules::path) else null
        }

    fun reserveRetry(sourceId: String, newJob: LocalGenerationJob): Unit = synchronized(lock) {
        MediaRules.path(sourceId); LocalGenerationRules.validate(newJob)
        require(newJob.kind == "scene" && newJob.state == "reserved") { "重试任务必须是待开始的生活照" }; recover()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val source = get(sourceId)?.job
            if (source == null || source.kind != "scene" || source.state != "failed" || retrySource(sourceId) != null)
                throw ApiFailure(409, "只有已确认失败的原生活照任务可重试一次", "scene_retry_denied")
            require(newJob.chatId == source.chatId && newJob.expectedVersion == source.expectedVersion && newJob.dayId == source.dayId &&
                newJob.promptHash == source.promptHash) { "生活照重试的角色、日期或来源已变化" }
            if (retryFor(sourceId) != null || get(newJob.requestId) != null)
                throw ApiFailure(409, "这次生活照重试已登记，请查看已有任务", "scene_retry_reserved")
            val values = ContentValues().apply {
                put("request_id", newJob.requestId); put("chat_id", newJob.chatId); put("kind", newJob.kind)
                put("version", newJob.expectedVersion); put("day", newJob.dayId); put("prompt_sha", newJob.promptHash)
                put("state", newJob.state); put("created_at", System.currentTimeMillis())
            }
            check(db.insertOrThrow("jobs", null, values) >= 0)
            check(db.insertOrThrow("scene_retries", null, ContentValues().apply {
                put("source_id", sourceId); put("retry_id", newJob.requestId)
            }) >= 0)
            db.setTransactionSuccessful()
        } catch (failure: android.database.sqlite.SQLiteConstraintException) {
            throw ApiFailure(409, "这次生活照重试已登记，请查看已有任务", "scene_retry_reserved")
        } finally { db.endTransaction() }
    }
    private fun recover() {
        if (identity in recovered) return
        writableDatabase.execSQL("UPDATE jobs SET state='unknown' WHERE state IN ('reserved','running')")
        recovered.add(identity)
    }
    fun list(chatId: String): List<LocalGenerationJob> = synchronized(lock) {
        MediaRules.path(chatId); recover()
        writableDatabase.rawQuery("SELECT request_id,chat_id,kind,version,day,prompt_sha,state FROM jobs WHERE chat_id=?", arrayOf(chatId)).use { c ->
            buildList { while (c.moveToNext()) add(LocalGenerationRules.validate(LocalGenerationJob(
                c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6)))) }
        }
    }
    fun get(requestId: String): StoredImageJob? = synchronized(lock) {
        MediaRules.path(requestId); recover()
        writableDatabase.rawQuery("SELECT request_id,chat_id,kind,version,day,prompt_sha,state,asset_id FROM jobs WHERE request_id=?", arrayOf(requestId)).use { c ->
            if (!c.moveToFirst()) null else StoredImageJob(LocalGenerationRules.validate(LocalGenerationJob(
                c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6))),
                if (c.isNull(7)) null else c.getString(7))
        }
    }
    fun candidates(chatId: String): List<String> = synchronized(lock) {
        MediaRules.path(chatId); recover()
        writableDatabase.rawQuery("SELECT asset_id FROM jobs WHERE chat_id=? AND kind='oc' AND state IN ('success','stale') AND asset_id IS NOT NULL ORDER BY created_at",arrayOf(chatId)).use { c ->
            buildList { while(c.moveToNext()) add(c.getString(0).also { MediaRules.path(it) }) }
        }
    }
    fun reserve(job: LocalGenerationJob) = synchronized(lock) {
        LocalGenerationRules.validate(job); require(job.state == "reserved"); recover()
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!LocalGenerationRules.canStart(list(job.chatId), job))
                throw ApiFailure(409, "生成机会已使用或仍在等待结果，请先查看已有结果", "generation_reserved")
            val values = ContentValues().apply {
                put("request_id", job.requestId); put("chat_id", job.chatId); put("kind", job.kind)
                put("version", job.expectedVersion); put("day", job.dayId); put("prompt_sha", job.promptHash)
                put("state", job.state); put("created_at", System.currentTimeMillis())
            }
            check(db.insertOrThrow("jobs", null, values) >= 0)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun finish(requestId: String, state: String, assetId: String? = null) = synchronized(lock) {
        MediaRules.path(requestId); require(state in LocalGenerationRules.states)
        assetId?.let { MediaRules.path(it) }
        val values = ContentValues().apply { put("state", state); if (assetId != null) put("asset_id", assetId) }
        check(writableDatabase.update("jobs", values, "request_id=? AND state IN ('reserved','running')", arrayOf(requestId)) == 1) {
            "生成记录已变化，请重新打开外观设置"
        }
    }
}
