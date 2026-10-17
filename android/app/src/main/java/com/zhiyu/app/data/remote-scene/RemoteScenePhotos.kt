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
    fun reviewConfigured(): Boolean = environment.reviewConfigured()

    suspend fun prepare(chat: String, targets: List<LocalScenePrefetchTarget>, profile: VisualProfile,
        snapshot: OcIdentitySnapshot?): LocalSceneStockResult = serial.withLock {
        MediaRules.path(chat)
        val frozen = targets.map(RemoteSceneRules::freeze)
        require(frozen.size <= 72 + LocalSelfieRules.SIZE && frozen.all { it.scene.visualVersion == profile.visualVersion })
        if (!configured()) return@withLock deferred("unconfigured")
        if (!reviewConfigured()) return@withLock deferred("review_unavailable")
        var skipped: LocalSceneStockResult? = null
        for (target in frozen) {
            currentCoroutineContext().ensureActive()
            if (environment.alreadyPrepared(chat, target, profile)) continue
            val binding = environment.freeze(chat, target, profile, snapshot)
            if (binding == null) { skipped = deferred("original_or_connection_unavailable"); continue }
            if (!environment.unchanged(binding, target)) { skipped = deferred("stale"); continue }
            val outcome = prepareOne(binding, target)
            if (!outcome.skip) return@withLock outcome.result
            skipped = outcome.result
        }
        skipped ?: LocalSceneStockResult()
    }

    /** Review only stored pixels. The generation receipt and binding remain unchanged. */
    suspend fun recheck(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile,
        snapshot: OcIdentitySnapshot?, requestId: String): LocalSceneStockResult = serial.withLock {
        MediaRules.path(chat); MediaRules.path(requestId)
        val frozen = actionTarget(target, profile)
        val binding = environment.freeze(chat, frozen, profile, snapshot)
            ?: return@withLock deferred("original_or_connection_unavailable")
        val job = environment.job(requestId)?.job ?: return@withLock deferred("candidate_unavailable")
        if (job.state in setOf("reserved", "running")) return@withLock deferred("request_in_flight")
        val candidate = savedCandidate(requestId) ?: return@withLock deferred("candidate_unavailable")
        if (candidate.pixelsRemoved) return@withLock deferred("candidate_removed")
        if (!RemoteSceneRules.ledgerMatches(environment, binding, frozen, candidate)) return@withLock deferred("stale")
        val bytes = savedPixels(candidate) ?: return@withLock deferred("candidate_pixels_changed")
        if (!environment.unchanged(binding, frozen)) return@withLock deferred("stale")
        if (RemoteSceneRules.currentApproval(binding, candidate)) return@withLock replayApproved(binding, frozen, candidate)
        val pending = candidate.copy(status = LifePhotoReviewStatus.UNKNOWN, reason = "review_pending",
            correction = null, reviewBindingSha = null)
        environment.saveCandidate(pending, bytes)
        try {
            currentCoroutineContext().ensureActive()
            if (!environment.unchanged(binding, frozen)) return@withLock deferred("stale")
            reviewAndPublish(binding, frozen, pending, AgnesGeneratedImage(bytes, pending.mime), true)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            deferred(failureReason(failure))
        }
    }

    /** Explicit authorization creates at most one durable child of a known failed normal request. */
    suspend fun retryFailed(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile,
        snapshot: OcIdentitySnapshot?, requestId: String): LocalSceneStockResult = serial.withLock {
        MediaRules.path(chat); MediaRules.path(requestId)
        val frozen = actionTarget(target, profile)
        if (!reviewConfigured()) return@withLock deferred("review_unavailable")
        val binding = environment.freeze(chat, frozen, profile, snapshot)
            ?: return@withLock deferred("original_or_connection_unavailable")
        if (!environment.unchanged(binding, frozen)) return@withLock deferred("stale")
        val attempt = RemoteSceneRules.normalAttempt(binding, frozen, requestId)
            ?: return@withLock deferred("retry_unavailable")
        val source = environment.job(requestId)?.job ?: return@withLock deferred("retry_unavailable")
        if (source.state != "failed" || environment.retrySource(requestId) != null ||
            !RemoteSceneRules.jobMatches(source, binding, frozen, requestId)) return@withLock deferred("retry_unavailable")
        if (environment.retryFor(requestId) != null) return@withLock recoverRetry(binding, frozen, requestId).result
        val id = RemoteSceneRules.retryId(requestId)
        val child = source.copy(requestId = id, state = "reserved")
        if(!environment.hasCandidateCapacity())return@withLock deferred("candidate_storage_full")
        try { environment.reserveRetry(requestId, child) } catch (failure: ApiFailure) {
            if (failure.status != 409) throw failure
            return@withLock deferred("retry_already_reserved")
        }
        val correction = if (attempt == 1) savedCandidate(RemoteSceneRules.requestId(binding, frozen, 0))
            ?.takeIf { RemoteSceneRules.candidateMatches(binding, frozen, it) }?.correction?.let(RemoteSceneRules::correction) else null
        generateReserved(binding, frozen, id, attempt, correction, requestId)
    }

    private fun actionTarget(target: LocalScenePrefetchTarget, profile: VisualProfile) = RemoteSceneRules.freeze(target).also {
        require(it.scene.visualVersion == profile.visualVersion)
    }

    private suspend fun prepareOne(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget): QueueOutcome {
        val firstId = RemoteSceneRules.requestId(binding, target, 0)
        val prior = environment.job(firstId) ?: return used(generate(binding, target, firstId, 0, null))
        if (prior.job.state == "failed") return recoverRetry(binding, target, firstId)
        val candidate = savedCandidate(firstId)
        if (prior.job.state != "success" || candidate == null) return skipped(priorReason(prior.job.state))
        if (!RemoteSceneRules.ledgerMatches(environment, binding, target, candidate)) return skipped("stale")
        if (RemoteSceneRules.currentApproval(binding, candidate)) return replayOutcome(binding,target,candidate)
        if (!RemoteSceneRules.currentReview(binding, candidate)) return skipped("stale")
        if (candidate.status != LifePhotoReviewStatus.REJECTED) return skipped(reviewReason(candidate.status))
        val correction = RemoteSceneRules.correction(candidate.correction) ?: return skipped("rejected")
        val nextId = RemoteSceneRules.requestId(binding, target, 1)
        val second = environment.job(nextId)
        if (second != null) {
            if (second.job.state == "failed") return recoverRetry(binding, target, nextId)
            val result = savedCandidate(nextId)
            if (second.job.state == "success" && result != null &&
                RemoteSceneRules.ledgerMatches(environment, binding, target, result) &&
                RemoteSceneRules.currentApproval(binding, result)) return replayOutcome(binding,target,result)
            return skipped(if (second.job.state == "success") "rejected" else priorReason(second.job.state))
        }
        return used(generate(binding, target, nextId, 1, correction))
    }

    private suspend fun recoverRetry(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, sourceId: String): QueueOutcome {
        val id = environment.retryFor(sourceId) ?: return skipped("failed")
        val job = environment.job(id)?.job ?: return skipped("retry_unavailable")
        if (job.state != "success") return skipped(priorReason(job.state))
        val candidate = savedCandidate(id) ?: return skipped("candidate_unavailable")
        if (candidate.retryOf != sourceId || !RemoteSceneRules.ledgerMatches(environment, binding, target, candidate)) return skipped("stale")
        return if (RemoteSceneRules.currentApproval(binding, candidate)) replayOutcome(binding,target,candidate) else
            skipped(reviewReason(candidate.status))
    }

    private suspend fun replayOutcome(binding:RemoteSceneBinding,target:LocalScenePrefetchTarget,candidate:RemoteSceneCandidate):QueueOutcome {
        val result=try {replayApproved(binding,target,candidate)}
            catch(_:IllegalArgumentException){return skipped("candidate_pixels_changed")}
            catch(_:IllegalStateException){return skipped("candidate_pixels_changed")}
            catch(_:java.io.IOException){return skipped("candidate_pixels_changed")}
        return if(result.prepared>0)used(result)else QueueOutcome(result,true)
    }

    private suspend fun replayApproved(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        candidate: RemoteSceneCandidate): LocalSceneStockResult {
        if (!environment.unchanged(binding, target)) return deferred("stale")
        if (savedPixels(candidate) == null) return deferred("candidate_pixels_changed")
        if (environment.alreadyPrepared(binding.chat, target, binding.profile)) return LocalSceneStockResult()
        return if (environment.publish(binding, target, candidate)) LocalSceneStockResult(1, true, "yield") else deferred("stale")
    }

    private fun savedCandidate(id:String):RemoteSceneCandidate? = try {environment.candidate(id)}
        catch(_:IllegalArgumentException){null}
        catch(_:IllegalStateException){null}
        catch(_:java.io.IOException){null}

    private fun savedPixels(candidate: RemoteSceneCandidate): ByteArray? {
        if (candidate.pixelsRemoved) return null
        val bytes = try {environment.candidateBytes(candidate)}
            catch(_:IllegalArgumentException){return null}
            catch(_:IllegalStateException){return null}
            catch(_:java.io.IOException){return null}
        return bytes.takeIf { it.size in 1..Image2Protocol.MAX_IMAGE_BYTES && ChatPhotoRules.sha(it) == candidate.imageSha }
    }

    private suspend fun generate(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        id: String, attempt: Int, correction: String?): LocalSceneStockResult {
        if(!environment.hasCandidateCapacity())return deferred("candidate_storage_full")
        environment.reserve(LocalGenerationJob(id, binding.chat, "scene", binding.profile.visualVersion, target.scene.dayId,
            RemoteSceneRules.metadataHash(id), "reserved"))
        return generateReserved(binding, target, id, attempt, correction, null)
    }

    private suspend fun generateReserved(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        id: String, attempt: Int, correction: String?, retryOf: String?): LocalSceneStockResult {
        environment.finish(id, "running")
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
                originalSha = binding.original.sha256, visualVersion = binding.profile.visualVersion,
                packageSha = binding.packageSha, retryOf = retryOf)
            environment.saveCandidate(candidate, image.bytes); knownCandidate = true
            if (!environment.unchanged(binding, target)) { environment.finish(id, "stale"); return deferred("stale") }
            val result = reviewAndPublish(binding, target, candidate, image)
            environment.finish(id, if (result.reason == "stale") "stale" else "success")
            return result
        } catch (failure: Exception) {
            val state = if (stale(failure)) "stale" else if (knownCandidate) "success" else if (terminal(failure)) "failed" else "unknown"
            environment.finish(id, state)
            if (failure is CancellationException) throw failure
            return deferred(failureReason(failure))
        }
    }

    private suspend fun reviewAndPublish(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        candidate: RemoteSceneCandidate, image: AgnesGeneratedImage, recheck: Boolean = false): LocalSceneStockResult {
        if ((target.selfie != null || target.reshoot != null) && candidate.imageSha == binding.original.sha256) {
            environment.saveCandidate(candidate.copy(status = LifePhotoReviewStatus.REJECTED,
                reason = "selfie_reused_original", correction = null), image.bytes)
            return deferred("rejected")
        }
        var review = environment.review(binding, target, candidate.prompt, image)
        currentCoroutineContext().ensureActive()
        if (!environment.unchanged(binding, target)) return deferred("stale")
        if (review.imageSha256 != candidate.imageSha) review = LifePhotoReview(LifePhotoReviewStatus.UNKNOWN, "image_changed", candidate.imageSha)
        val saved = candidate.copy(status = review.status, reason = review.reason.take(240),
            correction = if (candidate.attempt == 0 && candidate.retryOf == null && review.status == LifePhotoReviewStatus.REJECTED)
                RemoteSceneRules.correction(review.correction) else null,
            reviewBindingSha = if (recheck && review.status in setOf(LifePhotoReviewStatus.APPROVED, LifePhotoReviewStatus.REJECTED))
                RemoteSceneRules.bindingSha(binding) else candidate.reviewBindingSha)
        environment.saveCandidate(saved, image.bytes)
        if (saved.status == LifePhotoReviewStatus.APPROVED)
            return if (environment.publish(binding, target, saved)) LocalSceneStockResult(1, true, "yield") else deferred("stale")
        return deferred(if (saved.status == LifePhotoReviewStatus.REJECTED && saved.correction != null) "correction_pending" else reviewReason(saved.status))
    }

    private fun stale(failure: Exception) = failure is ContactDeliveryBlockedException || failure is ApiFailure &&
        failure.errorCode in setOf("life_prompt_stale", "life_photo_stale")
    private fun terminal(failure: Exception) = failure is ApiFailure && failure.errorCode in setOf(
        "image_auth_failed", "image_balance_insufficient", "provider_auth_failed", "provider_balance_insufficient")
    private fun failureReason(failure: Exception) = if (stale(failure)) "stale" else if (terminal(failure))
        (failure as ApiFailure).errorCode!! else "unknown"
    private fun priorReason(state: String) = when (state) { "stale" -> "stale"; "failed" -> "failed"; else -> "unknown" }
    private fun reviewReason(status: LifePhotoReviewStatus) = when (status) {
        LifePhotoReviewStatus.REJECTED -> "rejected"
        LifePhotoReviewStatus.UNAVAILABLE -> "review_unavailable"
        else -> "review_unknown"
    }
    private fun deferred(reason: String) = LocalSceneStockResult(0, true, reason)
    private fun skipped(reason: String) = QueueOutcome(deferred(reason), true)
    private fun used(result: LocalSceneStockResult) = QueueOutcome(result, false)
    private data class QueueOutcome(val result: LocalSceneStockResult, val skip: Boolean)
    companion object { private val serial = Mutex() }
}
