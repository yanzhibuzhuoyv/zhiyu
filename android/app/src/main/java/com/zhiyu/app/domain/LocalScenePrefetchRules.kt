package com.zhiyu.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class LocalScenePrefetchTarget(val scene: LocalDailyScene, val index: Int, val slot: LocalDailySlot, val nextDay: Boolean,
    val selfie: LocalSelfieTicket? = null, val reshoot: LocalSceneReshoot? = null)
data class LocalScenePrefetchResources(val cpuLoad: Double?, val thermalSafe: Boolean?, val memorySafe: Boolean?)
data class LocalScenePrefetchDecision(val allowed: Boolean, val reason: String)

/** Three persisted plans, with tomorrow's whole stock first and at least a rolling 36-hour horizon. */
object LocalScenePrefetchRules {
    private val zone = ZoneId.of(LocalDailyRules.TIMEZONE)
    fun days(now: Instant): List<String> = (0L..2L).map { now.atZone(zone).toLocalDate().plusDays(it).toString() }

    fun targets(scenes: List<LocalDailyScene>, now: Instant): List<LocalScenePrefetchTarget> {
        val expected = days(now)
        require(scenes.map { it.dayId }.sorted() == expected) { "预备轨迹需要当天及后两天" }
        require(scenes.map { it.visualVersion }.distinct().size == 1) { "预备轨迹外观版本不一致" }
        val until = now.plusSeconds(36 * 3600)
        return scenes.flatMap { scene ->
            require(scene.timezone == LocalDailyRules.TIMEZONE && scene.fictional) { "轨迹归属无效" }
            LocalDailyRules.current(scene.slots, now) // Validates slot times and metadata; does not relabel future activity.
            val current = if (scene.dayId == expected.first()) scene.slots.indexOf(LocalDailyRules.current(scene.slots, now)) else 0
            scene.slots.mapIndexedNotNull { index, slot ->
                val instant = LocalDate.parse(scene.dayId).atTime(java.time.LocalTime.parse(slot.time)).atZone(zone).toInstant()
                val included = scene.dayId == expected[1] || scene.dayId == expected[0] && index >= current ||
                    scene.dayId == expected[2] && !instant.isAfter(until)
                if (included) LocalScenePrefetchTarget(scene, index, slot, scene.dayId == expected[1]) else null
            }
        }.sortedWith(compareBy<LocalScenePrefetchTarget> { if (it.nextDay) 0 else if (it.scene.dayId == expected[0]) 1 else 2 }
            .thenBy { it.scene.dayId }.thenBy { it.slot.time })
    }

    fun decision(resources: LocalScenePrefetchResources, nextDayMissing: Boolean): LocalScenePrefetchDecision {
        require(resources.cpuLoad == null || resources.cpuLoad.isFinite() && resources.cpuLoad in 0.0..1.0) { "CPU 使用率无效" }
        if (resources.thermalSafe != true) return LocalScenePrefetchDecision(false, "thermal_unknown_or_high")
        if (resources.memorySafe != true) return LocalScenePrefetchDecision(false, "memory_unknown_or_low")
        if (nextDayMissing) return LocalScenePrefetchDecision(true, "next_day_priority")
        if (resources.cpuLoad == null) return LocalScenePrefetchDecision(false, "cpu_unknown")
        return if (resources.cpuLoad <= .25) LocalScenePrefetchDecision(true, "idle") else LocalScenePrefetchDecision(false, "cpu_busy")
    }
}
