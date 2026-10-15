package com.zhiyu.app.data.ocexpansion

import com.zhiyu.app.data.StoredImageJob
import com.zhiyu.app.data.remotescene.LifePhotoReview
import com.zhiyu.app.data.remotescene.RemoteSceneCandidate
import com.zhiyu.app.domain.*

class OcExpansionBinding(val chat: String, val policy: OcFramingPolicy,
    val connection: SavedConnection, val companion: CompanionBinding, val imageKey: String,
    val imageModel: String, original: ByteArray, val characterSha: String? = null, val description: String = "") {
    private val pixels = original.copyOf()
    init {
        MediaRules.path(chat); OcFramingRules.check(policy); Image2Protocol.checkKey(imageKey)
        require(imageModel in Image2Protocol.MODELS && pixels.size in 1..ChatPhotoRules.MAX_ORIGINAL_BYTES &&
            ChatPhotoRules.sha(pixels) == policy.original.sha256) { "扩图原参考无效" }
        require(connection.providerId == companion.providerId && connection.model == companion.model && description.length <= 1000)
        characterSha?.let(ChatPhotoRules::digest)
    }
    val originalBytes get() = pixels.copyOf()
    override fun toString() = "OcExpansionBinding(chat=$chat, revision=${policy.revision})"
}

data class OcExpansionRow(val requestId: String, val imageSha: String?, val status: String,
    val canReview: Boolean, val canConfirm: Boolean)
data class OcExpansionView(val policy: OcFramingPolicy?, val candidate: OcExpansionRow?,
    val canGenerate: Boolean, val configured: Boolean)

interface OcExpansionManagement {
    suspend fun state(chat: String): OcExpansionView
    suspend fun chooseFraming(chat: String, framing: OcFraming, allowExpansion: Boolean): OcExpansionView
    /** Explicit user action; repeated calls for the same policy never repeat a paid image request. */
    suspend fun generate(chat: String): OcExpansionView
    suspend fun recheck(chat: String, requestId: String): OcExpansionView
    suspend fun preview(chat: String, requestId: String): ByteArray
    suspend fun confirm(chat: String, requestId: String, expectedSha: String): OcExpansionView
}

interface OcExpansionEnvironment {
    suspend fun freeze(chat: String): OcExpansionBinding?
    suspend fun unchanged(binding: OcExpansionBinding): Boolean
    fun hasCapacity(): Boolean
    fun job(id: String): StoredImageJob?
    fun reserve(job: LocalGenerationJob)
    fun finish(id: String, status: String)
    fun candidate(id: String): RemoteSceneCandidate?
    fun save(candidate: RemoteSceneCandidate, bytes: ByteArray)
    fun bytes(candidate: RemoteSceneCandidate): ByteArray
    suspend fun prompt(binding: OcExpansionBinding): String
    suspend fun generate(binding: OcExpansionBinding, prompt: String): AgnesGeneratedImage
    suspend fun review(binding: OcExpansionBinding, prompt: String, image: AgnesGeneratedImage): LifePhotoReview
    suspend fun accept(binding: OcExpansionBinding, candidate: RemoteSceneCandidate, bytes: ByteArray): Boolean
}

object OcExpansionRules {
    fun requestId(chat: String, policy: OcFramingPolicy): String {
        MediaRules.path(chat)
        return hash(listOf("oc-expansion-v1", chat, OcFramingRules.hash(policy))).take(32)
    }
    fun requestId(binding: OcExpansionBinding): String = requestId(binding.chat, binding.policy)
    fun bindingSha(binding: OcExpansionBinding): String = hash(listOf(binding.chat, OcFramingRules.hash(binding.policy),
        binding.imageKey, binding.imageModel, binding.connection.serverUrl, binding.connection.providerId,
        binding.connection.model, binding.connection.apiKey, binding.connection.session?.token.orEmpty(),
        binding.companion.toString(), binding.characterSha.orEmpty(), binding.description))
    fun metadataHash(id: String) = hash(listOf("oc-expansion-metadata-v1", id))
    fun matches(binding: OcExpansionBinding, candidate: RemoteSceneCandidate): Boolean =
        candidate.requestId == requestId(binding) && candidate.chat == binding.chat && !candidate.pixelsRemoved &&
            candidate.originalId == binding.policy.original.id && candidate.originalSha == binding.policy.original.sha256 &&
            candidate.visualVersion == binding.policy.visualVersion && candidate.trajectory == OcFramingRules.hash(binding.policy) &&
            candidate.packageSha == binding.characterSha && candidate.attempt == 0 && candidate.retryOf == null && candidate.slot == "00:00"
    private fun hash(values: List<String>) = LocalMemoryCodec.sha(LocalMemoryCodec.bytes(buildString {
        values.forEach { append(it.length).append(':').append(it) }
    }))
}
