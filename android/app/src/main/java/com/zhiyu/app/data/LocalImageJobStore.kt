package com.zhiyu.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.zhiyu.app.domain.*

data class StoredImageJob(val job: LocalGenerationJob, val assetId: String?)

/** Durable charge ledger, separate from unmodified chat originals. */
class LocalImageJobStore(context: Context, name: String = "local_image_jobs.sqlite3") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    companion object { private val lock = Any(); private val recovered = mutableSetOf<String>() }
    private val identity = context.getDatabasePath(name).absolutePath
    init { require(Regex("[a-zA-Z0-9_-]+\\.sqlite3").matches(name)) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs(request_id TEXT PRIMARY KEY,chat_id TEXT NOT NULL,kind TEXT NOT NULL," +
            "version INTEGER NOT NULL,day TEXT NOT NULL,prompt_sha TEXT NOT NULL,state TEXT NOT NULL," +
            "asset_id TEXT,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX jobs_owner ON jobs(chat_id,kind,state)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { error("未知生成记录版本") }
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
