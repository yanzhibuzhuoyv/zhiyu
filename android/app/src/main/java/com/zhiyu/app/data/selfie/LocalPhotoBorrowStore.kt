package com.zhiyu.app.data.selfie

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.zhiyu.app.domain.*

/** One immutable borrow receipt and replacement opportunity per original daily slot. No pixels or keys. */
class LocalPhotoBorrowStore(context: Context) {
    private val app = context.applicationContext
    private fun helper() = object : SQLiteOpenHelper(app, "photo_borrows_v1.sqlite3", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE borrowed(request_id TEXT PRIMARY KEY,chat TEXT NOT NULL,asset TEXT NOT NULL,sha TEXT NOT NULL," +
                "source_day TEXT NOT NULL,trajectory TEXT NOT NULL,slot INTEGER NOT NULL,photo_time TEXT NOT NULL," +
                "recipient_day TEXT NOT NULL,oc_scope TEXT NOT NULL,UNIQUE(chat,sha),UNIQUE(chat,source_day,trajectory,slot))")
            db.execSQL("CREATE INDEX borrowed_asset ON borrowed(chat,asset)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            throw IllegalStateException("未知借图记录版本，原记录已保留")
    }
    private fun <T> database(block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        val helper = helper()
        try { block(helper.writableDatabase) } finally { helper.close() }
    }

    fun adjusted(chat: String, target: LocalScenePrefetchTarget): LocalScenePrefetchTarget {
        MediaRules.path(chat)
        if (target.selfie != null) return target
        return database { db ->
            db.rawQuery("SELECT request_id,sha,photo_time FROM borrowed WHERE chat=? AND source_day=? AND trajectory=? AND slot=?",
                arrayOf(chat, target.scene.dayId, LocalDailyRules.revision(target.scene), target.index.toString())).use {
                if (!it.moveToFirst()) target.copy(reshoot = null) else {
                    val next = LocalPhotoBorrowRules.replacement(target.copy(reshoot = null), it.getString(0), it.getString(1))
                    require(next.reshoot!!.photoTime == it.getString(2)) { "借图补充记录已变化" }
                    next
                }
            }
        }
    }
    fun available(chat: String, attachment: MediaAttachment): Boolean {
        MediaRules.path(chat); MediaRules.check(attachment)
        return database { db ->
            db.rawQuery("SELECT 1 FROM borrowed WHERE chat=? AND (asset=? OR sha=?) LIMIT 1",
                arrayOf(chat, attachment.id, attachment.sha256)).use { !it.moveToFirst() }
        }
    }
    fun borrowed(chat: String, sha: String): Boolean {
        MediaRules.path(chat); ChatPhotoRules.digest(sha)
        return database { db -> db.rawQuery("SELECT 1 FROM borrowed WHERE chat=? AND sha=? LIMIT 1", arrayOf(chat, sha)).use { it.moveToFirst() } }
    }
    fun consume(chat: String, photo: LocalBorrowedPhoto): Boolean {
        MediaRules.path(chat); MediaRules.check(photo.attachment); MediaRules.path(photo.sourceRequestId)
        ChatPhotoRules.digest(photo.identityScope); ChatPhotoRules.digest(photo.recipientTrajectory)
        require(photo.sourceIndex in photo.sourceScene.slots.indices)
        val target = LocalScenePrefetchTarget(photo.sourceScene, photo.sourceIndex, photo.sourceScene.slots[photo.sourceIndex], true)
        val replacement = LocalPhotoBorrowRules.replacement(target, photo.sourceRequestId, photo.attachment.sha256)
        return database { db ->
            db.beginTransaction()
            try {
                val count = db.rawQuery("SELECT COUNT(*) FROM borrowed WHERE chat=?", arrayOf(chat)).use { check(it.moveToFirst()); it.getLong(0) }
                require(count < 4096) { "借图记录已达到本机上限，已停止新增" }
                val value = ContentValues().apply {
                    put("request_id", photo.sourceRequestId); put("chat", chat); put("asset", photo.attachment.id); put("sha", photo.attachment.sha256)
                    put("source_day", photo.sourceScene.dayId); put("trajectory", LocalDailyRules.revision(photo.sourceScene)); put("slot", photo.sourceIndex)
                    put("photo_time", replacement.reshoot!!.photoTime); put("recipient_day", photo.recipientDay); put("oc_scope", photo.identityScope)
                }
                val inserted = db.insertWithOnConflict("borrowed", null, value, SQLiteDatabase.CONFLICT_IGNORE) >= 0
                db.setTransactionSuccessful()
                inserted
            } finally { db.endTransaction() }
        }
    }
    companion object { private val lock = Any() }
}
