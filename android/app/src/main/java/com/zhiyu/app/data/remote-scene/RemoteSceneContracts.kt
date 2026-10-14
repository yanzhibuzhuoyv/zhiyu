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
    val visualVersion: Int? = null, val packageSha: String? = null)

/** Network methods run outside commit locks; publish performs the final complete binding CAS. */
interface RemoteSceneEnvironment {
    fun configured(): Boolean
    suspend fun freeze(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile,
        snapshot: OcIdentitySnapshot?): RemoteSceneBinding?
    suspend fun unchanged(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget): Boolean
    fun alreadyPrepared(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile): Boolean
    fun job(requestId: String): StoredImageJob?
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
