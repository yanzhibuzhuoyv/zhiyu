package com.zhiyu.app.data

import android.graphics.Bitmap
import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

internal class DailyShareNativeFixture(val chat: String = LocalMediaStore.identifier()) : AutoCloseable {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    val context = NativeV4Context(base)
    val assets = LocalMediaStore(context)
    val visual = LocalVisualRepository(context)
    var now: Instant = Instant.parse("2026-10-12T05:00:00Z")
    val daily = LocalDailyRepository(context)
    val scene = daily.scene(chat, 0, now)
    val slot = scene.slots[1]
    val due = LocalDate.parse(scene.dayId).atTime(LocalTime.parse(slot.time))
        .atZone(ZoneId.of(LocalDailyRules.TIMEZONE)).toInstant()
    val event = LocalDailyShareEvent(UUID.randomUUID().toString(), scene.dayId, scene.sceneId,
        LocalDailyRules.revision(scene), scene.visualVersion, slot, due, due.plusSeconds(1_800))
    val selector = LocalDailyShareMedia(context) { now }
    val tags get() = slot.tags + listOf("daily_stock", "decoded_final", "slot_${slot.time.replace(":", "")}",
        "trajectory_${event.revision.take(24)}", "qwen_generated", "composite_reviewed")
    init { now = due.plusSeconds(5) }

    fun image(labels: List<String> = tags, day: String? = event.dayId, version: Int = 0,
        purpose: String = "library", kind: String = "image", color: Int = 0xff598d84.toInt()): MediaAttachment =
        if (kind == "sticker") legacySticker(context, png(color), "合成测试旧表情", day, labels, version)
        else assets.ingest(kind, png(color), "合成测试图片", version, day, slot.activity, labels, purpose)

    fun png(color: Int = 0xff598d84.toInt()): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            return ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
        } finally { bitmap.recycle() }
    }

    fun rewriteMetadata(asset: MediaAttachment, edit: (JSONObject) -> Unit) {
        val file = File(context.filesDir, "local_media/${asset.kind}/${asset.id}.json")
        val value = JSONObject(file.readText()); edit(value)
        LocalMediaStore.write(file, value.toString().toByteArray())
    }

    fun corruptBlob(asset: MediaAttachment) {
        val file = File(context.filesDir, "local_media/${asset.kind}/blobs/${asset.sha256}.bin")
        val changed = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 0x7f).toByte() }
        LocalMediaStore.write(file, changed)
    }

    fun changePersistedActivity() {
        val raw = daily.readableDatabase.rawQuery("SELECT payload FROM days WHERE chat=? AND day=?",
            arrayOf(chat, event.dayId)).use { check(it.moveToFirst()); it.getString(0) }
        val value = JSONObject(raw)
        value.getJSONArray("slots").getJSONObject(1).put("activity", "后来变更的真实轨迹事项")
        val changed = value.toString()
        daily.writableDatabase.update("days", ContentValues().apply {
            put("payload", changed); put("sha", LocalMediaStore.sha(changed.toByteArray()))
        }, "chat=? AND day=?", arrayOf(chat, event.dayId))
    }

    override fun close() {
        daily.close(); context.close()
        val owned = context.filesDir.canonicalFile
        check(owned.parentFile == base.filesDir.canonicalFile && owned.name.startsWith("v4_"))
        check(owned.deleteRecursively())
    }
}
