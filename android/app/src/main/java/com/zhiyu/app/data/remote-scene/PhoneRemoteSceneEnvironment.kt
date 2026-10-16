package com.zhiyu.app.data.remotescene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import com.zhiyu.app.data.ocexpansion.OcFramingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.math.roundToInt

internal class PhoneRemoteSceneEnvironment(context: Context) : RemoteSceneEnvironment {
    private val app = context.applicationContext
    private val credentials = appCredentialStore(app)
    private val imageCredentials = RemoteImageCredentialStore(app)
    private val visual = LocalVisualRepository(app, imageKey = { null })
    private val media = LocalMediaStore(app)
    private val characters = OcCreatorStore(app)
    private val pauses = ContactPauseStore(app)
    private val candidates = RemoteSceneCandidateStore(app)
    private val framing = OcFramingStore(app)
    private val selfies = com.zhiyu.app.data.selfie.LocalSelfieBank(app)
    private val prompts = LifePhotoPromptClient(credentials, stillCurrent = ::unchanged)
    private fun jobs() = LocalImageJobStore(app)
    override fun configured() = imageCredentials.configuration() != null
    override fun hasCandidateCapacity()=candidates.hasCapacity()

    override suspend fun freeze(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile,
        snapshot: OcIdentitySnapshot?): RemoteSceneBinding? = withContext(Dispatchers.IO) {
        val configuration = imageCredentials.configuration() ?: return@withContext null
        val connection = credentials.read() ?: return@withContext null
        val companion = credentials.readBinding() ?: return@withContext null
        val contact = pauses.read(chat); if (contact.paused) return@withContext null
        if (connection.providerId != companion.providerId || connection.model != companion.model) return@withContext null
        val binding = visual.withCurrentProfile(chat) { current ->
            if (!sameProfile(current, profile)) return@withCurrentProfile null
            val saved = relevant(characters.read(chat), profile)
            if (saved?.packageSha != snapshot?.packageSha) return@withCurrentProfile null
            val policy = framing.active(chat, profile)
            val original = source(profile, snapshot, policy) ?: return@withCurrentProfile null
            RemoteSceneBinding(chat, profile, snapshot?.packageSha, original, configuration.key,
                configuration.model, connection, companion, media.download(original.id, original.sha256), contact.revision, target, policy)
        } ?: return@withContext null
        if (unchanged(binding, target)) binding else null
    }

    private fun source(profile: VisualProfile, snapshot: OcIdentitySnapshot?, policy: OcFramingPolicy?): OcAssetRef? {
        if (policy != null) return OcFramingRules.reference(policy)
        if (snapshot != null) return snapshot.identity.photoSource
        val id = profile.referenceId ?: return null
        val item = media.details(id)
        // Legacy extracted/composited references are not substitutes for a missing original.
        if (item.attachment.sourceLabel in setOf("OC 人物预览", "OC 手动修整预览", "你制作的 OC")) return null
        return OcAssetRef(id, item.attachment.sha256, item.attachment.visualVersion)
    }

    override suspend fun unchanged(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget): Boolean = withContext(Dispatchers.IO) {
        val contact = pauses.read(binding.chat)
        if (contact.paused || contact.revision != binding.contactRevision) return@withContext false
        val profileNow = visual.profile(binding.chat)
        LocalDailyRepository(app).use { current(binding, target, profileNow, characters.read(binding.chat), it) }
    }

