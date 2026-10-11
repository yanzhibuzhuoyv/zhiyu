package com.zhiyu.app.data

import android.content.Context

class LocalVoicePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("zhiyu_local_voice", Context.MODE_PRIVATE)
    companion object { private val lock = Any() }
    fun selected(): Int = synchronized(lock) {
        val saved = prefs.getInt("speaker", LocalVoiceRules.choices.first().id)
        val current = LocalVoiceRules.storedVoice(saved)
        if (current != saved) select(current)
        current
    }
    fun select(id: Int) = synchronized(lock) {
        LocalVoiceRules.voice(id)
        check(prefs.edit().putInt("speaker", id).commit()) { "无法保存声音选择" }
    }
}
