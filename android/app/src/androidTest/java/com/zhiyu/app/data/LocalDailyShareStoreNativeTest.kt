package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Isolated derived preferences; no main chat/RAG/Key/model is touched. */
class LocalDailyShareStoreNativeTest {
    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "share_state_test_${UUID.randomUUID()}"
        val workName = "share_work_test_${UUID.randomUUID()}"
        val contactName = "share_contact_test_${UUID.randomUUID()}"
        val chat = UUID.randomUUID().toString().replace("-", "")
        fun state() = LocalDailyShareStore(context, name)
        fun contact() = ProactiveWorkStore(context, contactName)
        fun work() = LocalDailyShareWorkStore(context, workName, contact())
        fun plan(day: String = "2026-10-13", count: Int = 1): LocalDailySharePlan {
            val base = LocalDate.parse(day).atStartOfDay(java.time.ZoneId.of(LocalDailyRules.TIMEZONE)).toInstant()
            return LocalDailySharePlan(day, count, (0 until count).map { index ->
                val hour = 9 + index
                val slot = LocalDailySlot("%02d:00".format(hour), "合成活动$index", listOf("object"))
                LocalDailyShareEvent(UUID.nameUUIDFromBytes("$chat:$day:$hour".toByteArray()).toString(),
                    day, "1".repeat(32), "2".repeat(64), 1, slot,
                    base.plusSeconds(hour * 3600L + 600), base.plusSeconds(hour * 3600L + 3300))
            })
        }
        override fun close() {
            listOf(name, workName, contactName).forEach(context::deleteSharedPreferences)
        }
    }

    @Test fun firstPlanIsPersistentAndCannotRerollOrChangeVisualOnTheSameDay() {
        Fixture().use { f ->
            val first = f.plan(count = 2)
            assertEquals(first, f.state().savePlan(f.chat, first))
            val replacement = f.plan(count = 1).let { it.copy(events = it.events.map { e -> e.copy(visualVersion = 4) }) }
            assertEquals(first, f.state().savePlan(f.chat, replacement))
            assertEquals(first, f.state().plan(f.chat, first.dayId))
            assertTrue(f.state().finished(f.chat, first.dayId).isEmpty())
        }
    }

    @Test fun zeroQuotaSurvivesRecreationWithoutManufacturingEvents() {
        Fixture().use { f ->
            val zero = f.plan(count = 0)
            f.state().savePlan(f.chat, zero)
            assertEquals(zero, f.state().plan(f.chat, zero.dayId))
            assertEquals(zero, f.state().savePlan(f.chat, f.plan(count = 7)))
            assertTrue(f.state().usedSha(f.chat).isEmpty())
        }
    }

    @Test fun committedAndExpiredEventsRemainFinishedAndImageDigestsAreDeduplicated() {
        Fixture().use { f ->
            val plan = f.plan(count = 2)
            f.state().savePlan(f.chat, plan)
            f.state().finish(f.chat, plan.events.first(), "a".repeat(64))
            f.state().finish(f.chat, plan.events.first(), "a".repeat(64))
            f.state().finish(f.chat, plan.events.last())
            assertEquals(plan.events.map { it.requestId }.toSet(), f.state().finished(f.chat, plan.dayId))
            assertEquals(setOf("a".repeat(64)), f.state().usedSha(f.chat))
            assertEquals(plan.events.first(), f.state().event(f.chat, plan.events.first().requestId))
        }
    }

    @Test fun pendingRequestAliasSurvivesFinishAndMidnightUntilHistoryRetentionEnds() {
        Fixture().use { f ->
            val plan = f.plan()
            val request = UUID.randomUUID().toString()
            f.state().savePlan(f.chat, plan)
            f.state().rememberRequest(f.chat, request, plan.events.single())
            f.state().finish(f.chat, plan.events.single(), "b".repeat(64))
            f.state().prune(f.chat, "2026-10-14")
            assertEquals(plan.events.single(), f.state().aliasEvent(f.chat, request))
            assertEquals(setOf("b".repeat(64)), f.state().usedSha(f.chat))
        }
    }

    @Test fun pruningKeepsTodayAndThreeOldDaysWithoutDeletingOtherChats() {
        Fixture().use { f ->
            val another = "3".repeat(32)
            (0..4).forEach { day -> f.state().savePlan(f.chat, f.plan("2026-10-${10 + day}")) }
            f.state().savePlan(another, f.plan("2026-10-10"))
            f.state().prune(f.chat, "2026-10-14")
            assertNull(f.state().plan(f.chat, "2026-10-10"))
            (11..14).forEach { assertNotNull(f.state().plan(f.chat, "2026-10-$it")) }
            assertNotNull(f.state().plan(another, "2026-10-10"))
        }
    }

    @Test fun unknownEventBadDigestAndRepointedAliasAreRejected() {
        Fixture().use { f ->
            val plan = f.plan(count = 2)
            f.state().savePlan(f.chat, plan)
            val request = UUID.randomUUID().toString()
            f.state().rememberRequest(f.chat, request, plan.events.first())
            try { f.state().rememberRequest(f.chat, request, plan.events.last()); fail("An in-flight event was replaced") }
            catch (_: IllegalArgumentException) { }
            try { f.state().finish(f.chat, plan.events.first(), "bad"); fail("Malformed digest accepted") }
            catch (_: IllegalArgumentException) { }
            val unknown = plan.events.first().copy(requestId = UUID.randomUUID().toString())
            try { f.state().finish(f.chat, unknown); fail("Unregistered event accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(f.state().finished(f.chat, plan.dayId).isEmpty())
            assertEquals(plan.events.first(), f.state().aliasEvent(f.chat, request))
        }
    }

    @Test fun unfinishedEventMayRefreshOnlyVisualMetadataWithoutChangingItsQuotaOrIdentity() {
        Fixture().use { f ->
            val plan = f.plan()
            val original = plan.events.single()
            f.state().savePlan(f.chat, plan)
            val updated = original.copy(visualVersion = 3, revision = "c".repeat(64))
            assertEquals(updated, f.state().refresh(f.chat, updated))
            assertEquals(plan.copy(events = listOf(updated)), f.state().plan(f.chat, plan.dayId))
            try { f.state().refresh(f.chat, updated.copy(due = updated.due.plusSeconds(60))); fail("Scheduled event was moved") }
            catch (_: IllegalArgumentException) { }
            try { f.state().refresh(f.chat, updated.copy(slot = updated.slot.copy(activity = "different"))); fail("Activity was replaced") }
            catch (_: IllegalArgumentException) { }
            f.state().finish(f.chat, updated, "d".repeat(64))
            assertEquals(updated, f.state().refresh(f.chat, updated.copy(visualVersion = 4)))
        }
    }

    @Test fun workOptInAndNotificationClaimsAreSharedButPendingRequestsAreIndependent() {
        Fixture().use { f ->
            assertFalse(f.work().enabled(f.chat))
            f.contact().setEnabled(f.chat, true)
            assertTrue(f.work().enabled(f.chat))
            val shareRequest = f.work().requestId(f.chat)
            assertEquals(shareRequest, f.work().requestId(f.chat))
            assertNotEquals(shareRequest, f.contact().requestId(f.chat))
            f.work().complete(f.chat, UUID.randomUUID().toString())
            assertEquals(shareRequest, f.work().requestId(f.chat))
            f.work().complete(f.chat, shareRequest)
            assertNotEquals(shareRequest, f.work().requestId(f.chat))
            assertTrue(f.work().claimMessage("share-synthetic-message"))
            assertFalse(f.contact().claimMessage("share-synthetic-message"))
            assertTrue(f.contact().claimMessage("ordinary-synthetic-message"))
            assertFalse(f.work().claimMessage("ordinary-synthetic-message"))
        }
    }

    @Test fun independentWorkRunPersistsAndDoesNotReplaceOrdinaryRun() {
        Fixture().use { f ->
            val now = Instant.parse("2026-10-13T02:00:00Z")
            val ordinary = ProactiveRunRecord(now, "sent", now.plusSeconds(3600))
            val share = ProactiveRunRecord(now, "await_image", now.plusSeconds(900))
            f.contact().recordRun(f.chat, ordinary)
            f.work().recordRun(f.chat, share)
            assertEquals(share, f.work().lastRun(f.chat))
            assertEquals(ordinary, f.contact().lastRun(f.chat))
        }
    }
}
