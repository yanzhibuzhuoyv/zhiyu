package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.VoiceTuningSettings

class LocalVoicePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("zhiyu_local_voice", Context.MODE_PRIVATE)
    companion object { private val lock = Any() }
    fun selected(): Int = synchronized(lock) {
        val saved = prefs.getInt("speaker", LocalVoiceRules.choices.first().id)
        val current = if (prefs.getBoolean("speaker_catalog_v1", false)) LocalVoiceRules.voice(saved).id
            else LocalVoiceRules.storedVoice(saved)
        if (current != saved) select(current)
        current
    }
    fun select(id: Int) = synchronized(lock) {
        LocalVoiceRules.voice(id)
        check(prefs.edit().putInt("speaker", id).putBoolean("speaker_catalog_v1", true).commit()) { "无法保存声音选择" }
    }

    fun tuning(): VoiceTuningSettings = synchronized(lock) {
        VoiceTuningSettings(speed = prefs.getFloat("speed", 1f),
            pitchSemitones = prefs.getFloat("pitch_semitones", 0f), volume = prefs.getFloat("volume", 1f))
    }

    fun saveTuning(value: VoiceTuningSettings) = synchronized(lock) {
        check(prefs.edit().putFloat("speed", value.speed).putFloat("pitch_semitones", value.pitchSemitones)
            .putFloat("volume", value.volume).commit()) { "无法保存声音参数" }
    }

    fun snapshot(): Pair<Int, VoiceTuningSettings> = synchronized(lock) { selected() to tuning() }
}
