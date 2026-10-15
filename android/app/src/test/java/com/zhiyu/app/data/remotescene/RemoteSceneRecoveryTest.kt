package com.zhiyu.app.data.remotescene

import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Recovery tests use synthetic bytes and credentials; no network or paid provider. */
class RemoteSceneRecoveryTest {
    private val f = RecoveryFixture()
    private val target get() = f.targets.first()
    private suspend fun prepare(all: List<LocalScenePrefetchTarget> = f.targets) =
        RemoteScenePhotos(f).prepare(f.chat, all, f.profile, null)
    private suspend fun recheck(id: String) = RemoteScenePhotos(f).recheck(f.chat, target, f.profile, null, id)
    private suspend fun retry(id: String) = RemoteScenePhotos(f).retryFailed(f.chat, target, f.profile, null, id)

    @Test fun oldUnknownDoesNotRepayAndLaterSlotStillGeneratesOnce() = runTest {
        val old = f.seed(state = "unknown", withPixels = false)
        f.changeKeys()
        assertEquals(1, prepare().prepared)
        assertEquals(1, f.imageCalls)
        assertEquals("unknown", f.jobs.getValue(old.requestId).job.state)
        assertEquals(f.targets[1].slot.time, f.published.single().slot)
    }

    @Test fun failedAndStaleSlotsDoNotBlockAThirdSlot() = runTest {
        f.seed(state = "failed", withPixels = false)
        f.seed(f.targets[1], state = "stale", withPixels = false)
        assertEquals(1, prepare().prepared)
        assertEquals(1, f.imageCalls)
        assertEquals(f.targets[2].slot.time, f.published.single().slot)
    }

    @Test fun unavailableUnknownRemovedAndInvalidCorrectionAreSkippedIndividually() = runTest {
        for (kind in listOf("unavailable", "review_unknown", "removed", "no_correction")) {
            val local = RecoveryFixture()
            val old = local.seed(status = when (kind) {
                "review_unknown" -> LifePhotoReviewStatus.UNKNOWN
                "no_correction" -> LifePhotoReviewStatus.REJECTED
                else -> LifePhotoReviewStatus.UNAVAILABLE
            })
            if (kind == "removed") local.candidates[old.requestId] = old.copy(pixelsRemoved = true)
            val result = RemoteScenePhotos(local).prepare(local.chat, local.targets, local.profile, null)
            assertEquals(kind, 1, result.prepared); assertEquals(kind, 1, local.imageCalls)
            assertEquals(kind, local.targets[1].slot.time, local.published.single().slot)
        }
    }

    @Test fun changedBindingCandidateIsNotAutoRecheckedBeforeNextSlot() = runTest {
        val old = f.seed(status = LifePhotoReviewStatus.APPROVED)
        f.changeKeys()
        assertEquals(1, prepare().prepared)
        assertEquals(1, f.imageCalls); assertEquals(1, f.reviewCalls)
        assertFalse(f.published.any { it.requestId == old.requestId })
    }

    @Test fun newlyObservedAuthAndBalanceFailureStopRemainingSlots() = runTest {
        for (code in listOf("image_auth_failed", "image_balance_insufficient")) {
            val local = RecoveryFixture().apply { failure = ApiFailure(403, "synthetic", code) }
            val result = RemoteScenePhotos(local).prepare(local.chat, local.targets, local.profile, null)
            assertEquals(code, result.reason); assertEquals(1, local.imageCalls)
            assertEquals(1, local.jobs.size)
        }
    }

    @Test fun recheckUsesOriginalAndCandidateBytesAndPreservesGenerationBinding() = runTest {
        val old = f.seed()
        f.changeKeys()
        val result = recheck(old.requestId)
        assertEquals(1, result.prepared); assertEquals(0, f.imageCalls); assertEquals(0, f.promptCalls)
        assertEquals(1, f.reviewCalls); assertArrayEquals(f.bytes, f.lastReviewed)
        assertArrayEquals(f.bytes, f.frozen.originalBytes)
        val saved = f.candidates.getValue(old.requestId)
        assertEquals(old.bindingSha, saved.bindingSha)
        assertEquals(RemoteSceneRules.bindingSha(f.frozen), saved.reviewBindingSha)
        assertEquals(old.imageSha, saved.imageSha); assertEquals(old.originalSha, saved.originalSha)
        assertEquals("success", f.jobs.getValue(old.requestId).job.state)
    }

