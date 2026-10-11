package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.ChatTypographySettings

data class ChatTypographyLoadResult(val settings: ChatTypographySettings, val warning: String? = null)

/** Independent of login, character binding and system accessibility font scaling. */
class ChatTypographyPreferences(context: Context) {
    private val preferences = (context.applicationContext ?: context)
        .getSharedPreferences("chat_typography", Context.MODE_PRIVATE)

    fun load(): ChatTypographyLoadResult = try {
        ChatTypographyLoadResult(ChatTypographySettings(preferences.getInt("font_size_sp", 16)))
    } catch (_: ClassCastException) {
        ChatTypographyLoadResult(ChatTypographySettings(), "字号设置暂时无法读取，已使用默认字号。")
    } catch (_: IllegalArgumentException) {
        ChatTypographyLoadResult(ChatTypographySettings(), "字号设置超出范围，已使用默认字号。")
    }

    fun save(settings: ChatTypographySettings) {
        check(preferences.edit().putInt("font_size_sp", settings.fontSizeSp).commit()) {
            "字号没有保存成功，请重试。"
        }
    }
}
