package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class LocalProactiveService(context: Context, private val store: CredentialStore,
    private val gateway: LocalProactiveGateway = RepositoryProactiveGateway(LocalChatRepository(context, store,
        careContext = NativeCareContext(context)::prompt)),
    preferencesName: String = "local_proactive", private val contactPause:ContactPauseStore=ContactPauseStore(context),
    private val now: () -> Instant = Instant::now,
) : ProactiveService {
    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val sharedPreferences = if (preferencesName == "local_proactive") LocalProactivePreferencesStore(context) else null
    override suspend fun preferences(id: String): ProactivePreferences = withContext(Dispatchers.IO) {
        ProactiveRules.conversationId(id)
        sharedPreferences?.let { return@withContext it.read(id) }
        val data = prefs.getString(id, null)?.let(::JSONObject) ?: return@withContext ProactivePreferences()
        ProactivePreferences(data.getBoolean("enabled"), data.getInt("start"), data.getInt("end"), data.getString("zone"))
    }
    override suspend fun savePreferences(id: String, preferences: ProactivePreferences): ProactivePreferences = withContext(Dispatchers.IO) {
        ProactiveRules.conversationId(id)
        LocalContactPolicy.decide(now(), preferences, "gentle", LocalContactStats())
        check(gateway.conversations().any { it.id == id }) { "这段聊天不存在" }
        sharedPreferences?.let { it.save(id, preferences); return@withContext preferences }
        val value = JSONObject().put("enabled", preferences.enabled).put("start", preferences.quietStart)
            .put("end", preferences.quietEnd).put("zone", preferences.timeZone)
        check(prefs.edit().putString(id, value.toString()).commit()) { "主动联系设置保存失败" }
        preferences
    }
    override suspend fun check(id: String, requestId: String): ProactiveCheck = serial.withLock {
        ProactiveRules.conversationId(id)
        UUID.fromString(requestId)
        val preferences = preferences(id)
        val instant = now()
        if (!preferences.enabled) return@withLock ProactiveCheck(false, null, instant.plusSeconds(86400).toString(), "disabled")
        val contact=withContext(Dispatchers.IO){contactPause.read(id)}
        ContactDeliveryGate.reason(contactPause,id,contact)?.let {return@withLock ContactDeliveryGate.blocked(it,instant)}
        val binding = withContext(Dispatchers.IO) { store.readBinding() } ?: throw ApiFailure(401, "请先选定恋人")
        val style = binding.profile?.style ?: "gentle"
        val stats = contactStats(gateway.history(id), instant, preferences.timeZone)
        gateway.result(id, requestId)?.let { return@withLock completed(id, it, instant, preferences, style, stats,contact) }
        eventAlias(id, requestId)?.let { eventRequest ->
            gateway.result(id, eventRequest)?.let { return@withLock completed(id, it, instant, preferences, style, stats,contact) }
        }
        val decision = LocalContactPolicy.decide(instant, preferences, style, stats)
        val night = LocalNightEventPolicy.plan(id, style, instant, preferences.timeZone)
        if (!decision.eligible) return@withLock ProactiveCheck(false, null,
            LocalNightEventPolicy.nextDue(night, instant, decision.nextDue).toString(), decision.reason)
        val event = night?.takeIf { LocalNightEventPolicy.ready(it, instant) && gateway.result(id, it.requestId) == null }
        if (event != null) rememberEventAlias(id, requestId, event.requestId)
        val message = try {gateway.send(id, event?.requestId ?: requestId, event,contact.revision)}
            catch(_:ContactDeliveryBlockedException){return@withLock ContactDeliveryGate.blocked("contact_state_changed",instant)}
        completed(id, message, instant, preferences, style,
            stats.copy(todayCount = stats.todayCount + 1, unanswered = stats.unanswered + 1, lastContact = Instant.parse(message.createdAt)),contact)
    }
    private fun eventAlias(id: String, requestId: String): String? {
        val aliases = JSONArray(prefs.getString("event_requests_$id", "[]"))
        require(aliases.length() <= 64) { "夜间事件请求状态无效" }
        return (0 until aliases.length()).firstNotNullOfOrNull { index ->
            val row = aliases.getJSONObject(index)
            if (row.getString("request") == requestId) row.getString("event").also { UUID.fromString(it) } else null
        }
    }
    private suspend fun rememberEventAlias(id: String, requestId: String, eventRequestId: String) = withContext(Dispatchers.IO) {
        val previous = JSONArray(prefs.getString("event_requests_$id", "[]"))
        require(previous.length() <= 64) { "夜间事件请求状态无效" }
        val retained = (0 until previous.length()).map { previous.getJSONObject(it) }
            .filter { it.getString("request") != requestId }.takeLast(63)
        val updated = JSONArray(retained + JSONObject().put("request", requestId).put("event", eventRequestId))
        check(prefs.edit().putString("event_requests_$id", updated.toString()).commit()) { "夜间事件请求未保存" }
    }
    private fun contactStats(rows: List<Message>, instant: Instant, timeZone: String): LocalContactStats {
        val zone = ZoneId.of(timeZone)
        val sorted = rows.sortedBy { Instant.parse(it.createdAt) }
        val today = instant.atZone(zone).toLocalDate()
        val lastUser = sorted.lastOrNull { it.role == "user" }?.createdAt?.let(Instant::parse)
        val contacts = sorted.filter { it.origin == "proactive" }
        return LocalContactStats(contacts.count { Instant.parse(it.createdAt).atZone(zone).toLocalDate() == today },
            contacts.count { lastUser == null || Instant.parse(it.createdAt).isAfter(lastUser) },
            sorted.lastOrNull { it.origin !in setOf("proactive", "daily_share") }?.createdAt?.let(Instant::parse),
            contacts.lastOrNull()?.createdAt?.let(Instant::parse))
    }
    private fun completed(id: String, message: Message, instant: Instant, preferences: ProactivePreferences,
        style: String, stats: LocalContactStats,contact:ContactPauseState): ProactiveCheck {
        ContactDeliveryGate.reason(contactPause,id,contact,message)?.let {return ContactDeliveryGate.blocked(it,instant)}
        val decision = LocalContactPolicy.decide(instant, preferences, style, stats)
        val night = LocalNightEventPolicy.plan(id, style, instant, preferences.timeZone)
        val due = LocalNightEventPolicy.nextDue(night, instant, decision.nextDue)
        return ProactiveCheck(true, message, due.toString(), "sent")
    }
    companion object { private val serial = Mutex() }
}
