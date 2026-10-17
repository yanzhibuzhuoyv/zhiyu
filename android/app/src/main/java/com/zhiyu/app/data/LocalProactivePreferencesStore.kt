package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.ProactivePreferences
import com.zhiyu.app.domain.ProactiveRules
import org.json.JSONObject
import java.time.ZoneId

/** One local opt-in record shared by the service and background workers; no Key or message text. */
class LocalProactivePreferencesStore(context: Context) {
    private val app = context.applicationContext ?: context
    private val canonical = app.getSharedPreferences("proactive_delivery", Context.MODE_PRIVATE)
    private val legacy = app.getSharedPreferences("local_proactive", Context.MODE_PRIVATE)

    fun read(id: String): ProactivePreferences = synchronized(lock) {
        ProactiveRules.conversationId(id)
        val enabled = enabledLocked(id)
        val raw = canonical.getString("settings_$id", null) ?: legacy.getString(id, null)
        val settings = raw?.let(::JSONObject)
        ProactivePreferences(enabled, settings?.getInt("start") ?: 22,
            settings?.getInt("end") ?: 8, settings?.getString("zone") ?: "Asia/Shanghai")
            .also(::validate)
    }

    fun enabled(id: String): Boolean = synchronized(lock) {
        ProactiveRules.conversationId(id)
        enabledLocked(id)
    }

    fun hasExplicitChoice(id: String): Boolean = synchronized(lock) {
        ProactiveRules.conversationId(id)
        canonical.contains("enabled_$id") || legacy.contains(id)
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        ProactiveRules.conversationId(id)
        check(canonical.edit().putBoolean("enabled_$id", enabled).putInt("optin_schema_$id", 1)
            .remove("optin_issue_$id").commit()) { "主动联系设置未保存，请重试" }
    }

    /** A service grant and its metadata commit atomically; there is no second worker opt-in write. */
    fun save(id: String, preferences: ProactivePreferences) = synchronized(lock) {
        ProactiveRules.conversationId(id)
        validate(preferences)
        val metadata = JSONObject().put("start", preferences.quietStart)
            .put("end", preferences.quietEnd).put("zone", preferences.timeZone).toString()
        check(canonical.edit().putString("settings_$id", metadata).putBoolean("enabled_$id", preferences.enabled)
            .putInt("optin_schema_$id", 1).remove("optin_issue_$id").commit()) { "主动联系设置保存失败" }
    }

    /** A migration conflict is a settings issue, never a claim about the user's conversation. */
    fun migrationIssue(id: String): String? = synchronized(lock) {
        ProactiveRules.conversationId(id)
        enabledLocked(id)
        canonical.getString("optin_issue_$id", null)?.also { check(it == "legacy_conflict") }
    }

    private fun enabledLocked(id: String): Boolean {
        val schema = canonical.getInt("optin_schema_$id", 0)
        require(schema in 0..1) { "主动联系设置版本无效，原数据已保留" }
        if (schema == 1) {
            check(canonical.contains("enabled_$id")) { "主动联系设置不完整，原数据已保留" }
            return canonical.getBoolean("enabled_$id", false)
        }
        val worker = if (canonical.contains("enabled_$id")) canonical.getBoolean("enabled_$id", false) else null
        val service = legacy.getString(id, null)?.let { JSONObject(it).getBoolean("enabled") }
        if (worker == null && service == null) return false
        val conflict = worker != null && service != null && worker != service
        // Do not override either explicit false in a disagreement. A fresh, user-authorized
        // grant can resolve it through save/setEnabled; a contact pause remains independent.
        val enabled = !conflict && (worker ?: service ?: false)
        val edit = canonical.edit().putBoolean("enabled_$id", enabled).putInt("optin_schema_$id", 1)
        if (conflict) edit.putString("optin_issue_$id", "legacy_conflict") else edit.remove("optin_issue_$id")
        check(edit.commit()) { "主动联系旧设置未恢复，原数据已保留" }
        return enabled
    }

    private fun validate(preferences: ProactivePreferences) {
        require(preferences.quietStart in 0..23 && preferences.quietEnd in 0..23)
        ZoneId.of(preferences.timeZone)
    }

    private companion object { val lock = Any() }
}
