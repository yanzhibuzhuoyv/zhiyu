package com.zhiyu.app.ui

import android.content.Context

/** Device-level onboarding acknowledgement only; no permissions or chat data are stored. */
class BackgroundGuidanceStore(context: Context, preferencesName: String = "background_guidance_v1") {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName.also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    }, Context.MODE_PRIVATE)

    fun handled(): Boolean = preferences.getBoolean("handled", false)
    fun markHandled() {
        check(preferences.edit().putBoolean("handled", true).commit()) { "后台接收说明的处理状态未能保存" }
    }
}
