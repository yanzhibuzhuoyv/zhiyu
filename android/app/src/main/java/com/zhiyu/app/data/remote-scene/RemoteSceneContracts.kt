package com.zhiyu.app.data.remotescene

import com.zhiyu.app.data.StoredImageJob
import com.zhiyu.app.domain.*

/** Frozen original pixels and credentials. Neither keys nor image bytes appear in logs. */
class RemoteSceneBinding(val chat: String, val profile: VisualProfile, val packageSha: String?,
    val original: OcAssetRef, val imageKey: String, val imageModel: String,
    val connection: SavedConnection, val companion: CompanionBinding, originalBytes: ByteArray,
    val contactRevision: Int? = null, val target: LocalScenePrefetchTarget? = null) {
    private val pixels = originalBytes.copyOf()
    init {
        MediaRules.path(chat); MediaRules.path(original.id); ChatPhotoRules.digest(original.sha256)
        require(pixels.size in 1..ChatPhotoRules.MAX_ORIGINAL_BYTES && ChatPhotoRules.sha(pixels) == original.sha256)
        require(profile.visualVersion >= 0 && original.visualVersion >= 0 &&
            connection.providerId == companion.providerId && connection.model == companion.model)
        require(packageSha == null || Regex("[a-f0-9]{64}").matches(packageSha))
        Image2Protocol.checkKey(imageKey); require(imageModel in Image2Protocol.MODELS)
    }
    val originalBytes: ByteArray get() = pixels.copyOf()
    override fun toString() = "RemoteSceneBinding(chat=$chat, visualVersion=${profile.visualVersion})"
}

enum class LifePhotoReviewStatus { APPROVED, REJECTED, UNAVAILABLE, UNKNOWN }
data class LifePhotoReview(val status: LifePhotoReviewStatus, val reason: String,
    val imageSha256: String, val correction: String? = null) {
    val identityVerified: Boolean get() = false
}

data class RemoteSceneCandidate(val requestId: String, val chat: String, val bindingSha: String,
    val imageSha: String, val mime: String, val prompt: String, val attempt: Int,
    val day: String, val slot: String, val trajectory: String,
    val status: LifePhotoReviewStatus = LifePhotoReviewStatus.UNAVAILABLE,
    val reason: String = "not_reviewed", val correction: String? = null,
    val originalId: String? = null, val originalSha: String? = null,
    val visualVersion: Int? = null, val packageSha: String? = null,
    val pixelsRemoved:Boolean=false,val retryOf:String?=null,val reviewBindingSha:String?=null)

/** Network methods run outside commit locks; publish performs the final complete binding CAS. */
interface RemoteSceneEnvironment {
    fun configured(): Boolean
    fun hasCandidateCapacity():Boolean=true
    suspend fun freeze(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile,
        snapshot: OcIdentitySnapshot?): RemoteSceneBinding?
    suspend fun unchanged(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget): Boolean
    fun alreadyPrepared(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile): Boolean
    fun job(requestId: String): StoredImageJob?
    fun retryFor(sourceId: String): String? = null
    fun retrySource(retryId: String): String? = null
    fun reserveRetry(sourceId: String, newJob: LocalGenerationJob) {
        throw ApiFailure(409, "此环境不支持已失败照片重试", "scene_retry_unavailable")
    }
    fun reserve(job: LocalGenerationJob)
    fun finish(requestId: String, state: String)
    fun candidate(requestId: String): RemoteSceneCandidate?
    fun saveCandidate(candidate: RemoteSceneCandidate, bytes: ByteArray)
    fun candidateBytes(candidate: RemoteSceneCandidate): ByteArray
    suspend fun prompt(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, correction: String?): String
    suspend fun generate(binding: RemoteSceneBinding, prompt: String): AgnesGeneratedImage
    suspend fun review(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        prompt: String, image: AgnesGeneratedImage): LifePhotoReview
    suspend fun publish(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        candidate: RemoteSceneCandidate): Boolean
}