    @Test fun actualPixelsCanBeReviewedAfterUnknownOrStaleFinishWithoutChangingLedger() = runTest {
        for (state in listOf("unknown", "stale")) {
            val local = RecoveryFixture(); val old = local.seed(state = state)
            val result = RemoteScenePhotos(local).recheck(local.chat, local.targets.first(), local.profile, null, old.requestId)
            assertEquals(state, 1, result.prepared); assertEquals(state, 0, local.imageCalls)
            assertEquals(state, state, local.jobs.getValue(old.requestId).job.state)
        }
    }

    @Test fun alreadyApprovedUnderCurrentReviewProofAvoidsAnotherVisionCharge() = runTest {
        val old = f.seed(status = LifePhotoReviewStatus.APPROVED)
        assertEquals(1, recheck(old.requestId).prepared)
        assertEquals(0, f.reviewCalls); assertEquals(0, f.imageCalls)
    }

    @Test fun removedAndInFlightCandidatesCannotBeReviewedOrPublished() = runTest {
        for (state in listOf("reserved", "running", "removed")) {
            val local = RecoveryFixture(); val old = local.seed(state = if (state == "removed") "success" else state)
            if (state == "removed") local.candidates[old.requestId] = old.copy(pixelsRemoved = true)
            val result = RemoteScenePhotos(local).recheck(local.chat, local.targets.first(), local.profile, null, old.requestId)
            assertEquals(0, result.prepared); assertEquals(0, local.reviewCalls); assertEquals(0, local.publishes)
        }
    }

    @Test fun recheckRejectsEveryStructuralSourceMismatchBeforeNetwork() = runTest {
        val changes: List<(RemoteSceneCandidate) -> RemoteSceneCandidate> = listOf(
            { it.copy(chat = "d".repeat(32)) }, { it.copy(originalId = "d".repeat(32)) },
            { it.copy(originalSha = "d".repeat(64)) }, { it.copy(visualVersion = 4) },
            { it.copy(packageSha = "d".repeat(64)) }, { it.copy(day = "2026-10-15") },
            { it.copy(slot = "23:59") }, { it.copy(trajectory = "d".repeat(64)) },
            { it.copy(attempt = 1) })
        for (change in changes) {
            val local = RecoveryFixture(); val old = local.seed(); local.candidates[old.requestId] = change(old)
            RemoteScenePhotos(local).recheck(local.chat, local.targets.first(), local.profile, null, old.requestId)
            assertEquals(0, local.reviewCalls); assertEquals(0, local.publishes)
        }
    }

    @Test fun recheckRejectsCandidateShaMismatchAndReviewOfDifferentPixels() = runTest {
        val old = f.seed(); f.imageBytes[old.requestId] = byteArrayOf(1, 2, 3)
        assertEquals(0, recheck(old.requestId).prepared); assertEquals(0, f.reviewCalls)
        f.imageBytes[old.requestId] = f.bytes.copyOf(); f.reviewedSha = "f".repeat(64)
        assertEquals(0, recheck(old.requestId).prepared); assertEquals(0, f.publishes)
        assertEquals(LifePhotoReviewStatus.UNKNOWN, f.candidates.getValue(old.requestId).status)
    }

    @Test fun changedSnapshotsDuringRecheckPreventApprovalAndPublication() = runTest {
        val old = f.seed(); f.afterReview = { f.current = false }
        assertEquals(0, recheck(old.requestId).prepared)
        assertEquals(0, f.publishes); assertEquals(0, f.imageCalls)
        assertNotEquals(LifePhotoReviewStatus.APPROVED, f.candidates.getValue(old.requestId).status)
    }

