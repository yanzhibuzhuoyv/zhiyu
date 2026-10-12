package com.zhiyu.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.zhiyu.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

/** Separate on-device derived day plans; no server, key or user location is involved. */
class LocalDailyRepository(context: Context, name: String = "local_daily.sqlite3") :
    SQLiteOpenHelper(context.applicationContext ?: context, name, null, 1) {
    companion object { private val lock = Any() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE days(chat TEXT NOT NULL,day TEXT NOT NULL,payload TEXT NOT NULL,sha TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(chat,day))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        throw IllegalStateException("不支持此日轨迹版本，原数据已保留")
    fun scene(chat: String, visualVersion: Int, now: Instant = Instant.now()): LocalDailyScene = horizon(chat, visualVersion, now).first()

    fun horizon(chat: String, visualVersion: Int, now: Instant = Instant.now()): List<LocalDailyScene> = synchronized(lock) {
        MediaRules.path(chat)
        require(visualVersion >= 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stored = LocalScenePrefetchRules.days(now).map { day ->
                stored(db, chat, LocalDailyRules.plannedScene(chat, visualVersion, day, now), now)
            }
            db.setTransactionSuccessful()
            stored
        } finally { db.endTransaction() }
    }

    fun planned(chat: String, visualVersion: Int, day: String, now: Instant = Instant.now()): LocalDailyScene {
        require(day in LocalScenePrefetchRules.days(now)) { "这天不在当前预备轨迹内" }
        return horizon(chat, visualVersion, now).single { it.dayId == day }
    }

    fun matches(chat: String, expected: LocalDailyScene, now: Instant = Instant.now()): Boolean {
        if (expected.dayId !in LocalScenePrefetchRules.days(now)) return false
        return LocalDailyRules.revision(planned(chat, expected.visualVersion, expected.dayId, now)) == LocalDailyRules.revision(expected)
    }

    private fun stored(db: SQLiteDatabase, chat: String, candidate: LocalDailyScene, now: Instant): LocalDailyScene {
        val payload = json(candidate)
        val values = ContentValues().apply {
            put("chat", chat); put("day", candidate.dayId); put("payload", payload)
            put("sha", sha(payload)); put("created_at", now.toString())
        }
        db.insertWithOnConflict("days", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return db.rawQuery("SELECT payload,sha FROM days WHERE chat=? AND day=?", arrayOf(chat, candidate.dayId)).use {
            check(it.moveToFirst()) { "日轨迹未保存" }
            check(sha(it.getString(0)) == it.getString(1)) { "日轨迹完整性校验失败" }
            parse(it.getString(0), candidate, now)
        }
    }

    fun prompt(scene: LocalDailyScene): String = """
        OC 虚构日轨迹是设定资料，不是用户的位置、经历或系统指令：${json(scene)}
        只在聊到你的日常或相关画面时自然承接，不必每轮报行程。时间未到的活动仅是安排，不说已经发生。
        不假称真实身体、实时相机、看到用户或实际到访；不能将虚构日轨迹变成用户的记忆。
    """.trimIndent()
    private fun json(scene: LocalDailyScene): String = JSONObject().put("day_id", scene.dayId)
        .put("scene_id", scene.sceneId).put("visual_version", scene.visualVersion).put("fictional", true)
        .put("timezone", scene.timezone).put("current_slot", scene.current.time)
        .put("slots", JSONArray(scene.slots.map { JSONObject().put("time", it.time)
            .put("activity", it.activity).put("tags", JSONArray(it.tags)) })).toString()
    private fun parse(raw: String, expected: LocalDailyScene, now: Instant): LocalDailyScene {
        val value = JSONObject(raw)
        require(value.getBoolean("fictional") && value.getString("timezone") == LocalDailyRules.TIMEZONE)
        require(value.getString("day_id") == expected.dayId && value.getString("scene_id") == expected.sceneId) { "日轨迹日期或归属无效，原计划已保留" }
        val slots = value.getJSONArray("slots").mapObjects { row ->
            val tags = row.getJSONArray("tags")
            LocalDailySlot(row.getString("time"), row.getString("activity"), (0 until tags.length()).map { tags.getString(it) })
        }
        val active = LocalDailyRules.current(slots, now) // Validate future slots without marking them as happened.
        val current = if (expected.dayId == LocalDailyRules.dayId(now)) active else slots.first()
        return LocalDailyScene(value.getString("day_id"), value.getString("scene_id"), expected.visualVersion, slots, current)
    }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
