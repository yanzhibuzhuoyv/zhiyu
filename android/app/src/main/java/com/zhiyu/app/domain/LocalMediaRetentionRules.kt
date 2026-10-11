package com.zhiyu.app.domain

import java.time.LocalDate
import java.time.format.DateTimeParseException

data class MediaRetentionRecord(val id: String, val kind: String, val sha256: String, val dayId: String?,
    val purpose: String, val tags: Set<String>, val sourceLabel: String)
data class MediaRetentionBlob(val kind: String, val sha256: String)
data class MediaRetentionPlan(val removeIds: List<String>, val deleteBlobs: Set<MediaRetentionBlob>, val eligibleRecords: Int)

/** Original images and sent attachments are never a retention policy candidate. */
object LocalMediaRetentionRules {
    const val KEEP_DAYS = 7L
    const val MAX_BATCH = 256
    const val MAX_RECORDS = 40_000
    private val sha = Regex("[a-f0-9]{64}")
    fun check(value: MediaRetentionRecord): MediaRetentionRecord {
        MediaRules.path(value.id)
        checkBlob(MediaRetentionBlob(value.kind, value.sha256))
        require(value.purpose in setOf("library", "portrait", "chat", "scene_background")) { "素材用途无效" }
        require(value.tags.size <= 30 && value.tags.all { it.isNotBlank() && it.length <= 40 } &&
            value.sourceLabel.length <= 200) { "素材标注无效" }
        return value
    }
    fun checkBlob(value: MediaRetentionBlob): MediaRetentionBlob {
        require(value.kind in setOf("image", "sticker") && sha.matches(value.sha256)) { "素材文件标识无效" }
        return value
    }
    fun eligible(value: MediaRetentionRecord, today: LocalDate): Boolean {
        check(value)
        if (value.kind != "image") return false
        val knownCache = value.purpose == "scene_background" && value.sourceLabel == "生成的空场景" ||
            value.purpose == "library" && value.sourceLabel == "恋人的生活画面" && "daily_stock" in value.tags
        if (!knownCache || value.dayId == null) return false
        val day = try { LocalDate.parse(value.dayId) } catch (_: DateTimeParseException) { return false }
        return day.isBefore(today.minusDays(KEEP_DAYS))
    }
    fun plan(records: List<MediaRetentionRecord>, pinnedIds: Set<String>, today: LocalDate,
        limit: Int = MAX_BATCH): MediaRetentionPlan {
        require(limit in 1..MAX_BATCH && records.size <= MAX_RECORDS) { "素材回收批次过大" }
        records.forEach { check(it) }; pinnedIds.forEach { MediaRules.path(it) }
        require(records.map { it.id }.toSet().size == records.size) { "素材编号重复" }
        val eligible = records.filter { it.id !in pinnedIds && eligible(it, today) }
            .sortedWith(compareBy<MediaRetentionRecord> { it.dayId }.thenBy { it.id })
        val selected = eligible.take(limit); val ids = selected.map { it.id }.toSet()
        val blobs = selected.map { MediaRetentionBlob(it.kind, it.sha256) }.toSet()
        return MediaRetentionPlan(selected.map { it.id }, unreferenced(blobs, records.filter { it.id !in ids }), eligible.size)
    }
    /** Across both kinds, any surviving metadata record protects that SHA. */
    fun unreferenced(candidates: Set<MediaRetentionBlob>, remaining: List<MediaRetentionRecord>): Set<MediaRetentionBlob> {
        candidates.forEach { checkBlob(it) }; remaining.forEach { check(it) }
        val referenced = remaining.map { it.sha256 }.toSet()
        return candidates.filter { it.sha256 !in referenced }.toSet()
    }
}
