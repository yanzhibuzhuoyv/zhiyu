package com.zhiyu.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.zhiyu.app.domain.MediaRules

/** Uses the same archive/transaction as the caller's chat append, including rollback on failure. */
internal class LocalPhotoDeliveryStore(context: Context, private val archive: LocalChatDatabase? = null) {
    private val app = context.applicationContext
    private val legacy = app.getSharedPreferences("photo_delivery_used_sha_v1", Context.MODE_PRIVATE)

    fun delivered(chat: String, candidates: Set<String>): Set<String> = withArchive { database ->
        migrate(database, chat)
        candidates.forEach(::checkSha)
        buildSet {
            candidates.chunked(400).forEach { group ->
                val slots = group.joinToString(",") { "?" }
                database.readableDatabase.rawQuery("SELECT sha FROM photo_deliveries WHERE chat=? AND sha IN ($slots)",
                    (listOf(chat) + group).toTypedArray()).use { rows ->
                    while (rows.moveToNext()) add(rows.getString(0))
                }
            }
        }
    }

    fun <T> deliver(chat: String, sha: String, unavailable: () -> T, save: () -> T): T =
        synchronized(LocalMediaStore.lock) {
            checkSha(sha)
            withArchive { database ->
                database.atomic {
                    migrate(database, chat)
                    val used = database.readableDatabase.rawQuery(
                        "SELECT 1 FROM photo_deliveries WHERE chat=? AND sha=?", arrayOf(chat, sha)).use { it.moveToFirst() }
                    if (used) unavailable() else {
                        // The nested chat append and this insert commit together on the same SQLite connection.
                        val result = save()
                        database.writableDatabase.insertOrThrow("photo_deliveries", null, values(chat, sha))
                        result
                    }
                }
            }
        }

    private fun migrate(database: LocalChatDatabase, chat: String) {
        MediaRules.path(chat)
        database.atomic {
            val migrated = database.readableDatabase.rawQuery(
                "SELECT 1 FROM photo_delivery_migrations WHERE chat=?", arrayOf(chat)).use { it.moveToFirst() }
            if (!migrated) {
                val old = legacy.getStringSet("chat_$chat", emptySet()).orEmpty().toSet()
                require(old.size <= 4096) { "旧照片交付记录无效，原记录已保留" }
                old.forEach { sha ->
                    checkSha(sha)
                    database.writableDatabase.insertWithOnConflict("photo_deliveries", null, values(chat, sha), SQLiteDatabase.CONFLICT_IGNORE)
                }
                database.writableDatabase.insertOrThrow("photo_delivery_migrations", null, ContentValues().apply { put("chat", chat) })
            }
        }
    }

    private fun <T> withArchive(block: (LocalChatDatabase) -> T): T =
        archive?.let(block) ?: LocalChatDatabase(app).use(block)

    private fun checkSha(sha: String) { require(Regex("[a-f0-9]{64}").matches(sha)) { "照片校验标识无效" } }
    private fun values(chat: String, sha: String) = ContentValues().apply { put("chat", chat); put("sha", sha) }

    companion object {
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS photo_deliveries (chat TEXT NOT NULL, sha TEXT NOT NULL, PRIMARY KEY(chat,sha))")
            db.execSQL("CREATE TABLE IF NOT EXISTS photo_delivery_migrations (chat TEXT PRIMARY KEY)")
        }
    }
}
