package com.zhiyu.app.domain

import java.time.Instant

/** API checkpoint for independent picture-share scheduling. */
@Suppress("UNUSED_PARAMETER")
object LocalDailyShareSchedulePolicy {
    fun nextCheck(now: Instant, record: ProactiveRunRecord?): Instant = error("daily share scheduling not implemented")
    fun uniqueDueName(chat: String): String = error("daily share scheduling not implemented")
    fun delayMillis(now: Instant, due: Instant): Long = error("daily share scheduling not implemented")
}
