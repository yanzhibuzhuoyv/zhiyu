package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class LocalProactiveService(context: Context, private val store: CredentialStore,
    private val repository: LocalChatRepository = LocalChatRepository(context, store,
        careContext = NativeCareContext(context)::prompt),
) : ProactiveService {
    private val prefs = context.applicationContext.getSharedPreferences("local_proactive", Context.MODE_PRIVATE)
    override suspend fun preferences(id: String): ProactivePreferences = withContext(Dispatchers.IO) {
        ProactiveRules.conversationId(id)
        val data = prefs.getString(id, null)?.let(::JSONObject) ?: return@withContext ProactivePreferences()
        ProactivePreferences(data.getBoolean("enabled"), data.getInt("start"), data.getInt("end"), data.getString("zone"))
    }
    override suspend fun savePreferences(id: String, preferences: ProactivePreferences): ProactivePreferences = withContext(Dispatchers.IO) {
        ProactiveRules.conversationId(id)
        LocalContactPolicy.decide(Instant.now(), preferences, "gentle", LocalContactStats())
        check(repository.conversations().any { it.id == id }) { "这段聊天不存在" }
        val value = JSONObject().put("enabled", preferences.enabled).put("start", preferences.quietStart)
            .put("end", preferences.quietEnd).put("zone", preferences.timeZone)
        check(prefs.edit().putString(id, value.toString()).commit()) { "主动联系设置保存失败" }
        preferences
    }
    override suspend fun check(id: String, requestId: String): ProactiveCheck = serial.withLock {
        ProactiveRules.conversationId(id)
        UUID.fromString(requestId)
        val preferences = preferences(id)
        if (preferences.enabled) repository.proactiveResult(id, requestId)?.let {
            return@withLock ProactiveCheck(true, it, null, null)
        }
        val binding = withContext(Dispatchers.IO) { store.readBinding() } ?: throw ApiFailure(401, "请先选定恋人")
        val rows = repository.proactivePolicyHistory(id).sortedBy { it.createdAt }
        val now = Instant.now()
        val today = now.atZone(ZoneId.of(preferences.timeZone)).toLocalDate()
        val lastUser = rows.lastOrNull { it.role == "user" }
        val count = rows.count { it.origin == "proactive" && Instant.parse(it.createdAt)
            .atZone(ZoneId.of(preferences.timeZone)).toLocalDate() == today }
        val unanswered = rows.count { it.origin == "proactive" && (lastUser == null || it.createdAt > lastUser.createdAt) }
        val stats = LocalContactStats(count, unanswered,
            rows.lastOrNull { it.origin != "proactive" }?.createdAt?.let(Instant::parse),
            rows.lastOrNull { it.origin == "proactive" }?.createdAt?.let(Instant::parse))
        val decision = LocalContactPolicy.decide(now, preferences, binding.profile?.style ?: "gentle", stats)
        if (!decision.eligible) return@withLock ProactiveCheck(false, null, decision.nextDue.toString(), decision.reason)
        val message = repository.sendProactive(id, requestId)
        ProactiveCheck(true, message, null, null)
    }
    companion object { private val serial = Mutex() }
}
