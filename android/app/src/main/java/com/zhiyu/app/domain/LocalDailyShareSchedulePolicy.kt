package com.zhiyu.app.domain

import java.time.Instant
import java.time.Duration

/** Daily photos keep their own due time instead of changing the ordinary contact quota. */
object LocalDailyShareSchedulePolicy {
    fun nextCheck(now: Instant, record: ProactiveRunRecord?): Instant =
        (record?.nextDue ?: now.plusSeconds(30)).coerceIn(now.plusSeconds(30), now.plusSeconds(86400))
    fun uniqueDueName(chat: String): String = "zhiyu_daily_share_due_${ProactiveRules.conversationId(chat)}"
    fun delayMillis(now: Instant, due: Instant): Long = Duration.between(now, due).toMillis().coerceAtLeast(1)
}
