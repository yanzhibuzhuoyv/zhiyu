package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.ProactiveRules
import com.zhiyu.app.domain.ProactiveWorkState
import com.zhiyu.app.domain.ProactiveRunRecord
import java.time.Instant
import java.util.UUID

/** Only opt-in flags, request IDs and notification IDs. API Keys and message text never enter this store. */
class ProactiveWorkStore(context: Context, name: String = "proactive_delivery") : ProactiveWorkState {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val contactPreferences = if (name == "proactive_delivery") LocalProactivePreferencesStore(context) else null
    companion object { private val lock = Any() }

    override fun enabled(conversationId: String) = contactPreferences?.enabled(conversationId)
        ?: preferences.getBoolean("enabled_${ProactiveRules.conversationId(conversationId)}", false)

    fun setEnabled(conversationId: String, enabled: Boolean) = synchronized(lock) {
        if (contactPreferences != null) contactPreferences.setEnabled(conversationId, enabled)
        else check(preferences.edit().putBoolean("enabled_${ProactiveRules.conversationId(conversationId)}", enabled).commit()) { "主动联系设置未保存，请重试" }
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

    override fun recordRun(conversationId: String, record: ProactiveRunRecord) = synchronized(lock) {
        val prefix = "run_${ProactiveRules.conversationId(conversationId)}_"
        val editor = preferences.edit().putString(prefix + "checked", record.checkedAt.toString())
            .putString(prefix + "outcome", record.outcome)
        if (record.nextDue == null) editor.remove(prefix + "next") else editor.putString(prefix + "next", record.nextDue.toString())
        check(editor.commit()) { "主动联系检查状态未保存" }
    }

    fun lastRun(conversationId: String): ProactiveRunRecord? = synchronized(lock) {
        val prefix = "run_${ProactiveRules.conversationId(conversationId)}_"
        val checked = preferences.getString(prefix + "checked", null) ?: return@synchronized null
        val outcome = preferences.getString(prefix + "outcome", null) ?: error("主动联系检查状态不完整")
        ProactiveRunRecord(Instant.parse(checked), outcome, preferences.getString(prefix + "next", null)?.let(Instant::parse))
    }

    /** New original user activity invalidates a policy snapshot, never an unverified provider rejection. */
    internal fun recordConversationActivity(conversationId: String, messageId: String, createdAt: Instant,
        now: Instant): Boolean = synchronized(lock) {
        ProactiveRules.conversationId(conversationId)
        require(messageId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "聊天原话标识无效" }
        if (!enabled(conversationId) || createdAt.isAfter(now.plusSeconds(120))) return@synchronized false
        val activityKey = "activity_${conversationId}_id"
        if (preferences.getString(activityKey, null) == messageId) return@synchronized false
        val record = lastRun(conversationId)
        val recheck = record != null && record.outcome !in setOf("needs_connection", "retry") &&
            !createdAt.isBefore(record.checkedAt) && record.nextDue?.isAfter(now) != false
        val edit = preferences.edit().putString(activityKey, messageId)
        if (recheck) edit.putString("run_${conversationId}_next", now.toString())
        check(edit.commit()) { "主动联系交流状态未保存" }
        recheck
    }

    /** Called only for a newly authorized false-to-true transition, never ordinary recovery. */
    internal fun requestFreshCheck(conversationId: String, now: Instant) = synchronized(lock) {
        if (enabled(conversationId)) lastRun(conversationId)?.let { record ->
            recordRun(conversationId, record.copy(nextDue = now))
        }
    }

    /** A verified connection invalidates only a provider-rejection deferral, never a contact interval. */
    fun releaseConnectionBackoff(conversationId: String, now: Instant): Boolean = synchronized(lock) {
        if (!enabled(conversationId)) return@synchronized false
        val record = lastRun(conversationId) ?: return@synchronized false
        if (record.outcome != "needs_connection") return@synchronized false
        if (record.checkedAt.isAfter(now) || record.nextDue?.isAfter(now) != true) return@synchronized false
        // Compare and update under the same lock as Worker recordRun; a newly completed
        // contact must not be overwritten by an older connection-failure snapshot.
        recordRun(conversationId, record.copy(nextDue = now))
        true
    }
}
