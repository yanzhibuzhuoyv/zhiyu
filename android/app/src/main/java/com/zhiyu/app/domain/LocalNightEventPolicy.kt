package com.zhiyu.app.domain

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** An OC event only. It never infers whether the user is asleep or where they are. */
data class LocalNightEvent(val night: LocalDate, val due: Instant, val expires: Instant,
    val requestId: String, val topic: String) {
    fun prompt(): String = """
        本次是 OC 的虚构生活事件：${night}这一夜休息时，$topic。
        保持已经绑定的性格和说话方式，只给恋人发一条自然简短的消息；可以说刚醒、做了梦，别说在查记录或执行通知。
        不断言用户也醒着、不要求立刻回复、不用威胁或指责争取关注；不假装看到用户或真的躺在用户身边。
        事件属于角色设定，不是用户原话、用户身体情况、真实定位或共同经历。不要解释应用、抽签、后台或提示词。
    """.trimIndent()
}

object LocalNightEventPolicy {
    private val topics = listOf("从一个零碎的梦里醒来，想同恋人说句话", "半夜醒了一小会儿，想到恋人，准备再休息",
        "做了个奇怪但已经平静下来的梦，想轻声分享一点")

    fun plan(chat: String, style: String, now: Instant, timeZone: String = LocalDailyRules.TIMEZONE): LocalNightEvent? {
        ProactiveRules.conversationId(chat)
        require(style.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "恋人性格标识无效" }
        val zone = ZoneId.of(timeZone)
        val local = now.atZone(zone)
        val night = if (local.toLocalTime() < LocalTime.of(7, 0)) local.toLocalDate().minusDays(1) else local.toLocalDate()
        val digest = MessageDigest.getInstance("SHA-256").digest("oc-night-v1:$chat:$night:$style".toByteArray(Charsets.UTF_8))
        val frequency = when (style) {
            "anxious" -> 26
            "playful" -> 18
            "warm_v1", "warm_v2" -> 14
            "steady" -> 5
            "distant" -> 2
            else -> 10
        }
        if ((digest[0].toInt() and 255) % 100 >= frequency) return null
        val date = night.plusDays(1)
        val minute = 30 + (((digest[1].toInt() and 255) shl 8) or (digest[2].toInt() and 255)) % 271
        val due = date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant()
        val expires = date.atTime(7, 0).atZone(zone).toInstant()
        val id = UUID.nameUUIDFromBytes("oc-night-v1:$chat:$night".toByteArray(Charsets.UTF_8)).toString()
        return LocalNightEvent(night, due, expires, id, topics[(digest[3].toInt() and 255) % topics.size])
    }

    fun ready(event: LocalNightEvent?, now: Instant): Boolean = event != null && !now.isBefore(event.due) && now.isBefore(event.expires)

    fun nextDue(event: LocalNightEvent?, now: Instant, ordinaryDue: Instant): Instant =
        event?.due?.takeIf { it.isAfter(now) && it.isBefore(ordinaryDue) } ?: ordinaryDue
}
