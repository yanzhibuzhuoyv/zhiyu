package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.ProactiveRules
import com.zhiyu.app.domain.ProactiveWorkState
import java.util.UUID

/** Only opt-in flags, request IDs and notification IDs. API Keys and message text never enter this store. */
class ProactiveWorkStore(context: Context, name: String = "proactive_delivery") : ProactiveWorkState {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    companion object { private val lock = Any() }

    override fun enabled(conversationId: String) = preferences.getBoolean("enabled_${ProactiveRules.conversationId(conversationId)}", false)

    fun setEnabled(conversationId: String, enabled: Boolean) = synchronized(lock) {
        check(preferences.edit().putBoolean("enabled_${ProactiveRules.conversationId(conversationId)}", enabled).commit()) { "主动联系设置未保存，请重试" }
    }

    override fun requestId(conversationId: String): String = synchronized(lock) {
        val key = "pending_${ProactiveRules.conversationId(conversationId)}"
        preferences.getString(key, null)?.let { return@synchronized it }
        val id = UUID.randomUUID().toString()
        check(preferences.edit().putString(key, id).commit()) { "主动联系请求未保存" }
        id
    }

    override fun complete(conversationId: String, requestId: String) = synchronized(lock) {
        val key = "pending_${ProactiveRules.conversationId(conversationId)}"
        if (preferences.getString(key, null) == requestId) check(preferences.edit().remove(key).commit()) { "主动联系状态未保存" }
    }

    override fun claimMessage(messageId: String): Boolean = synchronized(lock) {
        val ids = preferences.getString("notified_ids", "").orEmpty().split('\n').filter(String::isNotBlank)
        if (messageId in ids) return@synchronized false
        require(messageId.isNotBlank() && messageId.none { it.code < 32 }) { "消息标识无效" }
        check(preferences.edit().putString("notified_ids", (ids.takeLast(127) + messageId).joinToString("\n")).commit()) { "通知状态未保存" }
        true
    }
}
