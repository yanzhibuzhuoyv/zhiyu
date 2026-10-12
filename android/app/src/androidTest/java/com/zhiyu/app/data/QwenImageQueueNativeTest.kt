package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.local.qwen.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QwenImageQueueNativeTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun job(id: Char, slot: String) = QwenImageJob(id.toString().repeat(64), "b".repeat(32), "2026-10-10", 2,
        null, null, "e".repeat(32), slot, "散步", listOf("outdoor"), 42, "prepared", "A sunny park.")
    @Test fun threeFailuresRejectOnlyOneImageAndTheFollowingImageStillRuns() = runBlocking {
        NativeV4Context(base).use { context ->
            val jobs = QwenImageJobStore(context)
            val bad = job('a', "07:30"); val good = job('c', "10:00")
            jobs.write(bad); jobs.write(good)
            val attempts = mutableListOf<String>(); var next = 0
            val queue = QwenImageQueue(jobs, execute = { selected ->
                attempts += selected.id
                if (selected.id == bad.id) error("Synthetic native interruption")
                jobs.write(selected.copy(state = "approved"))
            }, completed = {}, next = { next++ })
            assertEquals(QwenQueueOutcome.RETRY, queue.run())
            assertEquals(1, jobs.read(bad.id)!!.failures)
            assertEquals(QwenQueueOutcome.RETRY, queue.run())
            assertEquals(QwenQueueOutcome.DONE, queue.run())
            assertEquals("rejected", jobs.read(bad.id)!!.state)
            assertEquals(3, jobs.read(bad.id)!!.failures)
            assertEquals("prepared", jobs.read(good.id)!!.state)
            assertEquals(1, next)
            assertEquals(QwenQueueOutcome.DONE, queue.run())
            assertEquals("approved", jobs.read(good.id)!!.state)
            assertEquals(listOf(bad.id, bad.id, bad.id, good.id), attempts)
            assertEquals(1, next)
        }
    }
    @Test fun resourceDeferralAndCancellationNeverConsumeTheNativeFailureBudget() = runBlocking {
        NativeV4Context(base).use { context ->
            val jobs = QwenImageJobStore(context); val pending = job('a', "07:30")
            jobs.write(pending)
            var next = 0
            val deferred = QwenImageQueue(jobs, { throw QwenResourceDeferred() }, {}, { next++ })
            assertEquals(QwenQueueOutcome.RETRY, deferred.run())
            assertEquals(pending, jobs.read(pending.id))
            val quarantined = QwenImageQueue(jobs, { throw QwenProcessDeferred() }, {}, { next++ })
            assertEquals(QwenQueueOutcome.RETRY, quarantined.run())
            assertEquals(pending, jobs.read(pending.id))
            val cancelled = QwenImageQueue(jobs, { throw CancellationException("synthetic cancellation") }, {}, { next++ })
            try { cancelled.run(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            assertEquals(pending, jobs.read(pending.id))
            assertEquals(0, next)
        }
    }
    @Test fun anUncertainPaidReviewIsNotRepeatedAndDoesNotBlockIndependentImages() = runBlocking {
        NativeV4Context(base).use { context ->
            val jobs = QwenImageJobStore(context); val first = job('a', "07:30"); val second = job('c', "10:00")
            jobs.write(first); jobs.write(second)
            var next = 0; var calls = 0
            val queue = QwenImageQueue(jobs, { selected ->
                calls++
                jobs.write(selected.copy(state = "review_unknown"))
                error("Synthetic paid response uncertainty")
            }, {}, { next++ })
            assertEquals(QwenQueueOutcome.DONE, queue.run())
            assertEquals("review_unknown", jobs.read(first.id)!!.state)
            assertEquals(0, jobs.read(first.id)!!.failures)
            assertEquals(1, next)
            assertEquals(QwenQueueOutcome.DONE, queue.run())
            assertEquals(2, calls)
            assertEquals(1, next)
        }
    }
}
