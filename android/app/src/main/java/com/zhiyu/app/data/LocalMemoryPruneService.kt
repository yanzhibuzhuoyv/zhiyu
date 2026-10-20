package com.zhiyu.app.data

import com.zhiyu.app.domain.LocalMemoryPruneMatch
import com.zhiyu.app.domain.LocalMemoryPruneRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

data class LocalMemoryPruneReport(
    val oldCandidates: Int,
    val scanned: Int,
    val confident: Int,
    val requested: Int,
    val deleted: Int,
    val reason: String,
)

/**
 * Conservative old-memory retention:
 * semantic cosine >= 0.95 against a newer same-role source, then tier/pin weight, then a hard
 * 2.5% cap over the old candidate pool. This is the only production path allowed to delete
 * original rows; it never deletes attachments, proactive sources, pinned/long/permanent rows,
 * or the newest 100 messages.
 */
class LocalMemoryPruneService(private val archive: LocalChatDatabase,
    private val encoder: LocalSemanticEncoder, private val now: () -> Instant = Instant::now) {
    suspend fun run(owner: String, id: String): LocalMemoryPruneReport = withContext(Dispatchers.IO) {
        val instant = now()
        val cutoff = instant.minus(Duration.ofDays(LocalMemoryPruneRules.MIN_AGE_DAYS))
        val old = archive.oldMemoryCandidates(owner, id, cutoff)
        val cap = LocalMemoryPruneRules.limit(old.size)
        if (cap == 0) return@withContext LocalMemoryPruneReport(old.size, 0, 0, 0, 0, "below_cap")
        if (!encoder.isAvailable()) return@withContext LocalMemoryPruneReport(old.size, 0, 0, 0, 0, "encoder_unavailable")
        val store = LocalSemanticStore(archive)
        val matches = mutableListOf<LocalMemoryPruneMatch>()
        var scanned = 0
        old.take(MAX_SCAN).forEach { candidate ->
            val vector = store.firstVector(owner, id, candidate.id, encoder.modelIdentity) ?: return@forEach
            scanned++
            val hits = (store.search(owner, id, encoder.modelIdentity, encoder.dimensions,
                listOf(vector), setOf(candidate.id), minimumScore = LocalMemoryPruneRules.MIN_CONFIDENCE) +
                store.search(owner, id, encoder.modelIdentity, encoder.dimensions,
                    listOf(vector), setOf(candidate.id), state = "cold",
                    minimumScore = LocalMemoryPruneRules.MIN_CONFIDENCE))
                .filter {
                    it.role == candidate.role && it.createdAt > candidate.createdAt &&
                        it.relevanceFactor >= candidate.weight
                }
            val best = hits.maxByOrNull { it.score } ?: return@forEach
            matches += LocalMemoryPruneMatch(candidate, best.messageId, best.score)
        }
        val requested = LocalMemoryPruneRules.select(old.size, matches, instant)
        // Never delete a source that is itself the newer representative of another selected row.
        val requestedIds = requested.map { it.candidate.id }.toSet()
        val selected = requested.filterNot { it.newerId in requestedIds }
        val deleted = try {
            archive.deleteOldMemories(owner, id, selected.map { it.candidate.id }, cutoff)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0
        }
        LocalMemoryPruneReport(old.size, scanned, matches.size, selected.size, deleted,
            if (deleted == selected.size) "pruned" else "partial")
    }

    suspend fun runIfDue(owner: String, id: String, intervalMillis: Long = INTERVAL_MILLIS): LocalMemoryPruneReport? {
        if (!archive.memoryPruneDue(owner, id, now(), intervalMillis)) return null
        return run(owner, id).also { archive.markMemoryPrune(owner, id, now()) }
    }

    companion object {
        const val MAX_SCAN = 400
        const val INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}
