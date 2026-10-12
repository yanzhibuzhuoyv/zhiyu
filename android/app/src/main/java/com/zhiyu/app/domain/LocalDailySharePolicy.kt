package com.zhiyu.app.domain

import java.time.Instant
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.security.MessageDigest
import java.util.UUID

data class LocalDailyShareEvent(val requestId: String, val dayId: String, val sceneId: String,
    val revision: String, val visualVersion: Int, val slot: LocalDailySlot, val due: Instant, val expires: Instant)
data class LocalDailySharePlan(val dayId: String, val quota: Int, val events: List<LocalDailyShareEvent>)

/** Fixed-day OC sharing, independent of general proactive contact and the device timezone. */
object LocalDailySharePolicy {
    private val zone = ZoneId.of(LocalDailyRules.TIMEZONE)
    private val defaultWeights = listOf(16, 24, 25, 17, 10, 5, 2, 1)
    private val middleWeights = listOf(9, 12, 19, 24, 20, 10, 5, 1)
    private val weights = mapOf(
        "distant" to listOf(68, 24, 6, 2, 0, 0, 0, 0),
        "steady" to listOf(45, 35, 15, 4, 1, 0, 0, 0),
        "gentle" to middleWeights, "warm_v1" to middleWeights, "warm_v2" to middleWeights,
        "playful" to listOf(4, 7, 10, 14, 19, 22, 16, 8),
        "anxious" to listOf(2, 3, 5, 9, 14, 21, 24, 22))

    fun quota(chat: String, style: String, day: String): Int {
        validate(chat, style, day)
        val frequencies = weights[style] ?: defaultWeights
        val draw = (entropy("quota", chat, day, style) % frequencies.sum()).toInt()
        var cumulative = 0
        frequencies.forEachIndexed { count, weight ->
            cumulative += weight
            if (draw < cumulative) return count
        }
        error("日常分享次数分布无效")
    }

    fun plan(chat: String, style: String, scene: LocalDailyScene): LocalDailySharePlan {
        val count = quota(chat, style, scene.dayId)
        val date = day(scene.dayId)
        validateScene(scene, date)
        val selected = scene.slots.filter { it.time != "00:00" && it.activity.trim() != "休息" &&
            it.tags.none { tag -> tag in setOf("rest", "sleep") } }
            .distinctBy { it.activity.trim() }
            .sortedBy { entropy("selection", chat, scene.dayId, style, it.time) }
            .take(count).sortedBy { it.time }
        val revision = LocalDailyRules.revision(scene)
        return LocalDailySharePlan(scene.dayId, count, selected.map { event(chat, scene, date, revision, it) })
    }

    fun ready(event: LocalDailyShareEvent, now: Instant): Boolean {
        require(event.due < event.expires) { "日常分享窗口无效" }
        return !now.isBefore(event.due) && now.isBefore(event.expires)
    }

    fun nextDue(plan: LocalDailySharePlan, now: Instant, finished: Set<String>): Instant {
        require(plan.quota in 0..7 && plan.events.size <= plan.quota) { "日常分享计划无效" }
        val midnight = day(plan.dayId).plusDays(1).atStartOfDay(zone).toInstant()
        if (!now.isBefore(midnight)) return now // Request today's plan; never replay this old day.
        val earliest = plan.events.filter { it.requestId !in finished && now.isBefore(it.expires) }
            .minOfOrNull { it.due } ?: return midnight
        return maxOf(now, earliest)
    }

    private fun event(chat: String, scene: LocalDailyScene, date: LocalDate, revision: String,
        slot: LocalDailySlot): LocalDailyShareEvent {
        val start = date.atTime(LocalTime.parse(slot.time)).atZone(zone).toInstant()
        val index = scene.slots.indexOf(slot)
        val boundary = scene.slots.getOrNull(index + 1)?.let { date.atTime(LocalTime.parse(it.time)).atZone(zone).toInstant() }
            ?: date.plusDays(1).atStartOfDay(zone).toInstant()
        val jitter = (3 + entropy("jitter", chat, scene.dayId, slot.time) % 18) * 60
        val due = start.plusSeconds(minOf(jitter, Duration.between(start, boundary).seconds / 2))
        val expires = minOf(due.plus(Duration.ofMinutes(45)), boundary)
        // This UUID seed is frozen: appearance, revisions and app restarts cannot mint a second message.
        val id = UUID.nameUUIDFromBytes("zhiyu-daily-share:$chat:${scene.dayId}:${slot.time}".toByteArray(Charsets.UTF_8)).toString()
        return LocalDailyShareEvent(id, scene.dayId, scene.sceneId, revision, scene.visualVersion, slot, due, expires)
    }

    private fun validateScene(scene: LocalDailyScene, date: LocalDate) {
        require(scene.fictional && scene.visualVersion >= 0 && scene.timezone == LocalDailyRules.TIMEZONE) { "日轨迹归属无效" }
        require(scene.sceneId.matches(Regex("[a-f0-9]{32}"))) { "日轨迹编号无效" }
        LocalDailyRules.current(scene.slots, date.atStartOfDay(zone).toInstant())
        require(scene.slots.all { it.activity.isNotBlank() }) { "日轨迹事项无效" }
    }

    private fun validate(chat: String, style: String, date: String) {
        ProactiveRules.conversationId(chat)
        require(style.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "恋人性格标识无效" }
        day(date)
    }

    private fun day(value: String): LocalDate {
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "日轨迹日期无效" }
        return try { LocalDate.parse(value).also { require(it.toString() == value) { "日轨迹日期无效" } } }
        catch (failure: DateTimeException) { throw IllegalArgumentException("日轨迹日期无效", failure) }
    }

    private fun entropy(namespace: String, vararg parts: String): Long = MessageDigest.getInstance("SHA-256")
        .digest((listOf("zhiyu-daily-share", namespace) + parts).joinToString(":").toByteArray(Charsets.UTF_8))
        .take(4).fold(0L) { accumulated, byte -> (accumulated shl 8) or (byte.toLong() and 255) }
}