    @Test fun cancelledReviewCannotGenerateOrLeaveAProofForCurrentApproval() = runTest {
        val old = f.seed(); f.reviewFailure = CancellationException("synthetic cancel")
        try { recheck(old.requestId); fail("must propagate cancellation") } catch (_: CancellationException) { }
        assertEquals(0, f.imageCalls); assertEquals(0, f.publishes)
        assertNotEquals(LifePhotoReviewStatus.APPROVED, f.candidates.getValue(old.requestId).status)
        assertEquals("success", f.jobs.getValue(old.requestId).job.state)
    }

    @Test fun explicitFailedRetryGeneratesStableOneChildAndPreservesSource() = runTest {
        val old = f.seed(state = "failed", withPixels = false)
        val source = f.jobs.getValue(old.requestId); f.changeKeys()
        assertEquals(1, retry(old.requestId).prepared)
        val childId = RemoteSceneRules.retryId(old.requestId)
        assertEquals(childId, f.retryFor(old.requestId)); assertEquals(old.requestId, f.retrySource(childId))
        assertEquals(source, f.jobs.getValue(old.requestId))
        assertEquals(source.job.promptHash, f.jobs.getValue(childId).job.promptHash)
        assertEquals(old.requestId, f.candidates.getValue(childId).retryOf)
        assertEquals(1, f.imageCalls); assertEquals(1, f.retryReservations)
        retry(old.requestId); assertEquals(1, f.imageCalls); assertEquals(1, f.retryReservations)
    }

    @Test fun unknownStaleSuccessInFlightAndForeignJobsCannotBeExplicitlyRetried() = runTest {
        for (state in listOf("unknown", "stale", "success", "reserved", "running")) {
            val local = RecoveryFixture(); val old = local.seed(state = state, withPixels = false)
            RemoteScenePhotos(local).retryFailed(local.chat, local.targets.first(), local.profile, null, old.requestId)
            assertEquals(state, 0, local.imageCalls); assertEquals(state, 0, local.retryReservations)
        }
        val old = f.seed(state = "failed", withPixels = false)
        f.jobs[old.requestId] = f.jobs.getValue(old.requestId).let { it.copy(job = it.job.copy(chatId = "d".repeat(32))) }
        retry(old.requestId); assertEquals(0, f.imageCalls); assertEquals(0, f.retryReservations)
    }

    @Test fun reserveRetryCasLossStopsBeforePromptOrImageAndCreatesNoExtraChild() = runTest {
        val old = f.seed(state = "failed", withPixels = false); f.loseRetryCas = true
        assertEquals(0, retry(old.requestId).prepared)
        assertEquals(0, f.promptCalls); assertEquals(0, f.imageCalls); assertEquals(1, f.jobs.size)
    }

    @Test fun cancelledRetryBecomesUnknownAndNeverPaysAgainAfterKeyChange() = runTest {
        val old = f.seed(state = "failed", withPixels = false); f.failure = CancellationException("synthetic")
        try { retry(old.requestId); fail("must propagate cancellation") } catch (_: CancellationException) { }
        val child = RemoteSceneRules.retryId(old.requestId)
        assertEquals("unknown", f.jobs.getValue(child).job.state)
        f.failure = null; f.changeKeys(); retry(old.requestId)
        assertEquals(1, f.imageCalls); assertEquals(1, f.retryReservations)
    }

    @Test fun retryOfRetryAndForgedCandidateLineageCannotPublish() = runTest {
        val old = f.seed(state = "failed", withPixels = false); f.outcome = LifePhotoReviewStatus.REJECTED
        retry(old.requestId)
        val child = f.retryFor(old.requestId)!!
        f.jobs[child] = f.jobs.getValue(child).let { it.copy(job = it.job.copy(state = "failed")) }
        retry(child); assertEquals(1, f.imageCalls); assertEquals(1, f.retryReservations)
        val saved = f.candidates.getValue(child)
        f.candidates[child] = saved.copy(retryOf = "f".repeat(32))
        assertEquals(0, recheck(child).prepared); assertEquals(0, f.publishes)
    }