internal object RemoteSceneRules {
    fun retryId(sourceId: String): String {
        MediaRules.path(sourceId)
        return hash(listOf("remote-life-retry-v1", sourceId)).take(32)
    }
    fun requestId(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, attempt: Int): String {
        require(attempt in 0..1)
        return hash(listOf("remote-life-v1", binding.chat, binding.profile.visualVersion.toString(),
            binding.original.id, binding.original.sha256, binding.packageSha.orEmpty(),
            LocalDailyRules.revision(target.scene), target.index.toString(), target.slot.time, attempt.toString())).take(32)
    }
    fun bindingSha(binding: RemoteSceneBinding): String = hash(listOf(binding.chat, binding.profile.mode,
        binding.profile.description, binding.profile.gender, binding.profile.hair, binding.profile.visualVersion.toString(),
        binding.profile.referenceId.orEmpty(), binding.profile.referenceUrl.orEmpty(), binding.packageSha.orEmpty(),
        binding.original.id, binding.original.sha256, binding.original.visualVersion.toString(),
        binding.imageKey, binding.imageModel, binding.connection.serverUrl, binding.connection.providerId,
        binding.connection.apiKey, binding.connection.model, binding.companion.toString(), binding.contactRevision?.toString().orEmpty()))

    fun normalAttempt(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, id: String): Int? =
        (0..1).firstOrNull { requestId(binding, target, it) == id }

    fun metadataHash(id: String): String = ChatPhotoRules.sha(LocalMemoryCodec.bytes("remote-life-metadata:$id"))

    fun jobMatches(job: LocalGenerationJob, binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        id: String, promptHash: String = metadataHash(id)): Boolean = job.requestId == id &&
        job.chatId == binding.chat && job.kind == "scene" && job.expectedVersion == binding.profile.visualVersion &&
        job.dayId == target.scene.dayId && job.promptHash == promptHash && job.state in LocalGenerationRules.states

    fun candidateMatches(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, candidate: RemoteSceneCandidate): Boolean {
        if (candidate.pixelsRemoved || candidate.attempt !in 0..1 || candidate.chat != binding.chat ||
            candidate.day != target.scene.dayId || candidate.slot != target.slot.time ||
            candidate.originalId != binding.original.id || candidate.originalSha != binding.original.sha256 ||
            candidate.visualVersion != binding.profile.visualVersion || candidate.packageSha != binding.packageSha ||
            candidate.trajectory != LocalDailyRules.revision(target.scene)) return false
        val normalId = requestId(binding, target, candidate.attempt)
        return if (candidate.retryOf == null) candidate.requestId == normalId else
            candidate.retryOf == normalId && candidate.requestId == retryId(normalId)
    }

    fun ledgerMatches(environment: RemoteSceneEnvironment, binding: RemoteSceneBinding,
        target: LocalScenePrefetchTarget, candidate: RemoteSceneCandidate): Boolean {
        if (!candidateMatches(binding, target, candidate)) return false
        val job = environment.job(candidate.requestId)?.job ?: return false
        val parentId = candidate.retryOf
        if (parentId == null) return environment.retrySource(candidate.requestId) == null &&
            jobMatches(job, binding, target, candidate.requestId)
        val parent = environment.job(parentId)?.job ?: return false
        return environment.retrySource(parentId) == null && environment.retryFor(parentId) == candidate.requestId &&
            environment.retrySource(candidate.requestId) == parentId && parent.state == "failed" &&
            jobMatches(parent, binding, target, parentId) &&
            jobMatches(job, binding, target, candidate.requestId, parent.promptHash)
    }

    fun currentApproval(binding: RemoteSceneBinding, candidate: RemoteSceneCandidate) =
        candidate.status == LifePhotoReviewStatus.APPROVED && !candidate.pixelsRemoved &&
            currentReview(binding, candidate)

    fun currentReview(binding: RemoteSceneBinding, candidate: RemoteSceneCandidate) =
        (candidate.reviewBindingSha ?: candidate.bindingSha) == bindingSha(binding)

    fun freeze(target: LocalScenePrefetchTarget): LocalScenePrefetchTarget {
        require(target.scene.visualVersion >= 0 && target.scene.fictional && target.scene.timezone == LocalDailyRules.TIMEZONE)
        require(target.index in target.scene.slots.indices && target.index in 0..23 && target.slot == target.scene.slots[target.index])
        LocalDailyRules.current(target.scene.slots, java.time.Instant.EPOCH)
        val slots = target.scene.slots.map { it.copy(tags = it.tags.toList()) }
        return target.copy(scene = target.scene.copy(slots = slots, current = target.scene.current.copy(tags = target.scene.current.tags.toList())),
            slot = slots[target.index])
    }
    fun correction(value: String?): String? = value?.takeIf {
        it.isNotBlank() && it.length <= 1800 && it.all { char -> char.code in 32..126 }
    }
    private fun hash(parts: List<String>) = ChatPhotoRules.sha(LocalMemoryCodec.bytes(buildString {
        parts.forEach { append(it.length).append(':').append(it) }
    }))
}
