package com.zhiyu.app.data.selfie

import android.content.Context
import com.zhiyu.app.data.*
import com.zhiyu.app.data.remotescene.*
import com.zhiyu.app.domain.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Reuses published approved portrait pixels as an album photo, preserving the source ledger and dates. */
class LocalPhotoBorrower(context: Context) {
    private val app = context.applicationContext
    private val assets = LocalMediaStore(app)
    private val selfies = LocalSelfieBank(app)
    private val store = LocalPhotoBorrowStore(app)
    private val candidates = RemoteSceneCandidateStore(app)
    private val visual = LocalVisualRepository(app)
    private val characters = OcCreatorStore(app)
    private data class Proof(val attachment: MediaAttachment, val candidate: RemoteSceneCandidate)

    fun offer(chat: String, profile: VisualProfile, scene: LocalDailyScene, recentSha: Set<String>): LocalBorrowedPhoto? {
        val identity = selfies.identity(chat, profile, scene) ?: return null
        val day = LocalDate.parse(scene.dayId).plusDays(1).toString()
        val tomorrow = LocalDailyRepository(app).use { it.planned(chat, profile.visualVersion, day, instant(scene)) }
        val jobs = LocalImageJobStore(app).use { it.list(chat).filter { job ->
            job.kind == "scene" && job.dayId == day && job.expectedVersion == profile.visualVersion && job.state == "success"
        }.take(1024) }
        for (asset in assets.list("image", profile.visualVersion, 1000, day)) {
            if (asset.dayId != day || asset.sha256 in recentSha || !store.available(chat, asset)) continue
            val item = safe { assets.details(asset.id) } ?: continue
            val target = target(item, tomorrow, identity, chat) ?: continue
            if (store.adjusted(chat, target).reshoot != null) continue
            for (job in jobs) {
                val candidate = safe { candidates.read(job.requestId) } ?: continue
                if (candidate.imageSha != asset.sha256 || candidate.slot != target.slot.time) continue
                val proposed = LocalBorrowedPhoto(asset, tomorrow, target.index, candidate.requestId, identity.scope,
                    scene.dayId, LocalDailyRules.revision(scene))
                if (safe { proof(chat, proposed, profile, scene) } != null) return proposed
            }
        }
        return null
    }

    fun validate(chat: String, photo: LocalBorrowedPhoto, profile: VisualProfile, scene: LocalDailyScene): MediaAttachment? =
        proof(chat, photo, profile, scene)?.attachment?.copy(dayId = scene.dayId)

    /** Consuming the original and requesting its replacement share one SQLite transaction. Save is called by the caller once. */
    fun consume(chat: String, photo: LocalBorrowedPhoto, profile: VisualProfile, scene: LocalDailyScene): MediaAttachment? =
        visual.withCurrentProfile(chat) { current ->
            if (current.copy(generation = profile.generation) != profile) return@withCurrentProfile null
            characters.withCurrentSnapshot(chat) {
                LocalDailyRepository(app).use { daily -> daily.withCurrentScene(chat, profile.visualVersion, instant(scene)) { actual ->
                    if (LocalDailyRules.revision(actual) != LocalDailyRules.revision(scene)) return@withCurrentScene null
                    val proof = proof(chat, photo, profile, scene) ?: return@withCurrentScene null
                    candidates.withCurrentCandidate(proof.candidate) {
                        if (store.consume(chat, photo)) proof.attachment.copy(dayId = scene.dayId) else null
                    }
                } }
            }
        }

    private fun proof(chat: String, photo: LocalBorrowedPhoto, profile: VisualProfile, scene: LocalDailyScene): Proof? {
        if (photo.recipientDay != scene.dayId || photo.recipientTrajectory != LocalDailyRules.revision(scene) ||
            photo.sourceScene.dayId != LocalDate.parse(scene.dayId).plusDays(1).toString() ||
            photo.sourceScene.visualVersion != profile.visualVersion || !store.available(chat, photo.attachment)) return null
        val identity = selfies.identity(chat, profile, scene)?.takeIf { it.scope == photo.identityScope } ?: return null
        if (!LocalDailyRepository(app).use { it.matches(chat, photo.sourceScene, instant(scene)) }) return null
        val item = assets.details(photo.attachment.id)
        if (item.attachment != photo.attachment) return null
        val target = target(item, photo.sourceScene, identity, chat)?.takeIf { it.index == photo.sourceIndex } ?: return null
        if (store.adjusted(chat, target).reshoot != null) return null
        val candidate = candidates.read(photo.sourceRequestId) ?: return null
        if (!sourceApproved(chat, profile, identity, target, item.attachment, candidate)) return null
        assets.download(item.attachment.id, item.attachment.sha256)
        candidates.bytes(candidate)
        return Proof(item.attachment, candidate)
    }