    @Test fun approvedRetrySurvivingCrashCanPublishWithoutAnotherImage() = runTest {
        val old = f.seed(state = "failed", withPixels = false)
        val child = f.seedRetry(old.requestId, LifePhotoReviewStatus.APPROVED)
        assertEquals(1, prepare().prepared)
        assertEquals(child.requestId, f.published.single().requestId)
        assertEquals(0, f.imageCalls); assertEquals(0, f.reviewCalls)
    }

    @Test fun unknownAuthorizedRetryIsSkippedAndNextSlotCanGenerate() = runTest {
        val old = f.seed(state = "failed", withPixels = false)
        val child = f.seedRetry(old.requestId, state = "unknown")
        assertEquals(1, prepare().prepared); assertEquals(1, f.imageCalls)
        assertEquals("unknown", f.jobs.getValue(child.requestId).job.state)
        assertEquals(f.targets[1].slot.time, f.published.single().slot)
    }

    @Test fun automaticCorrectionStillOccursOnceAndThenLaterSlotProgresses() = runTest {
        f.outcome = LifePhotoReviewStatus.REJECTED
        f.correction = "Keep the same OC and visible body crop."
        assertEquals("correction_pending", prepare().reason)
        assertEquals("rejected", prepare().reason)
        f.outcome = LifePhotoReviewStatus.APPROVED
        assertEquals(1, prepare().prepared); assertEquals(3, f.imageCalls)
        assertEquals(f.targets[1].slot.time, f.published.single().slot)
    }
}