    private fun current(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, profile: VisualProfile,
        snapshot: OcIdentitySnapshot?, daily: LocalDailyRepository): Boolean {
        if (!sameProfile(profile, binding.profile) || relevant(snapshot, profile)?.packageSha != binding.packageSha ||
            imageCredentials.configuration() != RemoteImageConfiguration(binding.imageKey, binding.imageModel) ||
            credentials.read() != binding.connection || credentials.readBinding() != binding.companion) return false
        val policy = framing.active(binding.chat, profile)
        if (policy != binding.framingPolicy) return false
        val original = source(profile, relevant(snapshot, profile), policy) ?: return false
        if (original != binding.original) return false
        val instant = Instant.now()
        if (!daily.matches(binding.chat, target.scene, instant)) return false
        val revision = LocalDailyRules.revision(target.scene)
        if (target.selfie != null) return target.scene.dayId == LocalDailyRules.dayId(instant) && selfies.current(binding.chat, profile, target)
        return LocalScenePrefetchRules.targets(daily.horizon(binding.chat, profile.visualVersion, instant), instant).any {
            it.index == target.index && it.slot == target.slot && LocalDailyRules.revision(it.scene) == revision
        }
    }

    private fun sameProfile(a: VisualProfile, b: VisualProfile) = a.copy(generation = b.generation) == b
    private fun relevant(snapshot: OcIdentitySnapshot?, profile: VisualProfile) = snapshot?.takeIf {
        it.identity.confirmed && it.identity.reference?.id == profile.referenceId &&
            it.identity.reference?.visualVersion == profile.visualVersion
    }

    override fun alreadyPrepared(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile) =
        media.list("image", visualVersion = profile.visualVersion, limit = 1000, dayId = target.scene.dayId)
            .map { media.details(it.id) }.any { item ->
                item.attachment.dayId == target.scene.dayId && item.attachment.visualVersion == profile.visualVersion &&
                    stockTags(chat, target).all { it in item.tags }
                    && (target.selfie == null || selfies.eligible(chat, item, profile, target.scene))
                    && OcFramingStockRules.permits(framing.active(chat,profile),item.tags.toSet(),"contains_person" in item.tags)
            }
    override fun job(requestId: String) = jobs().use { it.get(requestId) }
    override fun retryFor(sourceId: String) = jobs().use { it.retryFor(sourceId) }
    override fun retrySource(retryId: String) = jobs().use { it.retrySource(retryId) }
    override fun reserveRetry(sourceId: String, newJob: LocalGenerationJob) { jobs().use { it.reserveRetry(sourceId, newJob) } }
    override fun reserve(job: LocalGenerationJob) { jobs().use { it.reserve(job) } }
    override fun finish(requestId: String, state: String) { jobs().use { it.finish(requestId, state) } }
    override fun candidate(requestId: String) = candidates.read(requestId)
    override fun saveCandidate(candidate: RemoteSceneCandidate, bytes: ByteArray) = candidates.write(candidate, bytes)
    override fun candidateBytes(candidate: RemoteSceneCandidate) = candidates.bytes(candidate)
    override suspend fun prompt(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, correction: String?) =
        prompts.create(binding, target, correction)
    override suspend fun review(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, prompt: String, image: AgnesGeneratedImage): LifePhotoReview {
        val sha = ChatPhotoRules.sha(image.bytes)
        if (target.selfie != null && !selfies.acceptsPixels(binding.chat, binding.profile, target, sha))
            return LifePhotoReview(LifePhotoReviewStatus.REJECTED, "selfie_duplicate_or_stale", sha)
        return prompts.review(binding, target, prompt, image)
    }
    override suspend fun generate(binding: RemoteSceneBinding, prompt: String): AgnesGeneratedImage {
        val reference = withContext(Dispatchers.IO) { referencePng(binding.originalBytes) }
        currentCoroutineContext().ensureActive()
        if (!unchanged(binding, checkNotNull(binding.target))) throw ApiFailure(409, "角色、轨迹或连接已更新", "life_photo_stale")
        return RemoteImageClient(model = binding.imageModel).generate(binding.imageKey, prompt, reference)
    }

