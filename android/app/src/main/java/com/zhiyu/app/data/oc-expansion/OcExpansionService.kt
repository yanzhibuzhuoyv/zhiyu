package com.zhiyu.app.data.ocexpansion

import com.zhiyu.app.data.remotescene.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Domain orchestration; phone persistence and network transports are injected. */
class OcExpansionService(private val environment: OcExpansionEnvironment) {
    suspend fun generate(chat: String): RemoteSceneCandidate? {
        MediaRules.path(chat)
        val binding = environment.freeze(chat) ?: return null
        if (!OcFramingRules.canExpand(binding.policy)) return null
        val id = OcExpansionRules.requestId(binding)
        if (environment.job(id) != null) return environment.candidate(id)
        // Never reconstruct a missing charge ledger from a saved picture and submit it again.
        if (environment.candidate(id) != null) return null
        if (!environment.hasCapacity() || !environment.unchanged(binding)) return null
        environment.reserve(LocalGenerationJob(id, chat, "expansion", binding.policy.visualVersion,
            LocalDate.now(ZoneId.of(LocalDailyRules.TIMEZONE)).toString(), OcExpansionRules.metadataHash(id), "reserved"))
        environment.finish(id, "running")
        return try { generateReserved(binding, id) }
        catch (failure: Exception) {
            withContext(NonCancellable) {
                if (environment.job(id)?.job?.state in setOf("reserved", "running")) environment.finish(id,
                    if ((failure as? ApiFailure)?.errorCode in KNOWN_FAILURES) "failed" else "unknown")
            }
            if (failure is CancellationException) throw failure
            environment.candidate(id)
        }
    }
    private suspend fun generateReserved(binding: OcExpansionBinding, id: String): RemoteSceneCandidate? {
        val prompt = environment.prompt(binding)
        require(prompt.isNotBlank() && prompt.length <= 4000) { "扩图描述无效" }
        if (!environment.unchanged(binding)) { environment.finish(id, "stale"); return null }
        val image = environment.generate(binding, prompt)
        val job = checkNotNull(environment.job(id)).job
        val candidate = RemoteSceneCandidate(id, binding.chat, OcExpansionRules.bindingSha(binding),
            ChatPhotoRules.sha(image.bytes), image.mimeType, prompt, 0, job.dayId, "00:00", OcFramingRules.hash(binding.policy),
            reason = "awaiting_pixel_review", originalId = binding.policy.original.id, originalSha = binding.policy.original.sha256,
            visualVersion = binding.policy.visualVersion, packageSha = binding.characterSha)
        environment.save(candidate, image.bytes)
        if (!environment.unchanged(binding)) { environment.finish(id, "stale"); return candidate }
        environment.finish(id, "success")
        return reviewed(binding, candidate)
    }
    suspend fun recheck(chat: String, requestId: String): RemoteSceneCandidate {
        MediaRules.path(chat)
        val binding = requireNotNull(environment.freeze(chat)) { "请先确认原图和服务设置" }
        val candidate = owned(binding, requestId)
        require(environment.job(requestId)?.job?.state !in setOf("reserved", "running")) { "扩图尚在准备中" }
        require(environment.unchanged(binding)) { "原参考或服务设置已更新" }
        return reviewed(binding, candidate)
    }
    private suspend fun reviewed(binding: OcExpansionBinding, candidate: RemoteSceneCandidate): RemoteSceneCandidate {
        val pixels = pixels(candidate)
        val pending = candidate.copy(status = LifePhotoReviewStatus.UNAVAILABLE, reason = "awaiting_pixel_review", correction = null)
        environment.save(pending, pixels)
        val result = try { environment.review(binding, candidate.prompt, AgnesGeneratedImage(pixels, candidate.mime)) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            LifePhotoReview(LifePhotoReviewStatus.UNKNOWN, "review_unconfirmed", candidate.imageSha)
        }
        val current = environment.unchanged(binding)
        val status = if (!current || result.imageSha256 != candidate.imageSha) LifePhotoReviewStatus.UNKNOWN else result.status
        val accepted = status in setOf(LifePhotoReviewStatus.APPROVED, LifePhotoReviewStatus.REJECTED)
        val updated = pending.copy(status = status, reason = if (accepted) "pixel_review_complete" else "pixel_review_unconfirmed",
            reviewBindingSha = if (accepted) OcExpansionRules.bindingSha(binding) else null)
        environment.save(updated, pixels)
        return updated
    }
    suspend fun confirm(chat: String, requestId: String, expectedSha: String): Boolean {
        MediaRules.path(chat)
        ChatPhotoRules.digest(expectedSha)
        val binding = requireNotNull(environment.freeze(chat)) { "请先确认原图和服务设置" }
        val candidate = owned(binding, requestId)
        require(candidate.imageSha == expectedSha && candidate.status == LifePhotoReviewStatus.APPROVED &&
            (candidate.reviewBindingSha ?: candidate.bindingSha) == OcExpansionRules.bindingSha(binding)) { "请使用当前设置重新审图后再确认" }
        require(environment.unchanged(binding)) { "原参考或服务设置已更新" }
        val pixels = pixels(candidate)
        return environment.accept(binding, candidate, pixels)
    }
    private fun owned(binding: OcExpansionBinding, requestId: String): RemoteSceneCandidate {
        MediaRules.path(requestId)
        val candidate = requireNotNull(environment.candidate(requestId)) { "扩图候选尚未保存" }
        val job = requireNotNull(environment.job(requestId)).job
        require(OcExpansionRules.matches(binding, candidate) && job.chatId == binding.chat && job.kind == "expansion" &&
            job.expectedVersion == binding.policy.visualVersion && job.dayId == candidate.day &&
            job.promptHash == OcExpansionRules.metadataHash(requestId) && job.state !in setOf("reserved", "running")) { "原参考或取景已经更新" }
        return candidate
    }
    private fun pixels(candidate: RemoteSceneCandidate): ByteArray = environment.bytes(candidate).also {
        require(it.size in 1..Image2Protocol.MAX_IMAGE_BYTES && ChatPhotoRules.sha(it) == candidate.imageSha) { "扩图像素已变化" }
    }
    companion object {
        private val KNOWN_FAILURES = setOf("image_auth_failed", "image_balance_insufficient", "image_rate_limited",
            "image_request_invalid", "image_redirect_rejected", "image_service_busy", "provider_auth_failed", "provider_balance_insufficient")
    }
}
