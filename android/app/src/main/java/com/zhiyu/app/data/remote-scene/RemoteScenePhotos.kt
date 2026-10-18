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
        snapshot: OcIdentitySnapshot?, allowFailedRetry: Boolean = false,
        refillExhausted: ((List<LocalScenePrefetchTarget>, LocalSelfieTicket) -> List<LocalScenePrefetchTarget>?)? = null): LocalSceneStockResult = serial.withLock {
        MediaRules.path(chat)
        val initial = targets.map(RemoteSceneRules::freeze)
        require(initial.size <= 72 + LocalSelfieRules.SIZE && initial.all { it.scene.visualVersion == profile.visualVersion })
        if (!configured()) return@withLock deferred("unconfigured")
        if (!reviewConfigured()) return@withLock deferred("review_unavailable")
        val frozen = if (allowFailedRetry && refillExhausted != null)
            refillBeforeSubmission(chat, initial, profile, snapshot, refillExhausted) else initial
        var skipped: LocalSceneStockResult? = null
        for (target in frozen) {
            currentCoroutineContext().ensureActive()
            if (environment.alreadyPrepared(chat, target, profile)) continue
            val binding = environment.freeze(chat, target, profile, snapshot)
            if (binding == null) { skipped = deferred("original_or_connection_unavailable"); continue }
            if (!environment.unchanged(binding, target)) { skipped = deferred("stale"); continue }
            val outcome = prepareOne(binding, target, allowFailedRetry && target.selfie != null)
            if (!outcome.skip) return@withLock outcome.result
            skipped = outcome.result
        }
        skipped ?: LocalSceneStockResult()
    }

    /** Probe the existing queue before any paid work; an unknown lane does not invalidate another failed lane. */
    private suspend fun refillBeforeSubmission(chat: String, targets: List<LocalScenePrefetchTarget>, profile: VisualProfile,
        snapshot: OcIdentitySnapshot?, refill: (List<LocalScenePrefetchTarget>, LocalSelfieTicket) -> List<LocalScenePrefetchTarget>?): List<LocalScenePrefetchTarget> {
        if (targets.size != LocalSelfieRules.SIZE || targets.any { it.selfie == null } || !environment.hasCandidateCapacity()) return targets
        var exhausted: LocalSelfieTicket? = null
        for (target in targets) {
            currentCoroutineContext().ensureActive()
            if (environment.alreadyPrepared(chat, target, profile)) return targets
            val binding = environment.freeze(chat, target, profile, snapshot) ?: return targets
            if (!environment.unchanged(binding, target)) return targets
            val probe = prepareOne(binding, target, true, readOnlyProbe = true)
            if (!probe.skip) return targets
            if (exhausted == null && probe.exhausted) exhausted = target.selfie
        }
        val ticket = exhausted ?: return targets
        val replacement = refill(targets, ticket)?.map(RemoteSceneRules::freeze) ?: return targets
        require(replacement.size == targets.size && targets.zip(replacement).all { (old, next) ->
            if (old.selfie != ticket) old == next else next.selfie == ticket.copy(generation = ticket.generation + 1) &&
                next.scene == old.scene && next.reshoot == null && next.slot == next.scene.current &&
                next.index == next.scene.slots.indexOf(next.scene.current)
        }) { "自拍补充机会与当前票据不一致" }
        return replacement
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
        retryKnownFailed(binding, frozen, requestId).result
    }

    /** Caller owns serial. Both explicit entry points share the same durable single-child CAS. */
    private suspend fun retryKnownFailed(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        requestId: String, readOnlyProbe: Boolean = false): QueueOutcome {
        val attempt = RemoteSceneRules.normalAttempt(binding, target, requestId)
            ?: return skipped("retry_unavailable")
        val source = knownFailedSource(binding, target, requestId) ?: return skipped("retry_unavailable")
        if (environment.retryFor(requestId) != null) return recoverRetry(binding, target, requestId, readOnlyProbe)
        if (!environment.unchanged(binding, target)) return skipped("stale")
        val id = RemoteSceneRules.retryId(requestId)
        val child = source.copy(requestId = id, state = "reserved")
        if(!environment.hasCandidateCapacity())return skipped("candidate_storage_full")
        if (readOnlyProbe) return availableProbe()
        try { environment.reserveRetry(requestId, child) } catch (failure: ApiFailure) {
            if (failure.status != 409) throw failure
            return skipped("retry_already_reserved")
        }
        val correction = if (attempt == 1) savedCandidate(RemoteSceneRules.requestId(binding, target, 0))
            ?.takeIf { RemoteSceneRules.candidateMatches(binding, target, it) }?.correction?.let(RemoteSceneRules::correction) else null
        return used(generateReserved(binding, target, id, attempt, correction, requestId))
    }

    private fun knownFailedSource(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, id: String): LocalGenerationJob? =
        environment.job(id)?.job?.takeIf { it.state == "failed" && environment.retrySource(id) == null &&
            RemoteSceneRules.jobMatches(it, binding, target, id) }

    private fun actionTarget(target: LocalScenePrefetchTarget, profile: VisualProfile) = RemoteSceneRules.freeze(target).also {
        require(it.scene.visualVersion == profile.visualVersion)
    }

    private suspend fun prepareOne(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        allowFailedRetry: Boolean, readOnlyProbe: Boolean = false): QueueOutcome {
        val firstId = RemoteSceneRules.requestId(binding, target, 0)
        val prior = environment.job(firstId) ?: return if (readOnlyProbe) availableProbe() else used(generate(binding, target, firstId, 0, null))
        if (prior.job.state == "failed") return if (allowFailedRetry) retryKnownFailed(binding, target, firstId, readOnlyProbe)
            else recoverRetry(binding, target, firstId, readOnlyProbe)
        val candidate = savedCandidate(firstId)
        if (prior.job.state != "success" || candidate == null) return skipped(priorReason(prior.job.state))
        if (!RemoteSceneRules.ledgerMatches(environment, binding, target, candidate)) return skipped("stale")
        if (RemoteSceneRules.currentApproval(binding, candidate)) return replayOutcome(binding,target,candidate,readOnlyProbe)
        if (!RemoteSceneRules.currentReview(binding, candidate)) return skipped("stale")
        if (candidate.status != LifePhotoReviewStatus.REJECTED) return skipped(reviewReason(candidate.status))
        val correction = RemoteSceneRules.correction(candidate.correction) ?: return skipped("rejected")
        val nextId = RemoteSceneRules.requestId(binding, target, 1)
        val second = environment.job(nextId)
        if (second != null) {
            if (second.job.state == "failed") return if (allowFailedRetry) retryKnownFailed(binding, target, nextId, readOnlyProbe)
                else recoverRetry(binding, target, nextId, readOnlyProbe)
            val result = savedCandidate(nextId)
            if (second.job.state == "success" && result != null &&
                RemoteSceneRules.ledgerMatches(environment, binding, target, result) &&
                RemoteSceneRules.currentApproval(binding, result)) return replayOutcome(binding,target,result,readOnlyProbe)
            return skipped(if (second.job.state == "success") "rejected" else priorReason(second.job.state))
        }
        return if (readOnlyProbe) availableProbe() else used(generate(binding, target, nextId, 1, correction))
    }

    private suspend fun recoverRetry(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, sourceId: String,
        readOnlyProbe: Boolean = false): QueueOutcome {
        val id = environment.retryFor(sourceId) ?: return skipped("failed")
        val job = environment.job(id)?.job ?: return skipped("retry_unavailable")
        if (job.state != "success") {
            val source = if (readOnlyProbe && job.state == "failed") knownFailedSource(binding, target, sourceId) else null
            val exhausted = source != null && id == RemoteSceneRules.retryId(sourceId) &&
                environment.retrySource(id) == sourceId && RemoteSceneRules.jobMatches(job, binding, target, id, source.promptHash)
            return QueueOutcome(deferred(priorReason(job.state)), true, exhausted)
        }
        val candidate = savedCandidate(id) ?: return skipped("candidate_unavailable")
        if (candidate.retryOf != sourceId || !RemoteSceneRules.ledgerMatches(environment, binding, target, candidate)) return skipped("stale")
        return if (RemoteSceneRules.currentApproval(binding, candidate)) replayOutcome(binding,target,candidate,readOnlyProbe) else
            skipped(reviewReason(candidate.status))
    }

    private suspend fun replayOutcome(binding:RemoteSceneBinding,target:LocalScenePrefetchTarget,candidate:RemoteSceneCandidate,
        readOnlyProbe: Boolean = false):QueueOutcome {
        if (readOnlyProbe) return if (savedPixels(candidate) == null) skipped("candidate_pixels_changed") else availableProbe()
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
        var phase = "prompt"
        try {
            if (!environment.unchanged(binding, target)) { environment.finish(id, "stale"); return deferred("stale") }
            val prompt = environment.prompt(binding, target, correction)
            currentCoroutineContext().ensureActive()
            if (!environment.unchanged(binding, target)) { environment.finish(id, "stale"); return deferred("stale") }
            phase = "image"
            val image = environment.generate(binding, prompt)
            val candidate = RemoteSceneCandidate(id, binding.chat, RemoteSceneRules.bindingSha(binding),
                ChatPhotoRules.sha(image.bytes), image.mimeType, prompt, attempt, target.scene.dayId,
                target.slot.time, LocalDailyRules.revision(target.scene), originalId = binding.original.id,
                originalSha = binding.original.sha256, visualVersion = binding.profile.visualVersion,
                packageSha = binding.packageSha, retryOf = retryOf)
            environment.saveCandidate(candidate, image.bytes); knownCandidate = true
            if (!environment.unchanged(binding, target)) { environment.finish(id, "stale"); return deferred("stale") }
            phase = "review"
            val result = reviewAndPublish(binding, target, candidate, image)
            environment.finish(id, if (result.reason == "stale") "stale" else "success")
            return result
        } catch (failure: Exception) {
            if (com.zhiyu.app.BuildConfig.DEBUG) android.util.Log.w("ZhiYuImages",
                "Remote photo phase=$phase type=${failure.javaClass.simpleName} code=${safeDiagnosticCode(failure)}")
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
    private fun availableProbe() = used(deferred("probe_available"))
    private fun safeDiagnosticCode(failure: Exception): String = (failure as? ApiFailure)?.errorCode?.takeIf { it in setOf(
        "image_auth_failed", "image_balance_insufficient", "provider_auth_failed", "provider_balance_insufficient",
        "life_prompt_unknown", "life_prompt_stale", "life_photo_stale", "provider_response_invalid",
        "image_request_unknown", "image_response_invalid") } ?: "unknown"
    private fun skipped(reason: String) = QueueOutcome(deferred(reason), true)
    private fun used(result: LocalSceneStockResult) = QueueOutcome(result, false)
    private data class QueueOutcome(val result: LocalSceneStockResult, val skip: Boolean, val exhausted: Boolean = false)
    companion object { private val serial = Mutex() }
}
