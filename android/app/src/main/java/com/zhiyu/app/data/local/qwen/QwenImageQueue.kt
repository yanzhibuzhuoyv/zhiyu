package com.zhiyu.app.data.local.qwen

import kotlinx.coroutines.CancellationException

enum class QwenQueueOutcome { DONE, RETRY }

/** A failed image is terminal after three tries; it cannot poison the independent images behind it. */
class QwenImageQueue(
    private val jobs: QwenImageJobStore,
    private val execute: suspend (QwenImageJob) -> Unit,
    private val completed: (QwenImageJob) -> Unit,
    private val next: () -> Unit,
) {
    suspend fun run(): QwenQueueOutcome {
        val job = jobs.pending().firstOrNull() ?: return QwenQueueOutcome.DONE
        try { execute(job) }
        catch (failure: CancellationException) { throw failure }
        catch (_: QwenResourceDeferred) { return QwenQueueOutcome.RETRY }
        catch (_: QwenProcessDeferred) { return QwenQueueOutcome.RETRY }
        catch (_: Exception) {
            val stored = jobs.read(job.id) ?: job
            if (stored.state in pendingStates) {
                val count = (stored.failures + 1).coerceAtMost(3)
                jobs.write(stored.copy(failures = count, state = if (count == 3) "rejected" else stored.state))
                if (count < 3) return QwenQueueOutcome.RETRY
            }
        }
        completed(job)
        if (jobs.pending().isNotEmpty()) next()
        // A successful queue step lets appended work run even when this particular image was rejected.
        return QwenQueueOutcome.DONE
    }
    companion object { private val pendingStates = setOf("prepared", "encoded", "sampled", "decoded") }
}
