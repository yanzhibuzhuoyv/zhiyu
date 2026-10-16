package com.zhiyu.app.data.voicemessages

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.zhiyu.app.domain.VoiceTuningSettings

internal data class VoiceClipEntry(val record: VoiceClipRecord, val kind: String, val transcriptSha: String?,
    val audioSha: String?, val audioBytes: Int, val attempts: Int)

/** Separate ledger; the main chat schema, lossless originals and retrieval indices are untouched. */
internal class VoiceClipLedger(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE voice_clips(id TEXT PRIMARY KEY,kind TEXT NOT NULL CHECK(kind IN ('user','reply')),
            owner TEXT,chat_id TEXT,message_id TEXT,source_sha TEXT,transcript_sha TEXT,duration_ms INTEGER NOT NULL,
            sample_rate INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('PENDING','READY','FAILED')),
            audio_sha TEXT,audio_bytes INTEGER NOT NULL,pitch REAL NOT NULL,volume REAL NOT NULL,error_code TEXT,
            attempts INTEGER NOT NULL DEFAULT 0,UNIQUE(owner,chat_id,message_id))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("语音库需要明确迁移，不能清除已发送语音")
    fun entry(id: String): VoiceClipEntry? = readableDatabase.rawQuery("SELECT * FROM voice_clips WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) it.entry() else null
    }
    fun insert(id: String, kind: String, transcriptSha: String?, state: VoiceClipState,
        duration: Long = 0, rate: Int = 16000, audioSha: String? = null, audioBytes: Int = 0,
        owner: String? = null, chat: String? = null, message: String? = null, sourceSha: String? = null,
        tuning: VoiceTuningSettings = VoiceTuningSettings()) {
        writableDatabase.insertOrThrow("voice_clips", null, cv("id" to id, "kind" to kind, "owner" to owner,
            "chat_id" to chat, "message_id" to message, "source_sha" to sourceSha, "transcript_sha" to transcriptSha,
            "duration_ms" to duration, "sample_rate" to rate, "state" to state.name, "audio_sha" to audioSha,
            "audio_bytes" to audioBytes, "pitch" to tuning.pitchSemitones, "volume" to tuning.volume))
    }
    fun update(id: String, vararg fields: Pair<String, Any?>) {
        check(writableDatabase.update("voice_clips", cv(*fields), "id=?", arrayOf(id)) == 1) { "语音状态已改变" }
    }
    fun pendingCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM voice_clips WHERE state='PENDING'", null).use { it.moveToFirst(); it.getInt(0) }
    fun delete(id: String) { writableDatabase.delete("voice_clips", "id=?", arrayOf(id)) }
    private fun Cursor.entry(): VoiceClipEntry {
        fun text(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
        fun number(name: String) = getLong(getColumnIndexOrThrow(name))
        val record = VoiceClipRecord(text("id")!!, text("owner"), text("chat_id"), text("message_id"), text("source_sha"),
            number("duration_ms"), number("sample_rate").toInt(), VoiceClipState.valueOf(text("state")!!), text("error_code"),
            getFloat(getColumnIndexOrThrow("pitch")), getFloat(getColumnIndexOrThrow("volume")))
        VoiceTuningSettings(pitchSemitones = record.pitchSemitones, volume = record.volume)
        require(record.sampleRate in 8000..48000 && record.durationMillis in 0..180_000)
        return VoiceClipEntry(record, text("kind")!!, text("transcript_sha"), text("audio_sha"), number("audio_bytes").toInt(), number("attempts").toInt())
    }
    companion object {
        private fun cv(vararg entries: Pair<String, Any?>) = ContentValues().apply { entries.forEach { (key, value) ->
            when (value) { null -> putNull(key); is String -> put(key, value); is Int -> put(key, value)
                is Long -> put(key, value); is Float -> put(key, value); else -> error("Invalid voice metadata") }
        } }
    }
}
