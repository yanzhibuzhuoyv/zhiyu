package com.zhiyu.app.data

import android.content.Context
import android.graphics.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Prepares cached phone-side pictures; reply generation never waits for this renderer. */
data class LocalSceneStockResult(val prepared: Int = 0, val deferred: Boolean = false, val reason: String? = null)

class LocalSceneStock(context: Context,
    backgrounds: LocalSceneBackgroundSource? = null,
    private val now: () -> Instant = Instant::now,
    private val resources: suspend () -> LocalScenePrefetchResources = LocalSceneResourceReader(context)::read,
) {
    private val app = context.applicationContext
    private val remoteDefault=backgrounds==null
    private val backgrounds=backgrounds?:QwenSceneBackgroundSource(context)
    private val remotePhotos=com.zhiyu.app.data.remotescene.RemoteScenePhotos(app)
    private val visual = LocalVisualRepository(app)
    private val media = LocalMediaStore(app)
    private val characters = OcCreatorStore(app)
    private val generatedBackgrounds=LocalGeneratedBackgrounds(app)
    private val renderer = CharacterSceneRenderer(app) { media.download(it.id, it.sha256) }
    suspend fun prepare(chat: String, expectedDay: String = LocalDailyRules.dayId(now()), expectedVersion: Int? = null) = withContext(Dispatchers.IO) {
        serial.withLock { prepareLocked(chat, expectedDay, expectedVersion) }
    }
    private suspend fun prepareLocked(chat: String, expectedDay: String, expectedVersion: Int?): LocalSceneStockResult {
        MediaRules.path(chat)
        val instant = now()
        if (expectedDay !in LocalScenePrefetchRules.days(instant)) return LocalSceneStockResult()
        val profile = visual.profile(chat)
        if (expectedVersion != null && expectedVersion != profile.visualVersion) return LocalSceneStockResult()
        val plans = LocalDailyRepository(app).use { it.horizon(chat, profile.visualVersion, instant) }
        val snapshot = characters.read(chat)?.takeIf { it.identity.reference?.id == profile.referenceId && it.identity.reference?.visualVersion == profile.visualVersion }
        if(remoteDefault) {
            val selfieTargets = com.zhiyu.app.data.selfie.LocalSelfieBank(app).targets(chat, profile, plans.first())
            return remotePhotos.prepare(chat, selfieTargets + LocalScenePrefetchRules.targets(plans,instant),profile,snapshot)
        }
        val pictureIdentity = snapshot?.let { OcCallCharacterRules.resolve(it.identity, it.identity.reference) }
        val native = backgrounds.hasInstalledPack()
        val pending = LocalScenePrefetchRules.targets(plans, instant).filterNot { hasStock(it, native) }
        var prepared = 0
        var nativeReviews = 0
        for (target in pending) {
            currentCoroutineContext().ensureActive()
            if (native) {
                val decision = LocalScenePrefetchRules.decision(resources(), target.nextDay)
                if (!decision.allowed) return LocalSceneStockResult(prepared, true, decision.reason)
            }
            val received = try {
                if (native) backgrounds.get(chat, target.scene.dayId, profile.visualVersion, target.slot)
                else generatedBackgrounds.get(chat, target.scene.dayId, profile.visualVersion, target.slot)
            } catch (_: LocalSceneBackgroundUnavailable) { continue }
            if (received == null && native) return LocalSceneStockResult(prepared, true, "pending")
            if (native && nativeReviews >= 1) { received?.recycle(); return LocalSceneStockResult(prepared, true, "yield") }
            val placeholder = received == null
            val background = if (received == null) LocalSceneBackgrounds.draw("${target.scene.sceneId}:${target.index}".hashCode(), target.slot)
                else try { checkNotNull(received.copy(Bitmap.Config.ARGB_8888, true)) { "生活背景无法复制" } } finally { received.recycle() }
            try {
                val image = render(chat, target, profile, snapshot, pictureIdentity, background)
                if (native) {
                    nativeReviews++
                    if (!backgrounds.reviewComposite(chat, target.scene.dayId, profile.visualVersion, target.slot, image,
                            reviewReference(target, profile, snapshot))) continue
                }
                publish(chat, target, profile, snapshot, image, native, placeholder)
                prepared++
            } finally { background.recycle() }
        }
        return LocalSceneStockResult(prepared)
    }
    private fun hasStock(target: LocalScenePrefetchTarget, native: Boolean): Boolean = media.list("image", limit = 1000, dayId = target.scene.dayId)
        .map { media.details(it.id) }.any { item -> item.attachment.dayId == target.scene.dayId &&
            item.attachment.visualVersion == target.scene.visualVersion && "daily_stock" in item.tags && marker(target) in item.tags &&
            revision(target) in item.tags && (!native || "qwen_generated" in item.tags && "composite_reviewed" in item.tags) }

    private suspend fun render(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile, snapshot: OcIdentitySnapshot?,
        pictureIdentity: OcIdentity?, background: Bitmap): ByteArray {
        val portrait = LocalDayStockRules.view(target.index) == "portrait" && profile.referenceId != null
        if (portrait && snapshot != null) {
            val image = renderer.render(chat, snapshot.packageSha, OcScene(name = target.slot.activity.take(80),
                background = Color.TRANSPARENT, motion = OcMotion(headTurn = .05f, breath = .03f)), background = background)
            require(characters.read(chat)?.packageSha == image.packageSha) { "角色已更新" }
            return image.png
        }
        if (portrait) {
            val ref = media.details(profile.referenceId!!).attachment
            val bitmap = LocalPortraitProcessor.decode(media.download(ref.id, ref.sha256))
            try { return composite(background, bitmap) } finally { bitmap.recycle() }
        }
        if (LocalDayStockRules.view(target.index) == "pov") drawPov(background, profile, pictureIdentity)
        return png(background)
    }
    private fun originalReference(profile: VisualProfile, snapshot: OcIdentitySnapshot?): ByteArray? {
        val source = snapshot?.identity?.photoSource ?: snapshot?.identity?.reference
        if (source != null) return media.download(source.id, source.sha256)
        val id = profile.referenceId ?: return null
        val asset = media.details(id).attachment
        return media.download(id, asset.sha256)
    }
    private fun reviewReference(target: LocalScenePrefetchTarget, profile: VisualProfile, snapshot: OcIdentitySnapshot?): ByteArray? =
        if (LocalDayStockRules.view(target.index) == "landscape") null else originalReference(profile, snapshot)
    private fun publish(chat: String, target: LocalScenePrefetchTarget, profile: VisualProfile, snapshot: OcIdentitySnapshot?,
        bytes: ByteArray, native: Boolean, placeholder: Boolean) = visual.withCurrentProfile(chat) { current ->
        require(current.visualVersion == profile.visualVersion && current.referenceId == profile.referenceId &&
            LocalDailyRepository(app).use { it.matches(chat, target.scene, now()) }) { "轨迹或外观已更新" }
        if (native) require(backgrounds.hasInstalledPack()) { "本机模型已变更" }
        if (snapshot != null) require(characters.read(chat)?.packageSha == snapshot.packageSha) { "角色已更新" }
        val portrait = LocalDayStockRules.view(target.index) == "portrait" && profile.referenceId != null
        val origin = when { native -> "本机生成并审核的生活画面"; placeholder -> "本地示意生活画面"; else -> "恋人的生活画面" }
        val tags = target.slot.tags + listOf("daily_stock", "decoded_final", marker(target), revision(target)) +
            if (native) listOf("qwen_generated", "composite_reviewed") else if (placeholder) listOf("local_placeholder") else listOf("legacy_background")
        media.ingest("image", bytes, origin, profile.visualVersion, target.scene.dayId, "${target.slot.activity} · ${if (placeholder) "本地示意" else "角色生活画面"}",
            tags + if (portrait) listOf("contains_person") else emptyList())
    }
    private fun marker(target: LocalScenePrefetchTarget) = "slot_${target.slot.time.replace(":", "")}"
    private fun revision(target: LocalScenePrefetchTarget) = "trajectory_${LocalDailyRules.revision(target.scene).take(24)}"
    private fun composite(background: Bitmap, source: Bitmap): ByteArray {
        val c=Canvas(background); val scale=minOf(background.width*.9f/source.width,background.height*.85f/source.height)
        val width=source.width*scale; val height=source.height*scale
        c.drawBitmap(source,null,RectF((background.width-width)/2,background.height-height,(background.width+width)/2,background.height.toFloat()),Paint(Paint.ANTI_ALIAS_FLAG))
        return png(background)
    }
    private fun drawPov(bitmap: Bitmap, profile: VisualProfile, identity: OcIdentity?) {
        val c=Canvas(bitmap); val p=Paint(Paint.ANTI_ALIAS_FLAG)
        if (profile.hair == "long" || (identity?.body?.hairLength ?:0f)>.6f) {
            p.color=identity?.body?.hair ?:Color.rgb(71,56,49); p.style=Paint.Style.STROKE; p.strokeWidth=5f
            repeat(8) { n -> val path=Path().apply { moveTo(n*8f,0f); cubicTo(130f,220f,20f,520f,130f+n*6,720f) }; c.drawPath(path,p) }
        }
        p.style=Paint.Style.FILL; p.color=Color.argb(60,38,38,32)
        val tall=identity?.body?.height ?:if(profile.gender=="male")1.15f else 1f
        c.drawOval(bitmap.width*.38f,bitmap.height*(.68f/tall),bitmap.width*.6f,bitmap.height.toFloat(),p)
    }
    private fun png(bitmap: Bitmap) = ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); out.toByteArray() }
    companion object { private val serial = Mutex() }
}
