package com.zhiyu.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LocalMemoryLifecycleTest {
    private val now = Instant.parse("2026-10-10T00:00:00Z")
    private fun observation(id: String, day: Int, kind: String = "wish", text: String = "我想吃苹果") =
        LocalMemoryAnnotation(id, "owner", "chat", "message-$id", "user", "2026-10-%02dT12:00:00Z".format(day),
            LocalMemoryRecognitionRules.sha(text), 0, 0, text.length, LocalMemoryRecognitionRules.sha(text), kind, "user", "affirm",
            if (kind == "profile") "user:birthday" else "wish:apple", .95, false, false, null, null, null)

    @Test fun repeatedWishOnThreeIndependentDaysBecomesLongButNeverAPermanentHobby() {
        val originals = listOf(observation("a", 1), observation("b", 4), observation("c", 8))
        val decisions = LocalMemoryLifecycle.evaluate(originals, now)
        assertEquals(setOf("long"), decisions.values.map { it.tier }.toSet())
        assertEquals(setOf("wish"), originals.map { it.kind }.toSet())
        assertEquals(3, decisions.getValue("c").independentEvidence)
    }

    @Test fun retriesDuplicateSpansAndRepeatedSameDayCannotManufactureIndependentDays() {
        val same = observation("a", 1)
        val otherSpan = same.copy(id = "b", start = 1, messageId = same.messageId)
        val sameDay = observation("c", 1)
        val decisions = LocalMemoryLifecycle.evaluate(listOf(same, same, otherSpan, sameDay), now)
        assertEquals("mid", decisions.getValue("a").tier)
        assertEquals(1, decisions.getValue("a").independentEvidence)
    }

    @Test fun threeDistinctDaysWithinLessThanSixElapsedDaysRemainMid() {
        val decisions = LocalMemoryLifecycle.evaluate(listOf(observation("a", 1), observation("b", 2), observation("c", 4)), now)
        assertTrue(decisions.values.all { it.tier == "mid" })
    }

    @Test fun ownersChatsTopicsAndKindsCannotPromoteEachOther() {
        val values = listOf(observation("a", 1), observation("b", 4).copy(owner = "other"),
            observation("c", 8).copy(conversationId = "other"), observation("d", 9).copy(kind = "preference"))
        assertTrue(LocalMemoryLifecycle.evaluate(values, now).values.all { it.tier == "mid" })
    }

    @Test fun currentUserProfileAndExplicitCorrectionArePermanentlyProtectedWithoutErasingOldSources() {
        val old = observation("old", 1, "profile", "我的生日九月十八").copy(validity = "superseded")
        val current = observation("new", 2, "profile", "更正，我生日九月十九").copy(correction = true)
        val decisions = LocalMemoryLifecycle.evaluate(listOf(old, current), now)
        assertEquals("mid", decisions.getValue("old").tier)
        assertEquals("permanent", decisions.getValue("new").tier)
        assertTrue(decisions.getValue("new").pinned)
        assertEquals("我的生日九月十八".length, old.end)
    }

    @Test fun pendingAndOverduePromisesStayPinnedEvenWhenTheirDateHasPassed() {
        val pending = observation("a", 1, "promise", "明天陪你去吃火锅").copy(promiseState = "pending")
        val ai = pending.copy(id = "ai", messageId = "ai", role = "assistant", subject = "assistant")
        val decisions = LocalMemoryLifecycle.evaluate(listOf(pending, ai), now)
        assertTrue(decisions.values.all { it.pinned })
        assertTrue(decisions.values.all { it.tier == "mid" })
    }

    @Test fun correctingATemporaryWishOrPromiseDoesNotMakeItPermanent() {
        val wish = observation("wish", 1, "wish", "更正，我现在想吃梨").copy(correction = true)
        val promise = observation("promise", 1, "promise", "改成下周陪你散步").copy(correction = true, promiseState = "pending")
        val ai = promise.copy(id = "ai", messageId = "ai", role = "assistant", subject = "assistant")
        val decisions = LocalMemoryLifecycle.evaluate(listOf(wish, promise, ai), now)
        assertTrue(decisions.values.all { it.tier == "mid" })
        assertTrue(decisions.getValue("wish").pinned)
        assertTrue(decisions.getValue("promise").pinned)
        assertEquals("current_correction", decisions.getValue("wish").reason)
    }

    @Test fun resolvedUncertainAndFutureObservationsDoNotCreateExtraProtectionOrEvidence() {
        val fulfilled = observation("a", 1, "promise").copy(promiseState = "fulfilled")
        val uncertain = observation("b", 4).copy(validity = "uncertain")
        val future = observation("c", 11, "profile")
        val decisions = LocalMemoryLifecycle.evaluate(listOf(fulfilled, uncertain, future), now)
        assertTrue(decisions.values.none { it.pinned || it.tier == "permanent" })
    }

    @Test fun irrelevantPermanentMemoryCannotGainRankingPriority() {
        val permanent = LocalMemoryLifecycle.evaluate(listOf(observation("a", 1, "profile")), now).getValue("a")
        assertEquals(1.0, LocalMemoryLifecycle.factor(permanent, 0.0), .00001)
        assertEquals(1.1, LocalMemoryLifecycle.factor(permanent, .8), .00001)
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryLifecycle.factor(permanent, Double.NaN) }
    }

    @Test fun automaticColdIsOffAndProtectedSourcesCannotBeCooled() {
        val normal = LocalMemoryLifecycleDecision("normal", "mid", false, 0, "unclassified", emptyList())
        val pinned = normal.copy(pinned = true)
        assertFalse(LocalMemoryLifecycle.canCool(listOf(normal), false, null))
        assertFalse(LocalMemoryLifecycle.canCool(listOf(pinned), false, null, automaticEnabled = true))
        assertFalse(LocalMemoryLifecycle.canCool(listOf(normal), true, null, automaticEnabled = true))
        assertFalse(LocalMemoryLifecycle.canCool(listOf(normal), false, "permanent", automaticEnabled = true))
    }
}
