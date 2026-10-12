package com.zhiyu.app.domain

import java.time.Instant

data class LocalDailyShareEvent(val requestId: String, val dayId: String, val sceneId: String,
    val revision: String, val visualVersion: Int, val slot: LocalDailySlot, val due: Instant, val expires: Instant)
data class LocalDailySharePlan(val dayId: String, val quota: Int, val events: List<LocalDailyShareEvent>)

/** Compile-time API checkpoint; implementation follows the observed RED run. */
@Suppress("UNUSED_PARAMETER")
object LocalDailySharePolicy {
    fun quota(chat: String, style: String, day: String): Int = error("daily sharing policy not implemented")
    fun plan(chat: String, style: String, scene: LocalDailyScene): LocalDailySharePlan = error("daily sharing plan not implemented")
    fun ready(event: LocalDailyShareEvent, now: Instant): Boolean = error("daily sharing window not implemented")
    fun nextDue(plan: LocalDailySharePlan, now: Instant, finished: Set<String>): Instant = error("daily sharing schedule not implemented")
}
