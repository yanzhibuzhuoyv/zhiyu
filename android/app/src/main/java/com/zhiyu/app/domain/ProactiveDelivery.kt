package com.zhiyu.app.domain

data class ProactivePreferences(val enabled: Boolean = false, val quietStart: Int = 22, val quietEnd: Int = 8, val timeZone: String = "Asia/Shanghai")
data class ProactiveCheck(val emitted: Boolean, val message: Message?, val nextDueAt: String?, val reason: String?)

interface ProactiveService {
    suspend fun preferences(id: String): ProactivePreferences
    suspend fun savePreferences(id: String, preferences: ProactivePreferences): ProactivePreferences
    suspend fun check(id: String, requestId: String): ProactiveCheck
}

interface ProactiveWorkState {
    fun enabled(conversationId: String): Boolean
    fun requestId(conversationId: String): String
    fun complete(conversationId: String, requestId: String)
    fun claimMessage(messageId: String): Boolean
}

enum class ProactiveWorkOutcome { COMPLETE, RETRY, STOP }

/** Backend decides timing/personality/caps. The device only decides whether it may interrupt its owner. */
class ProactiveDelivery(
    private val service: ProactiveService,
    private val state: ProactiveWorkState,
    private val permission: () -> Boolean,
    private val foreground: () -> Boolean,
    private val notify: (Message) -> Unit,
) {
    suspend fun run(conversationId: String): ProactiveWorkOutcome {
        if (!state.enabled(conversationId) || !permission() || foreground()) return ProactiveWorkOutcome.COMPLETE
        val requestId = state.requestId(conversationId)
        val result = try { service.check(conversationId, requestId) } catch (failure: ApiFailure) {
            if (failure.status == 0 || failure.status == 408 || failure.status == 429 || failure.status >= 500) return ProactiveWorkOutcome.RETRY
            state.complete(conversationId, requestId)
            return ProactiveWorkOutcome.STOP
        }
        if (result.reason in setOf("processing", "pending", "in_progress")) return ProactiveWorkOutcome.RETRY
        val message = result.message
        if (result.emitted && message != null && state.enabled(conversationId) && permission() && !foreground() && state.claimMessage(message.id)) {
            try { notify(message) } catch (_: SecurityException) {
                // Permission can be revoked between checking and posting; the message stays in the same chat.
            }
        }
        state.complete(conversationId, requestId)
        return ProactiveWorkOutcome.COMPLETE
    }
}

object ProactiveRules {
    fun conversationId(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "聊天标识无效" }
        return value
    }

    fun notificationPreview(content: String): String {
        val text = content.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return "想和你说说话。"
        if (text.codePointCount(0, text.length) <= 120) return text
        return text.substring(0, text.offsetByCodePoints(0, 120)) + "…"
    }
}
