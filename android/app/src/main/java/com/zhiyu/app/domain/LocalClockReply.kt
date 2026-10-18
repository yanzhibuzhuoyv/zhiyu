package com.zhiyu.app.domain

import java.time.Instant
import java.time.ZoneId

/** Only a standalone request for the present clock; stories and compound questions stay with the companion. */
object LocalClockReply {
    private val clock = Regex("(?:现在|此刻|当前)?(?:几点(?:钟)?|什么时间|的时间)(?:了)?")
    private val date = Regex("(?:今天|现在)(?:几号|几月几[号日]|的日期|是什么日期)")
    private val weekday = Regex("今天(?:是)?(?:星期几|周几|礼拜几)")
    private val zones = linkedMapOf("北京时间" to "Asia/Shanghai", "北京" to "Asia/Shanghai",
        "东京" to "Asia/Tokyo", "日本" to "Asia/Tokyo", "纽约" to "America/New_York", "伦敦" to "Europe/London", "UTC" to "UTC")
    fun respond(text: String, now: Instant, zone: ZoneId): String? {
        if (text.length > 80 || '\n' in text) return null
        var query = text.trim().trimEnd('？', '?', '。', '！', '!').replace(" ", "")
        query = query.replace(Regex("^(?:请问|请|能不能告诉我|能告诉我|告诉我|你知道)"), "")
            .replace(Regex("(?:吗|呀|啊|呢)$"), "")
        query = query.removePrefix("你那边").removePrefix("我这边").removePrefix("我们这边")
        val requested = zones.keys.firstOrNull { query.startsWith(it) }
        if (requested != null) query = query.removePrefix(requested)
        val local = now.atZone(requested?.let { ZoneId.of(checkNotNull(zones[it])) } ?: zone)
        val day = "星期" + "一二三四五六日"[local.dayOfWeek.value - 1]
        return when {
            clock.matches(query) -> (requested?.let { "${it}现在是" } ?: "现在是") +
                local.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "。"
            date.matches(query) -> "今天是${local.year}年${local.monthValue}月${local.dayOfMonth}日，$day。"
            weekday.matches(query) -> "今天$day。"
            else -> null
        }
    }
}
