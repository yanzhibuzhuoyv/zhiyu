package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LocalScenePrefetchRulesTest {
    private val now = Instant.parse("2026-10-12T06:00:00Z") // Beijing 14:00.
    private fun scenes() = LocalScenePrefetchRules.days(now).map { day -> LocalDailyRules.scene("a".repeat(32), 2,
        LocalDate.parse(day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()) }

    @Test fun nextDayIsCompleteAndFirstThenTheRestOfTheRollingThirtySixHours() {
        val scenes = scenes()
        val targets = LocalScenePrefetchRules.targets(scenes, now)
        assertEquals(scenes[1].slots.size, targets.takeWhile { it.nextDay }.size)
        assertEquals(scenes[1].slots, targets.filter { it.nextDay }.map { it.slot })
        assertTrue(targets.any { it.scene.dayId == "2026-10-14" && it.slot.time == "00:00" })
        assertTrue(targets.filter { it.scene.dayId == "2026-10-14" }.all { it.slot.time <= "02:00" })
        val today = targets.filter { it.scene.dayId == "2026-10-12" }
        assertEquals(LocalDailyRules.current(scenes[0].slots, now), today.first().slot)
    }

    @Test fun earlyMorningStillPreparesAllTomorrowEvenBeyondTheMinimumHorizon() {
        val early = Instant.parse("2026-10-11T17:00:00Z") // Beijing 01:00.
        val plans = LocalScenePrefetchRules.days(early).map { day -> LocalDailyRules.scene("a".repeat(32), 0,
            LocalDate.parse(day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()) }
        assertEquals(plans[1].slots, LocalScenePrefetchRules.targets(plans, early).filter { it.nextDay }.map { it.slot })
    }

    @Test fun unknownCpuIsDeferredUnlessTomorrowReallyHasMissingStock() {
        val unknown = LocalScenePrefetchResources(null, true, true)
        assertEquals("cpu_unknown", LocalScenePrefetchRules.decision(unknown, false).reason)
        assertTrue(LocalScenePrefetchRules.decision(unknown, true).allowed)
        assertFalse(LocalScenePrefetchRules.decision(LocalScenePrefetchResources(.8, true, true), false).allowed)
        assertTrue(LocalScenePrefetchRules.decision(LocalScenePrefetchResources(.2, true, true), false).allowed)
    }

    @Test fun tomorrowUrgencyCanNeverBypassUnknownOrUnsafeThermalAndMemory() {
        listOf(LocalScenePrefetchResources(null, null, true), LocalScenePrefetchResources(.0, false, true),
            LocalScenePrefetchResources(.0, true, null), LocalScenePrefetchResources(.0, true, false)).forEach {
            assertFalse(LocalScenePrefetchRules.decision(it, true).allowed)
        }
    }

    @Test fun duplicatedOrMixedAppearancePlansCannotEnterTheFutureQueue() {
        val scenes = scenes()
        assertThrows(IllegalArgumentException::class.java) { LocalScenePrefetchRules.targets(listOf(scenes[0], scenes[0], scenes[2]), now) }
        assertThrows(IllegalArgumentException::class.java) { LocalScenePrefetchRules.targets(scenes.mapIndexed { i, s -> s.copy(visualVersion = i) }, now) }
    }
}
