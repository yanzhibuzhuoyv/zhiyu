package com.zhiyu.app.domain

data class LocalSceneReshoot(val borrowedRequestId: String, val borrowedSha: String, val photoTime: String)
data class LocalBorrowedPhoto(val attachment: MediaAttachment, val sourceScene: LocalDailyScene,
    val sourceIndex: Int, val sourceRequestId: String, val identityScope: String,
    val recipientDay: String, val recipientTrajectory: String)

object LocalPhotoBorrowRules {
    fun replacement(target: LocalScenePrefetchTarget, requestId: String, sha: String): LocalScenePrefetchTarget {
        MediaRules.path(requestId); ChatPhotoRules.digest(sha)
        require(target.selfie == null && target.reshoot == null && target.index in target.scene.slots.indices &&
            target.slot == target.scene.slots[target.index]) { "借图补充位置无效" }
        LocalDailyRules.current(target.scene.slots, java.time.Instant.EPOCH)
        val start = java.time.LocalTime.parse(target.slot.time).toSecondOfDay()
        val end = target.scene.slots.getOrNull(target.index + 1)?.time?.let { java.time.LocalTime.parse(it).toSecondOfDay() } ?: 86400
        val capture = start + if (start + 60 < end) 60 else 1
        require(capture < end) { "该日常没有另一可用取景时点" }
        val time = java.time.LocalTime.ofSecondOfDay(capture.toLong()).toString()
        return target.copy(reshoot = LocalSceneReshoot(requestId, sha, time))
    }
    fun check(target: LocalScenePrefetchTarget) {
        target.reshoot?.let { value ->
            require(replacement(target.copy(reshoot = null), value.borrowedRequestId, value.borrowedSha).reshoot == value) {
                "借图补充时点或来源无效"
            }
        }
    }
    fun ready(day: String, tags: Set<String>, now: java.time.Instant): Boolean {
        if (tags.none { it.startsWith("reshoot_") }) return true
        val marker = tags.singleOrNull { it.startsWith("reshoot_") }?.removePrefix("reshoot_") ?: return false
        val value = tags.singleOrNull { it.startsWith("capture_") }?.removePrefix("capture_") ?: return false
        return try {
            MediaRules.path(marker)
            require(Regex("(?:[01][0-9]|2[0-3])[0-5][0-9](?:[0-5][0-9])?").matches(value))
            val at = java.time.LocalDate.parse(day).atTime(java.time.LocalTime.parse(value.chunked(2).joinToString(":")))
                .atZone(java.time.ZoneId.of(LocalDailyRules.TIMEZONE)).toInstant()
            !now.isBefore(at)
        } catch (_: IllegalArgumentException) { false }
          catch (_: java.time.DateTimeException) { false }
    }
}
