package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class LocalDailyRulesTest {
    private val chat = "a".repeat(32)
    @Test fun beijingMidnightSelectsANewDayAndRestSlotWithoutAComputer() {
        val before = LocalDailyRules.scene(chat, 3, Instant.parse("2026-10-08T15:59:59Z"))
        val after = LocalDailyRules.scene(chat, 3, Instant.parse("2026-10-08T16:00:00Z"))
        assertEquals("2026-10-08", before.dayId)
        assertEquals("2026-10-09", after.dayId)
        assertNotEquals(before.sceneId, after.sceneId)
        assertEquals("00:00", after.current.time)
        assertTrue(after.fictional)
        assertEquals("Asia/Shanghai", after.timezone)
    }
    @Test fun restartingReconstructsTheSamePlanAndPreservesItsAppearanceVersion() {
        val now = Instant.parse("2026-10-09T04:00:00Z")
        val first = LocalDailyRules.scene(chat, 9, now)
        assertEquals(first, LocalDailyRules.scene(chat, 9, now))
        val changedAppearance = LocalDailyRules.scene(chat, 10, now)
        assertEquals(first.slots, changedAppearance.slots)
        assertEquals(first.sceneId, changedAppearance.sceneId)
        assertEquals(10, changedAppearance.visualVersion)
        assertTrue(first.slots.zipWithNext().all { (a, b) -> a.time < b.time })
        assertTrue(first.slots.all { it.tags.isNotEmpty() })
    }
    @Test fun manyDaysHaveSeveralPlansButNeverInventUserLocationsOrAppointments() {
        val start = Instant.parse("2026-10-01T08:00:00Z")
        val scenes = (0 until 90).map { LocalDailyRules.scene(chat, 0, start.plusSeconds(it * 86400L)) }
        assertTrue(scenes.map { it.slots }.distinct().size >= 40)
        assertEquals(90, scenes.map { it.sceneId }.distinct().size)
        assertTrue(scenes.all { it.fictional && it.visualVersion == 0 })
        assertThrows(IllegalArgumentException::class.java) { LocalDailyRules.scene("../other", 0, start) }
        assertThrows(IllegalArgumentException::class.java) { LocalDailyRules.scene(chat, -1, start) }
    }
    @Test fun nextMidnightIsTheDeclaredBeijingBoundaryAndAlwaysInTheFuture() {
        val before = Instant.parse("2026-10-09T15:59:59Z")
        val boundary = Instant.parse("2026-10-09T16:00:00Z")
        assertEquals(boundary, LocalDailyRules.nextMidnight(before))
        assertEquals(Instant.parse("2026-10-10T16:00:00Z"), LocalDailyRules.nextMidnight(boundary))
        assertTrue(LocalDailyRules.nextMidnight(before).isAfter(before))
    }
    @Test fun deviceTimezoneChangeDoesNotRelabelTheDeclaredPlanDay() {
        val original = TimeZone.getDefault()
        val now = Instant.parse("2026-10-09T17:00:00Z")
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val west = LocalDailyRules.scene(chat, 1, now)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(west, LocalDailyRules.scene(chat, 1, now))
            assertEquals("2026-10-10", west.dayId)
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun manuallyGoingBackToAnOldDayDoesNotMutateThatDaysDeterministicIdentity() {
        val old = Instant.parse("2026-10-09T04:00:00Z")
        val first = LocalDailyRules.scene(chat, 1, old)
        assertNotEquals(first.dayId, LocalDailyRules.scene(chat, 1, old.plusSeconds(86400)).dayId)
        assertEquals(first, LocalDailyRules.scene(chat, 1, old))
    }
    @Test fun malformedUserPlanSlotsCannotBecomeTheCurrentActivity() {
        val now = Instant.parse("2026-10-09T04:00:00Z")
        val zero = LocalDailySlot("00:00", "休息", listOf("night"))
        listOf(listOf(zero.copy(time = "24:00")), listOf(zero, zero.copy(time = "02:99")),
            listOf(zero.copy(activity = "")), listOf(zero.copy(tags = listOf("system instruction")))).forEach {
            assertThrows(IllegalArgumentException::class.java) { LocalDailyRules.current(it, now) }
        }
    }
    @Test fun imageMatchingUsesCurrentPlanTagsAndNeverAddsAStickerRequirement() {
        val slot = LocalDailySlot("18:00", "看看晚霞", listOf("landscape", "evening"))
        assertEquals(2, LocalDailyRules.mediaAffinity("image", setOf("landscape", "evening", "cloud"), slot))
        assertEquals(0, LocalDailyRules.mediaAffinity("image", setOf("morning", "food"), slot))
        assertEquals(0, LocalDailyRules.mediaAffinity("sticker", setOf("landscape", "evening"), slot))
    }

    @Test fun futurePlanIsAnArrangementRatherThanTodaysCurrentActivity() {
        val now = Instant.parse("2026-10-12T10:00:00Z") // 18:00 now.
        val future = LocalDailyRules.plannedScene(chat, 2, "2026-10-13", now)
        assertEquals("2026-10-13", future.dayId)
        assertEquals("00:00", future.current.time)
        assertNotEquals(LocalDailyRules.current(future.slots, now), future.current)
        assertEquals(future.slots, LocalDailyRules.scene(chat, 2, Instant.parse("2026-10-12T16:00:00Z")).slots)
    }

    @Test fun trajectoryRevisionIgnoresClockAdvancingButChangesWithActualActivityOrAppearance() {
        val first = LocalDailyRules.scene(chat, 2, Instant.parse("2026-10-12T01:00:00Z"))
        val later = LocalDailyRules.scene(chat, 2, Instant.parse("2026-10-12T10:00:00Z"))
        assertEquals(LocalDailyRules.revision(first), LocalDailyRules.revision(later))
        assertNotEquals(LocalDailyRules.revision(first), LocalDailyRules.revision(first.copy(visualVersion = 3)))
        assertNotEquals(LocalDailyRules.revision(first), LocalDailyRules.revision(first.copy(slots = first.slots.mapIndexed { i, slot ->
            if (i == 0) slot.copy(activity = "新的角色休息安排") else slot
        })))
    }

    @Test fun newPlansHaveSevenDistinctNonRestActivitiesForDailyPhotoSharing() {
        val start = Instant.parse("2026-10-16T00:00:00Z")
        (0 until 90).forEach { offset ->
            val scene = LocalDailyRules.scene(chat, 4, start.plusSeconds(offset * 86400L))
            assertEquals(8, scene.slots.size)
            assertEquals("00:00", scene.slots.first().time)
            val activities = scene.slots.drop(1)
            assertEquals(7, activities.map { it.activity }.distinct().size)
            assertEquals(7, activities.map { it.time }.distinct().size)
            assertTrue(activities.all { it.activity != "休息" && it.time >= "07:00" && it.time <= "22:59" })
            assertTrue(scene.slots.zipWithNext().all { (a, b) -> a.time < b.time })
        }
    }

    @Test fun oldPersistedFiveSlotTrajectoriesRemainValidAndUnchanged() {
        val slots = listOf(LocalDailySlot("00:00", "休息", listOf("night")), LocalDailySlot("08:00", "旧早餐", listOf("food")),
            LocalDailySlot("12:00", "旧午餐", listOf("food")), LocalDailySlot("18:00", "旧晚餐", listOf("food")),
            LocalDailySlot("21:00", "旧阅读", listOf("object")))
        val original = slots.toList()
        assertEquals(slots[2], LocalDailyRules.current(slots, Instant.parse("2026-10-16T04:00:00Z")))
        assertEquals(original, slots)
    }
}
