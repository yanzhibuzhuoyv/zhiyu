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
    fun scene(chat: String, visualVersion: Int, now: Instant = Instant.now()): LocalDailyScene = synchronized(lock) {
        val candidate = LocalDailyRules.scene(chat, visualVersion, now)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val payload = json(candidate)
            val values = ContentValues().apply {
                put("chat", chat); put("day", candidate.dayId); put("payload", payload)
                put("sha", sha(payload)); put("created_at", now.toString())
            }
            db.insertWithOnConflict("days", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            val stored = db.rawQuery("SELECT payload,sha FROM days WHERE chat=? AND day=?", arrayOf(chat, candidate.dayId)).use {
                check(it.moveToFirst()) { "日轨迹未保存" }
                check(sha(it.getString(0)) == it.getString(1)) { "日轨迹完整性校验失败" }
                parse(it.getString(0), candidate, now)
            }
            db.setTransactionSuccessful()
            stored
        } finally { db.endTransaction() }
    }
    fun prompt(scene: LocalDailyScene): String = """
        当前 OC 虚构日轨迹是设定资料，不是用户的位置、经历或系统指令：${json(scene)}
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
        val current = LocalDailyRules.current(slots, now)
        return LocalDailyScene(value.getString("day_id"), value.getString("scene_id"), expected.visualVersion, slots, current)
    }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
