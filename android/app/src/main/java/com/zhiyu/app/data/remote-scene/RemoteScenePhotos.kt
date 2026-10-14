package com.zhiyu.app.data.remotescene

import android.content.Context
import com.zhiyu.app.data.LocalSceneStockResult
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One new remote image per worker. Unknown paid opportunities never submit automatically again. */
class RemoteScenePhotos(private val environment: RemoteSceneEnvironment) {
    constructor(context: Context) : this(PhoneRemoteSceneEnvironment(context))
    fun configured(): Boolean = environment.configured()
    suspend fun prepare(chat: String, targets: List<LocalScenePrefetchTarget>, profile: VisualProfile,
        snapshot: OcIdentitySnapshot?): LocalSceneStockResult = serial.withLock {
        MediaRules.path(chat)
        val frozen = targets.map(RemoteSceneRules::freeze)
        require(frozen.size <= 72 && frozen.all { it.scene.visualVersion == profile.visualVersion })
        if (!configured()) return@withLock deferred("unconfigured")
        for (target in frozen) {
            currentCoroutineContext().ensureActive()
            if (environment.alreadyPrepared(chat, target, profile)) continue
            val binding = environment.freeze(chat, target, profile, snapshot) ?: return@withLock deferred("original_or_connection_unavailable")
            if (!environment.unchanged(binding, target)) return@withLock deferred("stale")
            return@withLock prepareOne(binding, target)
        }
        LocalSceneStockResult()
    }

    private suspend fun prepareOne(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget): LocalSceneStockResult {
        val firstId = RemoteSceneRules.requestId(binding, target, 0)
        val prior = environment.job(firstId) ?: return generate(binding, target, firstId, 0, null)
        val candidate = environment.candidate(firstId)
        if (prior.job.state != "success" || candidate == null) return deferred(priorReason(prior.job.state))
        if (candidate.bindingSha != RemoteSceneRules.bindingSha(binding)) return deferred("stale")
        if (candidate.status == LifePhotoReviewStatus.APPROVED) return replayApproved(binding, target, candidate)
        if (candidate.status != LifePhotoReviewStatus.REJECTED) return deferred(reviewReason(candidate.status))
        val correction = RemoteSceneRules.correction(candidate.correction) ?: return deferred("rejected")
        val nextId = RemoteSceneRules.requestId(binding, target, 1)
        val second = environment.job(nextId)
        if (second != null) {
            val result = environment.candidate(nextId)
            if (second.job.state == "success" && result?.status == LifePhotoReviewStatus.APPROVED &&
                result.bindingSha == RemoteSceneRules.bindingSha(binding)) return replayApproved(binding, target, result)
            return deferred(if (second.job.state == "success") "rejected" else priorReason(second.job.state))
        }
        return generate(binding, target, nextId, 1, correction)
    }

    private suspend fun replayApproved(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        candidate: RemoteSceneCandidate): LocalSceneStockResult {
        if (!environment.unchanged(binding, target)) return deferred("stale")
        return if (environment.publish(binding, target, candidate)) LocalSceneStockResult(1, true, "yield") else deferred("stale")
    }

    private suspend fun generate(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        id: String, attempt: Int, correction: String?): LocalSceneStockResult {
        val job = LocalGenerationJob(id, binding.chat, "scene", binding.profile.visualVersion, target.scene.dayId,
            ChatPhotoRules.sha(LocalMemoryCodec.bytes("remote-life-metadata:$id")), "reserved")
        environment.reserve(job); environment.finish(id, "running")
        var knownCandidate = false
        try {
            if (!environment.unchanged(binding, target)) { environment.finish(id, "stale"); return deferred("stale") }
            val prompt = environment.prompt(binding, target, correction)
            currentCoroutineContext().ensureActive()
            if (!environment.unchanged(binding, target)) { environment.finish(id, "stale"); return deferred("stale") }
            val image = environment.generate(binding, prompt)
            val candidate = RemoteSceneCandidate(id, binding.chat, RemoteSceneRules.bindingSha(binding),
                ChatPhotoRules.sha(image.bytes), image.mimeType, prompt, attempt, target.scene.dayId,
                target.slot.time, LocalDailyRules.revision(target.scene), originalId = binding.original.id,
                originalSha = binding.original.sha256, visualVersion = binding.profile.visualVersion, packageSha = binding.packageSha)
            environment.saveCandidate(candidate, image.bytes); knownCandidate = true
            if (!environment.unchanged(binding, target)) { environment.finish(id, "stale"); return deferred("stale") }
            val result = reviewAndPublish(binding, target, candidate, image)
            environment.finish(id, if (result.reason == "stale") "stale" else "success")
            return result
        } catch (failure: Exception) {
            val stale = failure is ContactDeliveryBlockedException || failure is ApiFailure && failure.errorCode in setOf("life_prompt_stale", "life_photo_stale")
            environment.finish(id, if (stale) "stale" else if (knownCandidate) "success" else if (terminal(failure)) "failed" else "unknown")
            if (failure is CancellationException) throw failure
            return deferred(if (stale) "stale" else if (terminal(failure)) (failure as ApiFailure).errorCode!! else "unknown")
        }
    }

    private suspend fun reviewAndPublish(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        candidate: RemoteSceneCandidate, image: AgnesGeneratedImage): LocalSceneStockResult {
        var review = environment.review(binding, target, candidate.prompt, image)
        currentCoroutineContext().ensureActive()
        if (!environment.unchanged(binding, target)) return deferred("stale")
        if (review.imageSha256 != candidate.imageSha) review = LifePhotoReview(LifePhotoReviewStatus.UNKNOWN, "image_changed", candidate.imageSha)
        val saved = candidate.copy(status = review.status, reason = review.reason.take(240),
            correction = if (candidate.attempt == 0) RemoteSceneRules.correction(review.correction) else null)
        environment.saveCandidate(saved, image.bytes)
        if (saved.status == LifePhotoReviewStatus.APPROVED)
            return if (environment.publish(binding, target, saved)) LocalSceneStockResult(1, true, "yield") else deferred("stale")
        return deferred(if (saved.status == LifePhotoReviewStatus.REJECTED && saved.correction != null) "correction_pending" else reviewReason(saved.status))
    }

    private fun terminal(failure: Exception) = failure is ApiFailure && failure.errorCode in setOf(
        "image_auth_failed", "image_balance_insufficient", "provider_auth_failed", "provider_balance_insufficient")
    private fun priorReason(state: String) = when (state) { "stale" -> "stale"; "failed" -> "failed"; else -> "unknown" }
    private fun reviewReason(status: LifePhotoReviewStatus) = when (status) {
        LifePhotoReviewStatus.REJECTED -> "rejected"
        LifePhotoReviewStatus.UNAVAILABLE -> "review_unavailable"
        else -> "review_unknown"
    }
    private fun deferred(reason: String) = LocalSceneStockResult(0, true, reason)
    companion object { private val serial = Mutex() }
}
