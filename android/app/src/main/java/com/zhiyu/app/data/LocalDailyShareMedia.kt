package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Selects only already published, reviewed day-stock; never renders an on-demand substitute. */
class LocalDailyShareMedia(context: Context, private val now: () -> Instant = Instant::now) {
    private val app = context.applicationContext
    private val visual = LocalVisualRepository(app)
    private val assets = LocalMediaStore(app)

    fun scene(chat: String, at: Instant = now()): LocalDailyScene {
        return visual.withCurrentProfile(chat) { profile ->
            LocalDailyRepository(app).use { it.scene(chat, profile.visualVersion, at) }
        }
    }

    fun prepare(chat: String, event: LocalDailyShareEvent, usedSha: Set<String>): MediaAttachment? {
        require(usedSha.all { Regex("[a-f0-9]{64}").matches(it) }) { "已分享图片校验信息无效" }
        return try { withEvent(chat, event) { profile ->
            assets.list("image", dayId = event.dayId, limit = 1_000).asSequence()
                .filter { it.sha256 !in usedSha }
                .map { assets.details(it.id) }
                .filter { eligible(it, event, profile) }
                .firstOrNull { details ->
                    try { assets.download(details.attachment.id, details.attachment.sha256); true }
                    catch (_: IllegalArgumentException) { rejectedImage(); false }
                }?.attachment
        } } catch (_: IllegalArgumentException) { rejectedImage(); null }
    }

    fun <T> validateAndCommit(chat: String, event: LocalDailyShareEvent, attachment: MediaAttachment,
        save: (MediaAttachment) -> T): T {
        return withEvent(chat, event) { profile ->
            val actual = assets.details(attachment.id)
            require(actual.attachment == MediaRules.check(attachment) && eligible(actual, event, profile)) {
                "日常图片或恋人形象已更新"
            }
            assets.download(attachment.id, attachment.sha256)
            require(LocalDailySharePolicy.ready(event, now()) && LocalDailyRules.dayId(now()) == event.dayId) {
                "这段日常的分享时间已过"
            }
            save(actual.attachment)
        }
    }

    private fun <T> withEvent(chat: String, event: LocalDailyShareEvent, action: (VisualProfile) -> T): T =
        visual.withCurrentProfile(chat) { profile ->
            val instant = now()
            require(event.visualVersion == profile.visualVersion && LocalDailySharePolicy.ready(event, instant) &&
                event.dayId == LocalDailyRules.dayId(instant)) { "日常事项或恋人形象已更新" }
            LocalDailyRepository(app).use { daily ->
                daily.withCurrentScene(chat, profile.visualVersion, instant) { scene ->
                    require(matches(scene, event)) { "日常轨迹已更新" }
                    action(profile)
                }
            }
        }

    private fun matches(scene: LocalDailyScene, event: LocalDailyShareEvent): Boolean {
        if (!scene.fictional || scene.timezone != LocalDailyRules.TIMEZONE || scene.dayId != event.dayId ||
            scene.sceneId != event.sceneId || scene.visualVersion != event.visualVersion ||
            LocalDailyRules.revision(scene) != event.revision) return false
        val index = scene.slots.indexOf(event.slot)
        if (index < 1 || event.slot.tags.contains("night") && event.slot.activity == "休息") return false
        val day = LocalDate.parse(scene.dayId)
        val zone = ZoneId.of(LocalDailyRules.TIMEZONE)
        val start = day.atTime(LocalTime.parse(event.slot.time)).atZone(zone).toInstant()
        val next = scene.slots.getOrNull(index + 1)?.let { day.atTime(LocalTime.parse(it.time)).atZone(zone).toInstant() }
            ?: day.plusDays(1).atStartOfDay(zone).toInstant()
        return event.due >= start && event.due < next && event.expires <= next &&
            event.expires <= event.due.plusSeconds(45 * 60L)
    }

    private fun eligible(item: LocalMediaDetails, event: LocalDailyShareEvent, profile: VisualProfile): Boolean {
        val asset = item.attachment
        val tags = item.tags.toSet()
        if (item.purpose != "library" || asset.kind != "image" || asset.dayId != event.dayId ||
            asset.visualVersion != event.visualVersion || "daily_stock" !in tags || "decoded_final" !in tags ||
            "local_placeholder" in tags) return false
        if (item.tags.singleOrNull { it.startsWith("slot_") } != "slot_${event.slot.time.replace(":", "")}" ||
            item.tags.singleOrNull { it.startsWith("trajectory_") } != "trajectory_${event.revision.take(24)}") return false
        if (!tags.containsAll(event.slot.tags)) return false
        if ("qwen_generated" in tags) { if ("composite_reviewed" !in tags) return false }
        else if ("legacy_background" !in tags) return false
        if ("contains_person" in tags) {
            if (profile.mode == "none" || profile.visualVersion <= 0 || profile.referenceId == null) return false
            val reference = assets.details(profile.referenceId).attachment
            assets.download(reference.id, reference.sha256)
        }
        return true
    }

    private fun rejectedImage() {
        android.util.Log.w("ZhiYuDailyShare", "Rejected missing, corrupt, or stale daily-share media")
    }
}
