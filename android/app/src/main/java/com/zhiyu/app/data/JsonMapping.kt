package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

internal fun <T> JSONArray.mapObjects(mapper: (JSONObject) -> T): List<T> =
    (0 until length()).map { mapper(getJSONObject(it)) }
internal fun JSONObject.provider() = Provider(
    getString("id"), getString("name"), getString("default_model"),
    getJSONArray("models").let { a -> (0 until a.length()).map { a.getString(it) } },
    optString("model_hint"), optString("doc_url"),
)
internal fun JSONObject.conversation() = Conversation(
    getString("id"), getString("title"), getString("model"), getBoolean("use_rag"),
    getString("created_at"), getString("updated_at"), optJSONObject("companion_profile")?.profile(),
)
internal fun JSONObject.profile() = CompanionRules.normalize(CompanionProfile(
    getString("mode"), optString("style", "gentle"), optString("role", "partner"),
    optString("name"), optString("user_name"), optString("instructions"),
))!!
internal fun CompanionProfile.json() = JSONObject().put("mode", mode).put("style", style).put("role", role)
    .put("name", name).put("user_name", userName).put("instructions", instructions)
internal fun JSONObject.source() = Source(getString("document_id"), getString("name"), getString("text"), optDouble("score", 0.0))
internal fun JSONObject.message() = Message(
    getString("id"), getString("role"), getString("content"),
    optJSONArray("sources")?.mapObjects { it.source() }.orEmpty(), getString("created_at"),
    optJSONArray("media_attachments")?.mapObjects { it.mediaAttachment() }.orEmpty(), optString("origin", "reply"),
)
internal fun JSONObject.document() = Document(getString("id"), getString("name"), getLong("size"), getInt("chunks"), getString("created_at"))
internal fun JSONObject.auth(): AuthSession {
    val expiry = get("expires_at")
    val timestamp = if (expiry is Number) expiry.toLong() else {
        runCatching { Instant.parse(expiry.toString()).epochSecond }
            .getOrElse { OffsetDateTime.parse(expiry.toString()).toEpochSecond() }
    }
    return AuthSession(getString("token"), getString("provider_id"), getString("model"), timestamp)
}
