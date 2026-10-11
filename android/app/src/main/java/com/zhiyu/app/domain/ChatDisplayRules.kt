package com.zhiyu.app.domain

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

data class ChatTypographySettings(val fontSizeSp: Int = 16) {
    init { require(fontSizeSp in 14..26) { "聊天字号应在 14 到 26 之间" } }
}

/** Display only: the saved original timestamp and message are never rewritten. */
object ChatDisplayRules {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA)
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss", Locale.CHINA)
    const val NEW_SECTION_SECONDS = 300L

    fun messageTime(savedTime: String, zone: ZoneId = ZoneId.systemDefault()): String? =
        instant(savedTime)?.atZone(zone)?.format(timeFormat)

    fun separator(previousTime: String?, savedTime: String, zone: ZoneId = ZoneId.systemDefault()): String? {
        val current = instant(savedTime) ?: return null
        if (previousTime == null) return current.atZone(zone).format(dateFormat)
        val previous = instant(previousTime) ?: return null
        if (current.isBefore(previous)) return null
        val dateChanged = previous.atZone(zone).toLocalDate() != current.atZone(zone).toLocalDate()
        val longGap = Duration.between(previous, current).seconds >= NEW_SECTION_SECONDS
        return if (dateChanged || longGap) current.atZone(zone).format(dateFormat) else null
    }

    private fun instant(value: String): Instant? {
        val text = value.trim()
        if (text.length in listOf(10, 13) && text.all(Char::isDigit)) {
            val number = text.toLongOrNull() ?: return null
            return if (text.length == 10) Instant.ofEpochSecond(number) else Instant.ofEpochMilli(number)
        }
        return try { OffsetDateTime.parse(text).toInstant() }
        catch (_: DateTimeParseException) { null }
    }
}
