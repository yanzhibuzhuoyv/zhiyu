package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.AppearanceRules
import com.zhiyu.app.domain.AppearanceSelection
import com.zhiyu.app.domain.AppearanceTarget

data class AppearanceLoadResult(val selection: AppearanceSelection, val invalidTargets: List<AppearanceTarget>)

/** Independent local preferences: changing artwork never rewrites credentials or the bound companion. */
class AppearanceStore(context: Context, preferenceName: String = "chat_appearance") {
    private val preferences = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    fun load(): AppearanceLoadResult {
        val values = preferences.all
        var selection = AppearanceSelection()
        val invalid = mutableListOf<AppearanceTarget>()
        AppearanceTarget.entries.forEach { target ->
            val value = values[target.name] ?: return@forEach
            try {
                require(value is String)
                selection = selection.with(target, AppearanceRules.documentUri(value))
            } catch (_: IllegalArgumentException) { invalid.add(target) }
        }
        return AppearanceLoadResult(selection, invalid.toList())
    }

    fun save(selection: AppearanceSelection) {
        val editor = preferences.edit()
        AppearanceTarget.entries.forEach { target ->
            val uri = selection[target]
            if (uri == null) editor.remove(target.name) else editor.putString(target.name, AppearanceRules.documentUri(uri))
        }
        check(editor.commit()) { "外观设置没有保存成功，请重试" }
    }
}
