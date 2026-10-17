package com.zhiyu.app.data

import android.content.Context
import android.app.ActivityManager
import android.os.Build
import android.util.Log
import com.zhiyu.app.domain.ProactiveContactRuntime
import com.zhiyu.app.domain.ProactiveRules
import com.zhiyu.app.notifications.ProactiveNotifications

/** Reads capability metadata, never a credential or a conversation body. */
internal class ConversationRuntimeReader(private val context: Context) {
    fun proactive(id: String): ProactiveContactRuntime? {
        ProactiveRules.conversationId(id)
        return try {
            val restricted = if (Build.VERSION.SDK_INT >= 28)
                context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted else false
            ProactiveContactRuntime(LocalProactivePreferencesStore(context).read(id).enabled, ProactiveNotifications.allowed(context),
                ContactPauseStore(context).read(id).paused, restricted)
        } catch (_: Exception) {
            Log.w("ZhiYuContact", "Contact capability snapshot unavailable; no enabled state inferred")
            null
        }
    }
}
