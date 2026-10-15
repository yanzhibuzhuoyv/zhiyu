package com.zhiyu.app.data.remotescene

import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RemoteScenePhotosTest {
    private val chat = "a".repeat(32)
    private val bytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1)
    private val profile = VisualProfile("import", "OC", "unspecified", "long", 3, "b".repeat(32), null, GenerationQuota(false, 0, 0, 2))
    private val day = LocalDailyRules.scene(chat, 3, Instant.parse("2026-10-14T00:00:00Z"))
    private val targets = day.slots.take(3).mapIndexed { i, s -> LocalScenePrefetchTarget(day, i, s, false) }
    private fun binding() = RemoteSceneBinding(chat, profile, "c".repeat(64),
        OcAssetRef(profile.referenceId!!, ChatPhotoRules.sha(bytes), 3), "synthetic-image-key", Image2Protocol.DEFAULT_MODEL,
        SavedConnection("local://phone", "deepseek", "synthetic-chat-key", "deepseek-v4-flash"),
        CompanionBinding("deepseek", "deepseek-v4-flash"), bytes)

    private inner class Fake : RemoteSceneEnvironment {
        var current = true; var imageCalls = 0; var promptCalls = 0; var publishes = 0
        var frozen = binding(); var outcome = LifePhotoReviewStatus.APPROVED
        var correction: String? = null; var failure: Exception? = null
        var reviewedSha: String? = null
        var afterPrompt: () -> Unit = {}; var afterImage: () -> Unit = {}; var beforePublish: () -> Unit = {}
        val jobs = linkedMapOf<String, StoredImageJob>(); val candidates = linkedMapOf<String, RemoteSceneCandidate>()
        val imageBytes = mutableMapOf<String, ByteArray>(); val published = mutableListOf<RemoteSceneCandidate>()
        override fun configured() = true
        override suspend fun freeze(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile, snapshot: OcIdentitySnapshot?) = frozen
        override suspend fun unchanged(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget) = current
        override fun alreadyPrepared(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile) = published.any { it.slot == target.slot.time }
        override fun job(requestId: String) = jobs[requestId]
        override fun reserve(job: LocalGenerationJob) { check(job.requestId !in jobs); jobs[job.requestId] = StoredImageJob(job, null) }
        override fun finish(requestId: String, state: String) { val old = jobs.getValue(requestId); jobs[requestId] = old.copy(job = old.job.copy(state = state)) }
        override fun candidate(requestId: String) = candidates[requestId]
        override fun saveCandidate(candidate: RemoteSceneCandidate, bytes: ByteArray) { candidates[candidate.requestId] = candidate; imageBytes[candidate.requestId] = bytes.copyOf() }
        override fun candidateBytes(candidate: RemoteSceneCandidate) = imageBytes.getValue(candidate.requestId).copyOf()
        override suspend fun prompt(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, correction: String?): String {
            promptCalls++; afterPrompt(); return correction ?: "The same OC holding a cup in a morning room."
        }
        override suspend fun generate(binding: RemoteSceneBinding, prompt: String): AgnesGeneratedImage {
            imageCalls++; failure?.let { throw it }; afterImage(); return AgnesGeneratedImage(bytes, "image/png")
        }
        override suspend fun review(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, prompt: String, image: AgnesGeneratedImage) =
            LifePhotoReview(outcome, "synthetic pixel review", reviewedSha ?: ChatPhotoRules.sha(image.bytes), correction)
        override suspend fun publish(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, candidate: RemoteSceneCandidate): Boolean {
            beforePublish(); if (!current) return false; publishes++; published.add(candidate); return true
        }
    }
    private suspend fun run(fake: Fake, all: List<LocalScenePrefetchTarget> = targets) = RemoteScenePhotos(fake).prepare(chat, all, profile, null)

    @Test fun approvedRealCandidatePublishesOnceThenYieldsWithOriginalBytesIntact() = runTest {
        val fake = Fake(); val result = run(fake)
        assertEquals(1, result.prepared); assertTrue(result.deferred); assertEquals("yield", result.reason)
        assertEquals(1, fake.imageCalls); assertEquals(1, fake.publishes)
        assertArrayEquals(bytes, fake.frozen.originalBytes)
        assertArrayEquals(bytes, fake.candidateBytes(fake.published.single()))
        assertEquals("success", fake.jobs.values.single().job.state)
    }
    @Test fun noVisionPreservesPrivateCandidateWithoutDailyPublication() = runTest {
        val fake = Fake().apply { outcome = LifePhotoReviewStatus.UNAVAILABLE }
        assertEquals("review_unavailable", run(fake, targets.take(1)).reason)
        assertEquals(1, fake.candidates.size); assertEquals(0, fake.publishes)
        run(fake, targets.take(1)); assertEquals(1, fake.imageCalls)
    }
    @Test fun unknownReservationNeverRepaysEvenAfterImageKeyAndModelChange() = runTest {
        val fake = Fake().apply { failure = ApiFailure(504, "secret provider body", "image_request_unknown") }
        assertEquals("unknown", run(fake, targets.take(1)).reason)
        fake.frozen = RemoteSceneBinding(chat, profile, fake.frozen.packageSha, fake.frozen.original,
            "another-synthetic-key", "gpt-image-2", fake.frozen.connection, fake.frozen.companion, bytes)
        fake.failure = null; run(fake, targets.take(1))
        assertEquals(1, fake.imageCalls); assertEquals(1, fake.promptCalls); assertEquals(0, fake.publishes)
    }
    @Test fun authOrBalanceStopsWholeBatchWithOneReservation() = runTest {
        listOf("image_auth_failed", "image_balance_insufficient").forEach { code ->
            val fake = Fake().apply { failure = ApiFailure(403, "synthetic", code) }
            assertEquals(code, run(fake).reason)
            assertEquals(1, fake.imageCalls); assertEquals(1, fake.jobs.size); assertEquals(0, fake.publishes)
        }
    }
    @Test fun cancelledPaidRequestBecomesUnknownAndNeverRetries() = runTest {
        val fake = Fake().apply { failure = CancellationException("cancel") }
        try { run(fake, targets.take(1)); fail() } catch (_: CancellationException) { }
        assertEquals("unknown", fake.jobs.values.single().job.state)
        fake.failure = null; run(fake, targets.take(1)); assertEquals(1, fake.imageCalls)
    }
    @Test fun changedBindingAfterMetadataBlocksImageNetworkAndMarksStale() = runTest {
        val fake = Fake(); fake.afterPrompt = { fake.current = false }
        assertEquals("stale", run(fake).reason); assertEquals(0, fake.imageCalls)
        assertEquals("stale", fake.jobs.values.single().job.state)
    }
    @Test fun changedOcAfterImagePreservesCandidateButDoesNotPublish() = runTest {
        val fake = Fake(); fake.afterImage = { fake.current = false }
        assertEquals("stale", run(fake).reason)
        assertEquals(1, fake.candidates.size); assertEquals(0, fake.publishes)
        assertEquals("stale", fake.jobs.values.single().job.state)
    }
    @Test fun finalPublishCasCannotCommitAfterTrajectoryChanges() = runTest {
        val fake = Fake(); fake.beforePublish = { fake.current = false }
        assertEquals("stale", run(fake).reason); assertEquals(0, fake.publishes)
    }
    @Test fun atMostOneCorrectionUsesNextWorkerAndThenBecomesTerminal() = runTest {
        val fake = Fake().apply { outcome = LifePhotoReviewStatus.REJECTED; correction = "The same OC with exactly one visible sword in the original crop." }
        assertEquals("correction_pending", run(fake, targets.take(1)).reason); assertEquals(1, fake.imageCalls)
        assertEquals("rejected", run(fake, targets.take(1)).reason); assertEquals(2, fake.imageCalls)
        run(fake, targets.take(1)); assertEquals(2, fake.imageCalls); assertEquals(2, fake.jobs.size); assertEquals(0, fake.publishes)
    }
    @Test fun opportunityIdsBindActualTrajectoryAndOriginalButNotCredentials() {
        val a = binding(); val one = targets.first()
        val changedKey = RemoteSceneBinding(chat, profile, a.packageSha, a.original, "different", "gpt-image-2", a.connection, a.companion, bytes)
        assertEquals(RemoteSceneRules.requestId(a, one, 0), RemoteSceneRules.requestId(changedKey, one, 0))
        assertNotEquals(RemoteSceneRules.bindingSha(a), RemoteSceneRules.bindingSha(changedKey))
        assertNotEquals(RemoteSceneRules.requestId(a, one, 0), RemoteSceneRules.requestId(a, one.copy(scene = day.copy(slots = day.slots.mapIndexed { i, s -> if (i == 1) s.copy(activity = "different") else s })), 0))
        assertNotEquals(RemoteSceneRules.requestId(a, one, 0), RemoteSceneRules.requestId(a, one, 1))
    }
    @Test fun approvalForAnotherPngNeverPublishesActualCandidate() = runTest {
        val fake = Fake().apply { reviewedSha = "f".repeat(64) }
        assertEquals("review_unknown", run(fake).reason)
        assertEquals(0, fake.publishes); assertEquals(1, fake.candidates.size)
    }
}
