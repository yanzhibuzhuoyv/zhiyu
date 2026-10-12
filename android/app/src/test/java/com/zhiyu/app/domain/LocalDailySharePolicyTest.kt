package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID

class LocalDailySharePolicyTest {
    private val day = "2026-10-16"
    private val zone = ZoneId.of(LocalDailyRules.TIMEZONE)
    private val chat = "a".repeat(32)
    private val slots = listOf("00:00", "08:00", "10:00", "12:00", "14:00", "16:00", "18:00", "21:00")
        .mapIndexed { i, time -> LocalDailySlot(time, if (i == 0) "休息" else "生活事项$i", listOf(if (i == 0) "night" else "object")) }
    private val scene = LocalDailyScene(day, "c".repeat(32), 3, slots, slots.first())
    private fun at(time: String): Instant = LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun sampleChat(index: Int) = index.toString(16).padStart(32, '0')
    private fun sevenChat(): String = (0 until 4096).map(::sampleChat)
        .first { LocalDailySharePolicy.quota(it, "anxious", day) == 7 }

    @Test fun oneChatOneDayHasTheSameQuotaAcrossRestartAndAppearanceChanges() {
        val first = LocalDailySharePolicy.quota(chat, "playful", day)
        repeat(32) { assertEquals(first, LocalDailySharePolicy.quota(chat, "playful", day)) }
        val plan = LocalDailySharePolicy.plan(chat, "playful", scene)
        val changed = LocalDailySharePolicy.plan(chat, "playful", scene.copy(visualVersion = 9, current = slots.last()))
        assertEquals(plan.quota, changed.quota)
        assertEquals(plan.events.map { it.requestId }, changed.events.map { it.requestId })
        assertEquals(plan.events.map { it.due }, changed.events.map { it.due })
    }

    @Test fun quotasAlwaysStayBetweenZeroAndSevenAndBothEndpointsOccur() {
        val draws = (0 until 2048).flatMap { i -> listOf("steady", "distant", "gentle", "warm_v1", "warm_v2", "playful", "anxious", "custom")
            .map { style -> LocalDailySharePolicy.quota(sampleChat(i), style, day) } }
        assertTrue(draws.all { it in 0..7 })
        assertTrue(draws.contains(0))
        assertTrue(draws.contains(7))
    }

    @Test fun fixedPersonalitiesProduceDifferentSharingFrequencyWithoutUniformCaps() {
        fun draws(style: String) = (0 until 2048).map { LocalDailySharePolicy.quota(sampleChat(it), style, day) }
        val distant = draws("distant"); val steady = draws("steady"); val warm = draws("warm_v1")
        val playful = draws("playful"); val anxious = draws("anxious")
        assertTrue(distant.count { it <= 1 } > distant.size * 0.70)
        assertTrue(steady.count { it <= 1 } > steady.size * 0.60)
        assertTrue(warm.average() > steady.average() + 1.0)
        assertTrue(playful.average() > warm.average())
        assertTrue(anxious.average() > playful.average())
        assertTrue(anxious.count { it >= 5 } > anxious.size * 0.50)
    }

    @Test fun anotherDayOrAnotherChatCanHaveADifferentStableQuota() {
        assertTrue((0 until 64).map { LocalDailySharePolicy.quota(sampleChat(it), "playful", day) }.distinct().size > 3)
        assertTrue((0 until 64).map { LocalDailySharePolicy.quota(chat, "anxious", LocalDate.parse(day).plusDays(it.toLong()).toString()) }
            .distinct().size > 3)
    }

    @Test fun unknownButValidCustomStyleUsesABoundedDefaultAndInvalidIdentifiersAreRejected() {
        assertTrue(LocalDailySharePolicy.quota(chat, "my_custom_v2", day) in 0..7)
        listOf(Triple("../other", "steady", day), Triple(chat, "prompt\nchange", day), Triple(chat, "steady", "2026-02-30"),
            Triple(chat, "steady", "2026-1-2")).forEach { (id, style, date) ->
            assertThrows(IllegalArgumentException::class.java) { LocalDailySharePolicy.quota(id, style, date) }
        }
    }

