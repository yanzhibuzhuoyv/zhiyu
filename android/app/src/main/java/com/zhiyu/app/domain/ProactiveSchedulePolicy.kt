package com.zhiyu.app.domain

import java.time.Duration
import java.time.Instant

/** Scheduling metadata only; provider responses, Keys and message content do not belong here. */
data class ProactiveRunRecord(val checkedAt: Instant, val outcome: String, val nextDue: Instant?) {
    init { require(outcome in setOf("sent", "not_due", "foreground", "permission", "disabled", "retry", "needs_connection")) }
}

object ProactiveSchedulePolicy {
    fun nextCheck(now: Instant, record: ProactiveRunRecord?): Instant {
        val due = record?.nextDue ?: now.plusSeconds(15 * 60)
        return due.coerceIn(now.plusSeconds(30), now.plusSeconds(24 * 3600))
    }

    fun delayMillis(now: Instant, due: Instant): Long = Duration.between(now, due).toMillis().coerceAtLeast(1)

    fun uniqueDueName(chat: String): String = "zhiyu_companion_due_${ProactiveRules.conversationId(chat)}"

    fun parseNextDue(raw: String?): Instant? = raw?.let(Instant::parse)
}
