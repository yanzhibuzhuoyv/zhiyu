package com.zhiyu.app.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class LocalContactStats(val todayCount: Int = 0, val unanswered: Int = 0,
    val lastActivity: Instant? = null, val lastContact: Instant? = null)
data class LocalContactDecision(val eligible: Boolean, val reason: String, val nextDue: Instant)

object LocalContactPolicy {
    fun decide(now: Instant, preferences: ProactivePreferences, style: String, stats: LocalContactStats): LocalContactDecision {
        require(stats.todayCount >= 0 && stats.unanswered >= 0)
        val zone = try { ZoneId.of(preferences.timeZone) }
        catch (_: java.time.DateTimeException) { throw IllegalArgumentException("主动联系时区无效") }
        val local = now.atZone(zone)
        val (cap, hours, unansweredLimit) = when (style) {
            "steady" -> Triple(1, 12.0, 1)
            "warm_v1", "warm_v2" -> Triple(3, 4.0, 4)
            "playful" -> Triple(4, 3.0, 4)
            "anxious" -> Triple(12, maxOf(0.5, 3.0 / (1 shl minOf(stats.unanswered, 3))), Int.MAX_VALUE)
            "distant" -> Triple(1, 24.0, 1)
            else -> Triple(2, 6.0, 3)
        }
        val spacing = Duration.ofSeconds((hours * 3600).toLong())
        // Legacy quiet-hour fields remain readable, but no longer impose a time window.
        val nextDay = local.toLocalDate().plusDays(1).atStartOfDay(local.zone).toInstant()
        fun result(reason: String, due: Instant) = LocalContactDecision(reason == "ready", reason, due)
        if (!preferences.enabled) return result("disabled", now.plus(Duration.ofDays(1)))
        if (stats.unanswered >= unansweredLimit) return result("awaiting_reply", now.plus(spacing))
        if (style == "distant" && stats.lastActivity == null) return result("reserved_style", now.plus(spacing))
        val interactionDue = stats.lastActivity?.plus(Duration.ofMinutes(45))
        if (interactionDue != null && now.isBefore(interactionDue)) return result("recent_chat", interactionDue)
        if (stats.todayCount >= cap) return result("daily_limit", nextDay)
        val contactDue = stats.lastContact?.plus(spacing)
        if (contactDue != null && now.isBefore(contactDue)) return result("spacing", contactDue)
        return result("ready", now)
    }
}