    @Test fun aSevenShareDayContainsSevenFreshChronologicalActivitiesWithoutMidnightRest() {
        val plan = LocalDailySharePolicy.plan(sevenChat(), "anxious", scene)
        assertEquals(day, plan.dayId)
        assertEquals(7, plan.quota)
        assertEquals(7, plan.events.size)
        assertEquals(7, plan.events.map { it.requestId }.distinct().size)
        assertEquals(7, plan.events.map { it.slot.time }.distinct().size)
        assertEquals(7, plan.events.map { it.slot.activity }.distinct().size)
        assertTrue(plan.events.none { it.slot.time == "00:00" || it.slot.activity == "休息" })
        assertTrue(plan.events.zipWithNext().all { (a, b) -> a.due < b.due })
        assertTrue(plan.events.all { UUID.fromString(it.requestId).toString() == it.requestId })
    }

    @Test fun legacyFourActivitiesAreNotRewrittenOrPaddedToMeetASevenQuota() {
        val legacy = scene.copy(slots = slots.take(5))
        val plan = LocalDailySharePolicy.plan(sevenChat(), "anxious", legacy)
        assertEquals(7, plan.quota)
        assertEquals(4, plan.events.size)
        assertEquals(legacy.slots.drop(1), plan.events.map { it.slot })
        assertEquals(slots.take(5), legacy.slots)
    }

    @Test fun repeatedActivitiesAndExplicitSleepSlotsDoNotBecomeDuplicateShares() {
        val duplicate = scene.copy(slots = slots.mapIndexed { i, slot -> when (i) {
            2 -> slot.copy(activity = slots[1].activity)
            3 -> slot.copy(activity = "午休", tags = listOf("sleep"))
            4 -> slot.copy(activity = "休息", tags = listOf("rest"))
            else -> slot
        } })
        val events = LocalDailySharePolicy.plan(sevenChat(), "anxious", duplicate).events
        assertEquals(4, events.size)
        assertEquals(events.size, events.map { it.slot.activity }.distinct().size)
        assertTrue(events.none { it.slot.tags.any { tag -> tag in setOf("sleep", "rest") } })
    }

    @Test fun aRestOnlyDayHasNoInventedActivityEvenWithAHighQuota() {
        val plan = LocalDailySharePolicy.plan(sevenChat(), "anxious", scene.copy(slots = slots.take(1)))
        assertEquals(7, plan.quota)
        assertTrue(plan.events.isEmpty())
    }

    @Test fun eventsCarryTheExactCurrentTrajectoryRevisionAndAppearanceVersion() {
        val id = sevenChat()
        val first = LocalDailySharePolicy.plan(id, "anxious", scene)
        val updated = scene.copy(visualVersion = 10, slots = slots.mapIndexed { i, slot ->
            if (i == 1) slot.copy(activity = "新的早餐安排", tags = listOf("food")) else slot })
        val changed = LocalDailySharePolicy.plan(id, "anxious", updated)
        assertTrue(first.events.all { it.revision == LocalDailyRules.revision(scene) && it.sceneId == scene.sceneId && it.visualVersion == 3 })
        assertTrue(changed.events.all { it.revision == LocalDailyRules.revision(updated) && it.visualVersion == 10 })
        assertNotEquals(first.events.first().revision, changed.events.first().revision)
        assertEquals(first.events.map { it.requestId }, changed.events.map { it.requestId })
        assertEquals(first.events.map { it.due }, changed.events.map { it.due })
        assertEquals(updated.slots[1], changed.events.first().slot)
    }

    @Test fun sharingHappensAfterItsActivityWithAStableShortJitterAndExpiresQuickly() {
        val plan = LocalDailySharePolicy.plan(sevenChat(), "anxious", scene)
        assertTrue(plan.events.map { Duration.between(at(it.slot.time), it.due).toMinutes() }.all { it in 3..20 })
        plan.events.forEach { event ->
            val index = slots.indexOf(event.slot)
            val boundary = if (index < slots.lastIndex) at(slots[index + 1].time) else LocalDailyRules.nextMidnight(at(event.slot.time))
            assertTrue(event.due.isBefore(boundary))
            assertTrue(event.expires > event.due)
            assertTrue(!event.expires.isAfter(boundary))
            assertTrue(Duration.between(event.due, event.expires) <= Duration.ofMinutes(45))
        }
        assertTrue(plan.events.map { it.due }.distinct().size > 1)
    }

