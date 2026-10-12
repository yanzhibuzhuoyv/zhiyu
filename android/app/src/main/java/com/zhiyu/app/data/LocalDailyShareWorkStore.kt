package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.*
import java.time.Instant
import java.util.UUID

/** Independent pending/run state, while opt-in and notification claims are shared. */
class LocalDailyShareWorkStore(context: Context, name: String = "daily_share_delivery",
    private val notificationState: ProactiveWorkStore = ProactiveWorkStore(context),
) : ProactiveWorkState {
    private val prefs = (context.applicationContext ?: context).getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun enabled(conversationId: String) = notificationState.enabled(conversationId)
    override fun requestId(conversationId: String): String = synchronized(lock) {
        val key = "pending_${ProactiveRules.conversationId(conversationId)}"
        prefs.getString(key, null)?.let { previous ->
            require(UUID.fromString(previous).toString() == previous)
            return@synchronized previous
        }
        val request = UUID.randomUUID().toString()
        check(prefs.edit().putString(key, request).commit()) { "日常分享请求未保存" }
        request
    }
    override fun complete(conversationId: String, requestId: String): Unit = synchronized(lock) {
        require(UUID.fromString(requestId).toString() == requestId)
        val key = "pending_${ProactiveRules.conversationId(conversationId)}"
        if (prefs.getString(key, null) == requestId) check(prefs.edit().remove(key).commit()) { "日常分享请求完成状态未保存" }
    }
    override fun claimMessage(messageId: String) = notificationState.claimMessage(messageId)
    override fun recordRun(conversationId: String, record: ProactiveRunRecord): Unit = synchronized(lock) {
        val prefix = "run_${ProactiveRules.conversationId(conversationId)}_"
        val editor = prefs.edit().putString(prefix + "checked", record.checkedAt.toString()).putString(prefix + "outcome", record.outcome)
        if (record.nextDue == null) editor.remove(prefix + "next") else editor.putString(prefix + "next", record.nextDue.toString())
        check(editor.commit()) { "日常分享检查状态未保存" }
    }
    fun lastRun(conversationId: String): ProactiveRunRecord? = synchronized(lock) {
        val prefix = "run_${ProactiveRules.conversationId(conversationId)}_"
        val checked = prefs.getString(prefix + "checked", null) ?: return@synchronized null
        val outcome = prefs.getString(prefix + "outcome", null) ?: error("日常分享检查状态不完整")
        ProactiveRunRecord(Instant.parse(checked), outcome, prefs.getString(prefix + "next", null)?.let(Instant::parse))
    }
    companion object { private val lock = Any() }
}
