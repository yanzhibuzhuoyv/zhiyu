package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class LocalGenerationRulesTest {
    private val chat = "a".repeat(32)
    private fun job(index: Int, state: String = "success", kind: String = "oc") = LocalGenerationJob(
        index.toString().padStart(32, '0'), chat, kind, 1, "2026-10-10", "b".repeat(64), state)

    @Test fun unresolvedRequestsReserveOpportunityAndCannotAutoRetry() {
        val jobs = listOf(job(1, "unknown"), job(2, "running"))
        val quota = LocalGenerationRules.quota(jobs, true)
        assertEquals(0, quota.used)
        assertEquals(2, quota.pending)
        assertEquals(0, quota.remaining)
        assertFalse(LocalGenerationRules.canStart(jobs, job(1, "reserved")))
        assertFalse(LocalGenerationRules.canStart(jobs, job(3, "reserved")))
    }
    @Test fun onlySuccessfulOcAndUnresolvedOcCountAgainstTwoOpportunities() {
        val jobs = listOf(job(1), job(2, "failed"), job(3, "success", "scene"))
        val quota = LocalGenerationRules.quota(jobs, true)
        assertEquals(1, quota.used)
        assertEquals(1, quota.remaining)
        assertTrue(LocalGenerationRules.canStart(jobs, job(4, "reserved")))
    }
    @Test fun replayNeverStartsAnotherChargeEvenAfterSuccessOrKnownFailure() {
        listOf("success", "failed", "unknown", "running", "reserved", "stale").forEach { state ->
            assertFalse(state, LocalGenerationRules.canStart(listOf(job(1, state)), job(1, "reserved")))
        }
    }
    @Test fun jobsFromAnotherRelationshipDoNotConsumeCurrentQuota() {
        val elsewhere = job(1).copy(chatId = "c".repeat(32))
        assertTrue(LocalGenerationRules.canStart(listOf(elsewhere), job(2, "reserved")))
    }
    @Test fun quotaCannotBeUsedWhenImageServiceHasNoCredential() {
        assertEquals(false, LocalGenerationRules.quota(emptyList(), false).configured)
        assertEquals(2, LocalGenerationRules.quota(emptyList(), false).remaining)
    }
    @Test fun malformedJobCannotCrossStorageBoundary() {
        listOf(job(1).copy(chatId = "../elsewhere"), job(1).copy(state = "maybe"),
            job(1).copy(expectedVersion = -1), job(1).copy(dayId = "2026-02-30"),
            job(1).copy(promptHash = "unsafe")).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { LocalGenerationRules.validate(value) }
        }
    }
}
