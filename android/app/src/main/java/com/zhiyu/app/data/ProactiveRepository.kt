package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun interface ProactiveTransport {
    suspend fun request(url: String, path: String, method: String, token: String?, body: JSONObject?): String
}

class ProactiveRepository(store: CredentialStore, private val allowHttp: Boolean,
    private val transport: ProactiveTransport = ProactiveTransport { url, path, method, token, body -> HttpApi().request(url, path, method, token, body) },
) : ProactiveService {
    private val coordinator = SessionCoordinator(store, object : AuthService {
        override suspend fun authenticate(connection: SavedConnection) = JSONObject(transport.request(
            ConnectionRules.normalizeUrl(connection.serverUrl, allowHttp), "/api/auth/login", "POST", null,
            JSONObject().put("provider_id", connection.providerId).put("api_key", connection.apiKey).put("model", connection.model),
        )).auth()
    })

    override suspend fun preferences(id: String) = JSONObject(request(path(id, "proactive-preferences"))).preferences()

    override suspend fun savePreferences(id: String, preferences: ProactivePreferences): ProactivePreferences {
        require(preferences.quietStart in 0..23 && preferences.quietEnd in 0..23)
        java.time.ZoneId.of(preferences.timeZone)
        val json = JSONObject().put("enabled", preferences.enabled).put("quiet_start", preferences.quietStart)
            .put("quiet_end", preferences.quietEnd).put("time_zone", preferences.timeZone)
        return JSONObject(request(path(id, "proactive-preferences"), "PUT", json)).preferences()
    }

    override suspend fun check(id: String, requestId: String): ProactiveCheck {
        java.util.UUID.fromString(requestId)
        val json = JSONObject(request(path(id, "proactive/check"), "POST", JSONObject().put("request_id", requestId)))
        val emitted = json.getBoolean("emitted")
        val message = json.optJSONObject("message")?.message()
        require(!emitted || message?.role == "assistant") { "主动消息响应无效" }
        return ProactiveCheck(emitted, message, json.optString("next_due_at").takeUnless { it.isBlank() || it == "null" },
            json.optString("reason").takeUnless { it.isBlank() || it == "null" })
    }

    private fun path(id: String, route: String) = "/api/conversations/${ProactiveRules.conversationId(id)}/$route"
    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null) = withContext(Dispatchers.IO) {
        coordinator.authorized { token ->
            val saved = coordinator.saved() ?: throw ApiFailure(401, "请先连接恋人")
            transport.request(ConnectionRules.normalizeUrl(saved.serverUrl, allowHttp), path, method, token, body)
        }
    }

    private fun JSONObject.preferences() = ProactivePreferences(
        getBoolean("enabled"), getInt("quiet_start"), getInt("quiet_end"), getString("time_zone"),
    )
}
