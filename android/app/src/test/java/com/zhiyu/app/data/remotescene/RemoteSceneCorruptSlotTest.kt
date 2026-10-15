package com.zhiyu.app.data.remotescene

import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.test.runTest
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RemoteSceneCorruptSlotTest {
    @Test fun unreadableOldCandidateMetadataDoesNotBlockOrRepayItsSlot()=runTest {
        for(failure in listOf(IllegalArgumentException("bad metadata"),IllegalStateException("bad candidate"),java.io.IOException("unreadable metadata"))) {
            val fake=CorruptSlotEnvironment(false).apply {candidateFailure=failure}
            val result=RemoteScenePhotos(fake).prepare(fake.chat,fake.targets,fake.profile,null)
            assertEquals(1,result.prepared);assertEquals(1,fake.imageCalls)
            assertEquals(fake.targets[1].slot.time,fake.published.single().slot)
            assertEquals("success",fake.job(fake.oldId)!!.job.state)
            assertFalse(fake.published.any {it.requestId==fake.oldId})
        }
    }
    @Test fun fullCandidateStorageStopsBeforeANewReservationOrPaidRequest()=runTest {
        val fake=CorruptSlotEnvironment(false).apply {capacity=false}
        val result=RemoteScenePhotos(fake).prepare(fake.chat,fake.targets.drop(1),fake.profile,null)
        assertEquals("candidate_storage_full",result.reason)
        assertEquals(0,fake.imageCalls);assertNull(fake.job(RemoteSceneRules.requestId(fake.freeze(fake.chat,fake.targets[1],fake.profile,null),fake.targets[1],0)))
    }
    @Test fun fullCandidateStorageDoesNotConsumeTheExplicitRetryOpportunity()=runTest {
        val fake=CorruptSlotEnvironment(false).apply {capacity=false;failOld()}
        val result=RemoteScenePhotos(fake).retryFailed(fake.chat,fake.targets[0],fake.profile,null,fake.oldId)
        assertEquals("candidate_storage_full",result.reason)
        assertEquals(0,fake.retryReservations);assertEquals(0,fake.imageCalls)
    }
    @Test fun corruptOrConcurrentlyClearedApprovedPixelsSkipOnlyTheirSlot() = runTest {
        val errors = mutableListOf<String>()
        for (clearException in listOf(false, true)) {
            val fake = CorruptSlotEnvironment(clearException)
            try {
                val result = RemoteScenePhotos(fake).prepare(fake.chat, fake.targets, fake.profile, null)
                if (result.prepared != 1 || fake.imageCalls != 1 || fake.published.singleOrNull()?.slot != fake.targets[1].slot.time)
                    errors.add("clearException=$clearException: later slot did not prepare exactly once")
            } catch (_: IllegalArgumentException) {
                errors.add("clearException=$clearException: cleared old slot blocked the batch")
            }
            assertEquals("success", fake.job(fake.oldId)!!.job.state)
            assertEquals(fake.oldId, fake.candidate(fake.oldId)!!.requestId)
            assertFalse(fake.published.any { it.requestId == fake.oldId })
        }
        assertEquals(emptyList<String>(), errors)
    }
}

private class CorruptSlotEnvironment(private val clearException: Boolean) : RemoteSceneEnvironment {
    var candidateFailure:Exception?=null
    var capacity=true;var retryReservations=0
    val chat = "a".repeat(32); val bytes = byteArrayOf(1, 2, 3)
    val profile = VisualProfile("import", "OC", "unspecified", "long", 3, "b".repeat(32), null, GenerationQuota(false, 0, 0, 2))
    private val scene = LocalDailyRules.scene(chat, 3, Instant.parse("2026-10-14T00:00:00Z"))
    val targets = scene.slots.take(2).mapIndexed { i, slot -> LocalScenePrefetchTarget(scene, i, slot, false) }
    private val binding = RemoteSceneBinding(chat, profile, null, OcAssetRef(profile.referenceId!!, ChatPhotoRules.sha(bytes), 3),
        "synthetic-image-key", Image2Protocol.DEFAULT_MODEL, SavedConnection("local://phone", "deepseek", "synthetic-chat-key", "deepseek-v4-flash"),
        CompanionBinding("deepseek", "deepseek-v4-flash"), bytes)
    val oldId = RemoteSceneRules.requestId(binding, targets.first(), 0)
    private val jobs = mutableMapOf<String, StoredImageJob>()
    private val candidates = mutableMapOf<String, RemoteSceneCandidate>()
    val published = mutableListOf<RemoteSceneCandidate>(); var imageCalls = 0
    init {
        jobs[oldId] = StoredImageJob(LocalGenerationJob(oldId, chat, "scene", 3, scene.dayId, RemoteSceneRules.metadataHash(oldId), "success"), null)
        candidates[oldId] = RemoteSceneCandidate(oldId, chat, RemoteSceneRules.bindingSha(binding), ChatPhotoRules.sha(bytes), "image/png",
            "The same OC in the original crop.", 0, scene.dayId, targets.first().slot.time, LocalDailyRules.revision(scene),
            LifePhotoReviewStatus.APPROVED, originalId = binding.original.id, originalSha = binding.original.sha256, visualVersion = 3)
    }
    override fun configured() = true
    override fun hasCandidateCapacity()=capacity
    fun failOld(){val old=jobs.getValue(oldId);jobs[oldId]=old.copy(job=old.job.copy(state="failed"))}
    override fun reserveRetry(sourceId:String,job:LocalGenerationJob){retryReservations++;jobs[job.requestId]=StoredImageJob(job,null)}
    override suspend fun freeze(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile, snapshot: OcIdentitySnapshot?) = binding
    override suspend fun unchanged(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget) = true
    override fun alreadyPrepared(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile) = false
    override fun job(requestId: String) = jobs[requestId]
    override fun reserve(job: LocalGenerationJob) { check(job.requestId != oldId); jobs[job.requestId] = StoredImageJob(job, null) }
    override fun finish(requestId: String, state: String) {
        val previous = jobs.getValue(requestId); jobs[requestId] = previous.copy(job = previous.job.copy(state = state))
    }
    override fun candidate(requestId: String):RemoteSceneCandidate? {
        if(requestId==oldId)candidateFailure?.let {throw it}
        return candidates[requestId]
    }
    override fun saveCandidate(candidate: RemoteSceneCandidate, bytes: ByteArray) { candidates[candidate.requestId] = candidate }
    override fun candidateBytes(candidate: RemoteSceneCandidate): ByteArray {
        if (candidate.requestId != oldId) return bytes.copyOf()
        if (clearException) throw IllegalArgumentException("候选像素已清理")
        return byteArrayOf(9)
    }
    override suspend fun prompt(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, correction: String?) = "The same OC in the original crop."
    override suspend fun generate(binding: RemoteSceneBinding, prompt: String): AgnesGeneratedImage {
        imageCalls++; return AgnesGeneratedImage(bytes, "image/png")
    }
    override suspend fun review(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, prompt: String,
        image: AgnesGeneratedImage) = LifePhotoReview(LifePhotoReviewStatus.APPROVED, "synthetic review", ChatPhotoRules.sha(image.bytes))
    override suspend fun publish(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, candidate: RemoteSceneCandidate): Boolean {
        published.add(candidate); return true
    }
}