    private fun target(item: LocalMediaDetails, scene: LocalDailyScene, identity: LocalSelfieIdentity, chat: String): LocalScenePrefetchTarget? {
        val asset = item.attachment; val tags = item.tags.toSet()
        if (item.purpose != "library" || asset.kind != "image" || asset.dayId != scene.dayId || asset.visualVersion != scene.visualVersion ||
            asset.sha256 in setOf(identity.original.sha256, identity.referenceSha) ||
            !tags.containsAll(setOf("daily_stock", "remote_generated", "decoded_final", "composite_reviewed", "contains_person")) ||
            tags.any { it == "selfie_stock" || it == "local_placeholder" || it.startsWith("reshoot_") } ||
            item.tags.singleOrNull { it.startsWith("remote_chat_") } != "remote_chat_${chat.take(24)}" ||
            item.tags.singleOrNull { it.startsWith("trajectory_") } != "trajectory_${LocalDailyRules.revision(scene).take(24)}" ||
            !OcFramingStockRules.permits(identity.framing, tags, true)) return null
        val index = scene.slots.indexOfFirst { "slot_${it.time.replace(":", "")}" == item.tags.singleOrNull { tag -> tag.startsWith("slot_") } }
        if (index < 0 || LocalDayStockRules.view(index) != "portrait" || !tags.containsAll(scene.slots[index].tags)) return null
        return LocalScenePrefetchTarget(scene, index, scene.slots[index], true)
    }

    private fun sourceApproved(chat: String, profile: VisualProfile, identity: LocalSelfieIdentity, target: LocalScenePrefetchTarget,
        asset: MediaAttachment, candidate: RemoteSceneCandidate): Boolean {
        if (candidate.status != LifePhotoReviewStatus.APPROVED || candidate.pixelsRemoved || candidate.chat != chat ||
            candidate.imageSha != asset.sha256 || candidate.day != target.scene.dayId || candidate.slot != target.slot.time ||
            candidate.trajectory != LocalDailyRules.revision(target.scene) || candidate.visualVersion != profile.visualVersion ||
            candidate.originalId != identity.original.id || candidate.originalSha != identity.original.sha256 || candidate.packageSha != identity.packageSha) return false
        val normal = RemoteSceneRules.opportunityId(chat, profile.visualVersion, identity.original, identity.packageSha, identity.framing, target, candidate.attempt)
        val parent = candidate.retryOf
        if (parent == null && candidate.requestId != normal || parent != null &&
            (parent != normal || candidate.requestId != RemoteSceneRules.retryId(normal))) return false
        return LocalImageJobStore(app).use { ledger ->
            val job = ledger.get(candidate.requestId)?.job ?: return@use false
            if (job.state != "success" || job.chatId != chat || job.kind != "scene" || job.dayId != target.scene.dayId ||
                job.expectedVersion != profile.visualVersion) return@use false
            if (parent == null) ledger.retrySource(job.requestId) == null && job.promptHash == RemoteSceneRules.metadataHash(normal)
            else {
                val source = ledger.get(parent)?.job ?: return@use false
                ledger.retryFor(parent) == candidate.requestId && ledger.retrySource(candidate.requestId) == parent &&
                    ledger.retrySource(parent) == null && source.state == "failed" && source.chatId == chat && source.kind == "scene" &&
                    source.dayId == target.scene.dayId && source.expectedVersion == profile.visualVersion &&
                    source.promptHash == RemoteSceneRules.metadataHash(normal) && job.promptHash == source.promptHash
            }
        }
    }
    private fun instant(scene: LocalDailyScene) = LocalDate.parse(scene.dayId).atTime(LocalTime.parse(scene.current.time))
        .atZone(ZoneId.of(LocalDailyRules.TIMEZONE)).toInstant()
    private fun <T> safe(action: () -> T): T? = try { action() } catch (failure: Exception) {
        if (failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is java.io.IOException && failure !is org.json.JSONException) throw failure
        android.util.Log.w("ZhiYuSelfies", "Borrow source could not be validated; its pixels and ledger were retained")
        null
    }
}
