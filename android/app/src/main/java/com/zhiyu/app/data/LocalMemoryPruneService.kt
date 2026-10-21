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
 * full original equality against a newer same-role source, then tier/pin weight, then a hard
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
        val matches = mutableListOf<LocalMemoryPruneMatch>()
        var scanned = 0
        val cursor = archive.pruneCursor(owner, id)
        val batch = old.filter { it.sequence > cursor }.ifEmpty { old }.take(MAX_SCAN)
        batch.forEach { candidate ->
            scanned++
            if (!LocalMemoryPruneRules.eligible(candidate, instant)) return@forEach
            val newer = archive.exactNewerOriginal(owner, id, candidate.id) ?: return@forEach
            matches += LocalMemoryPruneMatch(candidate, newer, 1.0)
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
            android.util.Log.w("ZhiYuMemory", "Original retention transaction failed; originals retained")
            0
        }
        batch.lastOrNull()?.let { archive.advancePruneCursor(owner, id, it.sequence) }
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