    @Test fun shortAndEndOfDaySlotsRemainInsideTheActualNextSlotOrMidnight() {
        val close = scene.copy(slots = listOf(slots.first(), slots[1].copy(time = "23:58"), slots[2].copy(time = "23:59")))
        val events = LocalDailySharePolicy.plan(sevenChat(), "anxious", close).events
        assertEquals(2, events.size)
        assertTrue(events[0].due > at("23:58") && events[0].due < at("23:59"))
        assertTrue(events[0].expires <= at("23:59"))
        assertTrue(events[1].due > at("23:59") && events[1].expires <= LocalDailyRules.nextMidnight(at("23:59")))
    }

    @Test fun readyUsesAHalfOpenWindowAndNeverCatchesUpOldPhotos() {
        val event = LocalDailySharePolicy.plan(sevenChat(), "anxious", scene).events.first()
        assertFalse(LocalDailySharePolicy.ready(event, event.due.minusSeconds(1)))
        assertTrue(LocalDailySharePolicy.ready(event, event.due))
        assertTrue(LocalDailySharePolicy.ready(event, event.expires.minusSeconds(1)))
        assertFalse(LocalDailySharePolicy.ready(event, event.expires))
        assertFalse(LocalDailySharePolicy.ready(event, event.expires.plusSeconds(86400)))
        assertFalse(LocalDailySharePolicy.ready(event, at("00:00")))
    }

    @Test fun nextDueSkipsFinishedAndExpiredEventsInsteadOfReplayingThemInABurst() {
        val plan = LocalDailySharePolicy.plan(sevenChat(), "anxious", scene)
        val first = plan.events[0]; val second = plan.events[1]
        assertEquals(first.due, LocalDailySharePolicy.nextDue(plan, at("00:00"), emptySet()))
        assertEquals(first.due, LocalDailySharePolicy.nextDue(plan, first.due, emptySet()))
        assertEquals(second.due, LocalDailySharePolicy.nextDue(plan, first.due, setOf(first.requestId)))
        assertEquals(second.due, LocalDailySharePolicy.nextDue(plan, first.expires, emptySet()))
    }

    @Test fun finishedOrZeroQuotaPlansWaitForMidnightAndAnOldPlanRequestsRefreshNow() {
        val midnight = LocalDailyRules.nextMidnight(at("12:00"))
        val plan = LocalDailySharePolicy.plan(sevenChat(), "anxious", scene)
        assertEquals(midnight, LocalDailySharePolicy.nextDue(plan, at("12:00"), plan.events.map { it.requestId }.toSet()))
        assertEquals(midnight, LocalDailySharePolicy.nextDue(LocalDailySharePlan(day, 0, emptyList()), at("12:00"), emptySet()))
        val after = midnight.plusSeconds(20)
        assertEquals(after, LocalDailySharePolicy.nextDue(plan, after, emptySet()))
    }

    @Test fun deviceTimezoneAndCurrentClockDoNotRedrawTheDeclaredDayPlan() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val first = LocalDailySharePolicy.plan(sevenChat(), "anxious", scene)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(first, LocalDailySharePolicy.plan(sevenChat(), "anxious", scene.copy(current = slots.last())))
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun malformedOrNonFictionalTrajectoriesAreRejectedBeforeSharing() {
        val bad = listOf(scene.copy(fictional = false), scene.copy(visualVersion = -1), scene.copy(dayId = "2026-02-30"),
            scene.copy(slots = slots.reversed()), scene.copy(slots = listOf(slots.first(), slots[1].copy(time = "25:00"))),
            scene.copy(slots = listOf(slots.first(), slots[1].copy(activity = "\n"))), scene.copy(timezone = "invalid-zone"))
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { LocalDailySharePolicy.plan(chat, "steady", it) } }
    }
}
