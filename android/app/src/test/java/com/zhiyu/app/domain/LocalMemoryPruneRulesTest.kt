package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LocalMemoryPruneRulesTest {
    private val now = Instant.parse("2026-10-19T00:00:00Z")

    private fun candidate(id: String, days: Long = 60, tier: String = "mid", pinned: Boolean = false,
        attachments: Boolean = false, referenced: Boolean = false, weight: Double = 1.0) =
        LocalMemoryPruneCandidate(id, "user", now.minusSeconds(days * 86400).toString(), tier, pinned,
            attachments, referenced, weight)

    @Test fun onlyOldUnprotectedMidTierRowsAreEligible() {
        assertTrue(LocalMemoryPruneRules.eligible(candidate("old"), now))
        assertFalse(LocalMemoryPruneRules.eligible(candidate("young", days = 29), now))
        assertFalse(LocalMemoryPruneRules.eligible(candidate("pinned", pinned = true), now))
    }

    @Test fun attachmentsReferencesAndHigherTiersAreNeverEligible() {
        assertFalse(LocalMemoryPruneRules.eligible(candidate("media", attachments = true), now))
        assertFalse(LocalMemoryPruneRules.eligible(candidate("proactive", referenced = true), now))
        assertFalse(LocalMemoryPruneRules.eligible(candidate("long", tier = "long"), now))
        assertFalse(LocalMemoryPruneRules.eligible(candidate("permanent", tier = "permanent"), now))
        assertFalse(LocalMemoryPruneRules.eligible(candidate("weighted", weight = 1.05), now))
    }

    @Test fun capIsTwoAndAHalfPercentOfTheOldCandidatePool() {
        assertEquals(0, LocalMemoryPruneRules.limit(39))
        assertEquals(1, LocalMemoryPruneRules.limit(40))
        assertEquals(25, LocalMemoryPruneRules.limit(1000))
    }

    @Test fun confidenceAndCapSelectOnlyTheBestOldDuplicates() {
        val matches = (0 until 150).map {
            LocalMemoryPruneMatch(candidate("old$it"), "new$it", if (it < 3) 0.999 else 0.99)
        } + LocalMemoryPruneMatch(candidate("low"), "new-low", 0.949)
        val selected = LocalMemoryPruneRules.select(150, matches, now)
        assertEquals(3, selected.size)
        assertTrue(selected.all { it.confidence >= LocalMemoryPruneRules.MIN_CONFIDENCE })
        assertEquals(listOf("old0", "old1", "old2"), selected.map { it.candidate.id })
    }
}