    override suspend fun publish(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget,
        candidate: RemoteSceneCandidate): Boolean = withContext(Dispatchers.IO) {
        require(RemoteSceneRules.currentApproval(binding, candidate) &&
            RemoteSceneRules.ledgerMatches(this@PhoneRemoteSceneEnvironment, binding, target, candidate)) { "生活照复核来源不一致" }
        val bytes = candidates.bytes(candidate)
        val coroutine = currentCoroutineContext(); coroutine.ensureActive()
        visual.withCurrentProfile(binding.chat) { profile -> commitSnapshot(binding, target, profile, candidate, bytes, coroutine) }
    }

    private fun commitSnapshot(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, profile: VisualProfile,
        candidate: RemoteSceneCandidate, bytes: ByteArray, coroutine: CoroutineContext): Boolean = characters.withCurrentSnapshot(binding.chat) { snapshot ->
        LocalDailyRepository(app).use { daily -> daily.withCurrentScene(binding.chat, profile.visualVersion, Instant.now()) {
            coroutine.ensureActive()
            if (!current(binding, target, profile, snapshot, daily) || target.selfie != null &&
                !selfies.acceptsPixels(binding.chat, profile, target, candidate.imageSha)) false else
                pauses.withDeliveryAllowed(binding.chat, checkNotNull(binding.contactRevision)) {
                    candidates.withCurrentCandidate(candidate) {
                        coroutine.ensureActive()
                        if (!RemoteSceneRules.ledgerMatches(this, binding, target, candidate)) false else
                            alreadyPrepared(binding.chat, target, profile) || ingest(binding, target, profile, bytes)
                    }
                }
        } }
    }
    private fun ingest(binding: RemoteSceneBinding, target: LocalScenePrefetchTarget, profile: VisualProfile, bytes: ByteArray): Boolean {
        val tags = target.slot.tags + stockTags(binding.chat, target) +
            (if (target.selfie != null || LocalDayStockRules.view(target.index) != "landscape") listOf("contains_person") else emptyList()) +
            (binding.framingPolicy?.let(OcFramingStockRules::tags) ?: emptyList())
        if (target.selfie != null) require(ChatPhotoRules.sha(bytes) != binding.original.sha256) { "原图不能当作新自拍" }
        media.ingest("image", bytes, if (target.selfie != null) "云服务生成并视觉复核的自拍" else "云服务生成并视觉复核的生活照",
            profile.visualVersion, target.scene.dayId,
            "${target.slot.activity} · 虚构角色${if (target.selfie != null) "自拍" else "生活照"}（自动视觉复核可能出错）", tags.distinct())
        return true
    }

    private fun stockTags(chat: String, target: LocalScenePrefetchTarget) = listOf(if (target.selfie != null) "selfie_stock" else "daily_stock", "decoded_final",
        "remote_generated", "composite_reviewed", "slot_${target.slot.time.replace(":", "")}",
        "trajectory_${LocalDailyRules.revision(target.scene).take(24)}", "remote_chat_${chat.take(24)}") +
        target.selfie?.let(LocalSelfieRules::tags).orEmpty()

    /** Encoding/resizing the complete original is a transport derivative, never a character cutout or composite. */
    private fun referencePng(original: ByteArray): ByteArray {
        val normalized = ChatPhotoImageProcessor().normalize(original)
        var bitmap = checkNotNull(BitmapFactory.decodeByteArray(normalized.bytes, 0, normalized.byteCount)) { "原图无法解码" }
        try {
            repeat(4) {
                val limit = if (it == 0) 1536 else maxOf(192, maxOf(bitmap.width, bitmap.height) / 2)
                val longest = maxOf(bitmap.width, bitmap.height)
                if (longest > limit) {
                    val scale = limit.toDouble() / longest
                    val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                        (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
                    if (smaller !== bitmap) { bitmap.recycle(); bitmap = smaller }
                }
                val output = ByteArrayOutputStream(); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                if (output.size() <= Image2Protocol.MAX_IMAGE_BYTES) return output.toByteArray()
            }
            throw IllegalArgumentException("原图发送副本仍过大，请选择较小的原图")
        } finally { bitmap.recycle() }
    }
}
