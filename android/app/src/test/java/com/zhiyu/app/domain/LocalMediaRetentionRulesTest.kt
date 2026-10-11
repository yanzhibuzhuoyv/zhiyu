package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LocalMediaRetentionRulesTest {
    private val today = LocalDate.parse("2026-10-10")
    private fun stock(n: Int, day: String? = "2026-10-02", sha: String = "a".repeat(64)) =
        MediaRetentionRecord(n.toString(16).padStart(32, '0'), "image", sha, day,
            "library", setOf("daily_stock"), "恋人的生活画面")

    @Test fun strictSevenDayBoundaryAndUndatedOrInvalidAssetsStay() {
        val records = listOf(stock(1), stock(2, "2026-10-03"), stock(3, null),
            stock(4, "2026-02-30"), stock(5, "2027-01-01"))
        assertEquals(listOf(records.first().id), LocalMediaRetentionRules.plan(records, emptySet(), today).removeIds)
    }
    @Test fun allSentAssetsAndNonCachePurposesStayForever() {
        val original = stock(1)
        val records = listOf(original, stock(2).copy(purpose = "portrait"), stock(3).copy(purpose = "chat"),
            stock(4).copy(tags = emptySet()), stock(5).copy(kind = "sticker"),
            stock(6).copy(sourceLabel = "本地导入"), stock(7).copy(sourceLabel = "内置素材"))
        assertTrue(LocalMediaRetentionRules.plan(records, setOf(original.id), today).removeIds.isEmpty())
    }
    @Test fun onlyKnownOldGeneratedBackgroundIsEligible() {
        val cache = stock(1).copy(purpose = "scene_background", tags = emptySet(), sourceLabel = "生成的空场景")
        val user = stock(2).copy(purpose = "scene_background", sourceLabel = "用户图片")
        assertEquals(listOf(cache.id), LocalMediaRetentionRules.plan(listOf(cache, user), emptySet(), today).removeIds)
    }
    @Test fun shaRemainsProtectedByAnySurvivingRecordAcrossKinds() {
        val old = stock(1)
        val protected = stock(2).copy(kind = "sticker", purpose = "library", sourceLabel = "内置素材")
        val plan = LocalMediaRetentionRules.plan(listOf(old, protected), emptySet(), today)
        assertEquals(listOf(old.id), plan.removeIds)
        assertTrue(plan.deleteBlobs.isEmpty())
        assertTrue(LocalMediaRetentionRules.unreferenced(setOf(MediaRetentionBlob("image", old.sha256)), listOf(protected)).isEmpty())
    }
    @Test fun boundedBatchUsesOldestFirstWithoutDependingOnEnumerationOrder() {
        val records = listOf(stock(3, "2026-10-02"), stock(2, "2026-09-10"), stock(1, "2026-09-10"))
        val plan = LocalMediaRetentionRules.plan(records, emptySet(), today, limit = 2)
        assertEquals(3, plan.eligibleRecords)
        assertEquals(listOf(stock(1).id, stock(2).id), plan.removeIds)
        assertEquals(plan, LocalMediaRetentionRules.plan(records.reversed(), emptySet(), today, limit = 2))
        assertTrue(plan.deleteBlobs.isEmpty()) // Third metadata still references the shared blob.
    }
    @Test fun malformedPathsDuplicateIdsAndUnboundedPlansFailClosed() {
        rejects { LocalMediaRetentionRules.plan(listOf(stock(1).copy(id = "../private")), emptySet(), today) }
        rejects { LocalMediaRetentionRules.plan(listOf(stock(1), stock(1)), emptySet(), today) }
        rejects { LocalMediaRetentionRules.plan(listOf(stock(1).copy(sha256 = "../private")), emptySet(), today) }
        rejects { LocalMediaRetentionRules.plan(listOf(stock(1)), setOf("../private"), today) }
        rejects { LocalMediaRetentionRules.plan(listOf(stock(1)), emptySet(), today, limit = 0) }
        rejects { LocalMediaRetentionRules.plan(listOf(stock(1)), emptySet(), today, limit = 257) }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }
}