private class RecoveryFixture : RemoteSceneEnvironment {
    val chat = "a".repeat(32)
    val bytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1)
    val profile = VisualProfile("import", "OC", "unspecified", "long", 3, "b".repeat(32), null, GenerationQuota(false, 0, 0, 2))
    private val day = LocalDailyRules.scene(chat, 3, Instant.parse("2026-10-14T00:00:00Z"))
    val targets = day.slots.take(3).mapIndexed { i, s -> LocalScenePrefetchTarget(day, i, s, false) }
    var frozen = RemoteSceneBinding(chat, profile, "c".repeat(64), OcAssetRef(profile.referenceId!!, ChatPhotoRules.sha(bytes), 3),
        "synthetic-image-key", Image2Protocol.DEFAULT_MODEL, SavedConnection("local://phone", "deepseek", "synthetic-chat-key", "deepseek-v4-flash"),
        CompanionBinding("deepseek", "deepseek-v4-flash"), bytes)
    var current = true; var imageCalls = 0; var promptCalls = 0; var reviewCalls = 0; var publishes = 0
    var retryReservations = 0; var loseRetryCas = false; var lastReviewed: ByteArray? = null
    var outcome = LifePhotoReviewStatus.APPROVED; var correction: String? = null
    var failure: Exception? = null; var reviewFailure: Exception? = null; var reviewedSha: String? = null
    var afterReview: () -> Unit = {}
    val jobs = linkedMapOf<String, StoredImageJob>(); val candidates = linkedMapOf<String, RemoteSceneCandidate>()
    val imageBytes = mutableMapOf<String, ByteArray>(); val published = mutableListOf<RemoteSceneCandidate>()
    private val retries = mutableMapOf<String, String>()
    fun changeKeys() {
        frozen = RemoteSceneBinding(chat, profile, frozen.packageSha, frozen.original, "new-synthetic-key", "gpt-image-2",
            frozen.connection.copy(apiKey = "new-chat-key"), frozen.companion, bytes)
    }
    fun seed(target: LocalScenePrefetchTarget = targets.first(), state: String = "success",
        status: LifePhotoReviewStatus = LifePhotoReviewStatus.UNAVAILABLE, withPixels: Boolean = true): RemoteSceneCandidate {
        val id = RemoteSceneRules.requestId(frozen, target, 0)
        jobs[id] = StoredImageJob(LocalGenerationJob(id, chat, "scene", 3, target.scene.dayId,
            ChatPhotoRules.sha(LocalMemoryCodec.bytes("remote-life-metadata:$id")), state), null)
        val candidate = RemoteSceneCandidate(id, chat, RemoteSceneRules.bindingSha(frozen), ChatPhotoRules.sha(bytes), "image/png",
            "Keep the original OC in the given activity.", 0, target.scene.dayId, target.slot.time, LocalDailyRules.revision(target.scene),
            status, originalId = frozen.original.id, originalSha = frozen.original.sha256, visualVersion = 3, packageSha = frozen.packageSha)
        if (withPixels) saveCandidate(candidate, bytes)
        return candidate
    }
    fun seedRetry(source: String, status: LifePhotoReviewStatus = LifePhotoReviewStatus.UNAVAILABLE,
        state: String = "success"): RemoteSceneCandidate {
        val id = RemoteSceneRules.retryId(source); val sourceJob = jobs.getValue(source).job
        retries[source] = id; jobs[id] = StoredImageJob(sourceJob.copy(requestId = id, state = state), null)
        val sourceCandidate = seed(state = "failed", withPixels = false)
        val candidate = sourceCandidate.copy(requestId = id, retryOf = source, status = status)
        saveCandidate(candidate, bytes); return candidate
    }
    override fun configured() = true
    override suspend fun freeze(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile, snapshot: OcIdentitySnapshot?) = frozen
    override suspend fun unchanged(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget) = current
    override fun alreadyPrepared(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile) = published.any { it.slot == target.slot.time }
    override fun job(requestId: String) = jobs[requestId]
    override fun retryFor(sourceId: String) = retries[sourceId]
    override fun retrySource(retryId: String) = retries.entries.firstOrNull { it.value == retryId }?.key
    override fun reserveRetry(sourceId: String, newJob: LocalGenerationJob) {
        if (loseRetryCas || jobs[sourceId]?.job?.state != "failed" || retries.containsKey(sourceId) || retrySource(sourceId) != null)
            throw ApiFailure(409, "synthetic CAS loss", "scene_retry_reserved")
        retries[sourceId] = newJob.requestId; retryReservations++; reserve(newJob)
    }
    override fun reserve(job: LocalGenerationJob) { check(job.requestId !in jobs); jobs[job.requestId] = StoredImageJob(job, null) }
    override fun finish(requestId: String, state: String) {
        val old = jobs.getValue(requestId); jobs[requestId] = old.copy(job = old.job.copy(state = state))
    }
    override fun candidate(requestId: String) = candidates[requestId]
    override fun saveCandidate(candidate: RemoteSceneCandidate, bytes: ByteArray) {
        candidates[candidate.requestId] = candidate; imageBytes[candidate.requestId] = bytes.copyOf()
    }
    override fun candidateBytes(candidate: RemoteSceneCandidate) = imageBytes.getValue(candidate.requestId).copyOf()
    override suspend fun prompt(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, correction: String?): String {
        promptCalls++; return correction ?: "Keep the original OC in the given activity."
    }
    override suspend fun generate(binding: RemoteSceneBinding, prompt: String): AgnesGeneratedImage {
        imageCalls++; failure?.let { throw it }; return AgnesGeneratedImage(bytes, "image/png")
    }
    override suspend fun review(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, prompt: String,
        image: AgnesGeneratedImage): LifePhotoReview {
        reviewCalls++; lastReviewed = image.bytes.copyOf(); reviewFailure?.let { throw it }; afterReview()
        return LifePhotoReview(outcome, "synthetic real-pixel review", reviewedSha ?: ChatPhotoRules.sha(image.bytes), correction)
    }
    override suspend fun publish(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, candidate: RemoteSceneCandidate): Boolean {
        if (!current || candidate.pixelsRemoved || (candidate.reviewBindingSha ?: candidate.bindingSha) != RemoteSceneRules.bindingSha(binding)) return false
        publishes++; published.add(candidate); return true
    }
}
