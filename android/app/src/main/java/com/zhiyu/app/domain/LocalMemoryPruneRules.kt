package com.zhiyu.app.domain

import java.time.Duration
import java.time.Instant

data class LocalMemoryPruneCandidate(
    val id: String,
    val role: String,
    val createdAt: String,
    val tier: String,
    val pinned: Boolean,
    val hasAttachments: Boolean,
    val referenced: Boolean,
    val weight: Double,
    val sequence: Long = 0,
) {
    val age: Duration get() = Duration.between(Instant.parse(createdAt), Instant.now())
}

data class LocalMemoryPruneMatch(val candidate: LocalMemoryPruneCandidate, val newerId: String, val confidence: Double)

/**
 * The service and database must prove full original equality before using this quantity policy.
 * Semantic similarity alone is never permission to delete an original.
 */
object LocalMemoryPruneRules {
    const val MIN_CONFIDENCE = 0.95
    const val MAX_FRACTION = 0.025
    const val MIN_AGE_DAYS = 30L
    const val PROTECTED_RECENT_MESSAGES = 100
    fun eligible(candidate: LocalMemoryPruneCandidate, now: Instant): Boolean {
        val created = Instant.parse(candidate.createdAt)
        if (created.isAfter(now)) return false
        if (Duration.between(created, now).toDays() < MIN_AGE_DAYS) return false
        return candidate.role in setOf("user", "assistant") && candidate.tier == "mid" &&
            !candidate.pinned && !candidate.hasAttachments && !candidate.referenced && candidate.weight <= 1.0
    }

    fun limit(oldCandidates: Int): Int {
        require(oldCandidates >= 0)
        return (oldCandidates * MAX_FRACTION).toInt()
    }

    fun select(oldCandidates: Int, matches: List<LocalMemoryPruneMatch>, now: Instant): List<LocalMemoryPruneMatch> {
        require(oldCandidates >= 0 && limit(oldCandidates) <= oldCandidates)
        val cap = limit(oldCandidates)
        if (cap == 0) return emptyList()
        return matches.asSequence()
            .filter { it.confidence >= MIN_CONFIDENCE && eligible(it.candidate, now) }
            .sortedWith(compareByDescending<LocalMemoryPruneMatch> { it.confidence }
                .thenBy { it.candidate.createdAt })
            .distinctBy { it.candidate.id }
            .take(cap)
            .toList()
    }
}
