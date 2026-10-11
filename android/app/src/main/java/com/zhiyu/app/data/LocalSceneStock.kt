package com.zhiyu.app.data

import android.content.Context
import android.graphics.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.time.Instant

/** Prepares cached phone-side pictures; reply generation never waits for this renderer. */
class LocalSceneStock(context: Context) {
    private val app = context.applicationContext
    private val visual = LocalVisualRepository(app)
    private val media = LocalMediaStore(app)
    private val characters = OcCreatorStore(app)
    private val generatedBackgrounds=LocalGeneratedBackgrounds(app)
    private val renderer = CharacterSceneRenderer(app) { media.download(it.id, it.sha256) }
    suspend fun prepare(chat: String, expectedDay: String = LocalDailyRules.dayId(Instant.now())) = withContext(Dispatchers.IO) {
        MediaRules.path(chat)
        if (expectedDay != LocalDailyRules.dayId(Instant.now())) return@withContext
        val profile = visual.profile(chat)
        val day = LocalDailyRepository(app).use { it.scene(chat, profile.visualVersion) }
        val snapshot = characters.read(chat)?.takeIf { it.identity.reference?.id == profile.referenceId && it.identity.reference?.visualVersion == profile.visualVersion }
        val existing = media.list("image", limit = 1000, dayId=day.dayId).map { media.details(it.id) }.filter {
            it.attachment.dayId == day.dayId && it.attachment.visualVersion == profile.visualVersion && "daily_stock" in it.tags
        }.flatMap { it.tags }.toSet()
        for ((index, slot) in day.slots.withIndex()) {
            ensureActive()
            val marker = "slot_${slot.time.replace(":", "")}"; if (marker in existing) continue
            val background = generatedBackgrounds.get(chat,day.dayId,profile.visualVersion,slot)
                ?: LocalSceneBackgrounds.draw("${day.sceneId}:$index".hashCode(), slot)
            var bitmap: Bitmap? = null
            try {
                val view = LocalDayStockRules.view(index)
                val portrait = view == "portrait" && profile.referenceId != null
                val bytes = if (portrait && snapshot != null) {
                    val scene = OcScene(name = slot.activity, cameraYaw = if (index % 2 == 0) 12f else -12f,
                        background = Color.TRANSPARENT, motion = OcMotion(headTurn = .05f, breath = .03f))
                    val image = renderer.render(chat, snapshot.packageSha, scene, background = background)
                    require(characters.read(chat)?.packageSha == image.packageSha) { "角色已更新" }
                    // The 3D renderer returns transparent pixels; composite without replacing geometry.
                    if (snapshot.identity.route == "avatar_3d") {
                        bitmap = LocalPortraitProcessor.decode(image.png); composite(background, bitmap!!)
                    } else image.png
                } else if (portrait) {
                    val ref = media.details(profile.referenceId!!).attachment
                    bitmap = LocalPortraitProcessor.decode(media.download(ref.id, ref.sha256))
                    composite(background, bitmap!!)
                } else {
                    if (view == "pov") drawPov(background, profile, snapshot?.identity)
                    png(background)
                }
                visual.withCurrentProfile(chat) { current ->
                    require(current.visualVersion == profile.visualVersion && current.referenceId == profile.referenceId &&
                        LocalDailyRules.dayId(Instant.now()) == day.dayId) { "当天或外观已更新" }
                    if (snapshot != null) require(characters.read(chat)?.packageSha == snapshot.packageSha) { "角色已更新" }
                    media.ingest("image", bytes, "恋人的生活画面", profile.visualVersion, day.dayId,
                        "${slot.activity} · ${if(portrait) "固定角色场景" else if(view=="pov") "第一人称画面" else "生活场景"}",
                        slot.tags + listOf("daily_stock", marker) + if(portrait) listOf("contains_person") else emptyList())
                }
            } finally { bitmap?.recycle(); background.recycle() }
        }
    }
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
}
