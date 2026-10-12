package com.zhiyu.app.domain

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LocalDailySlot(val time: String, val activity: String, val tags: List<String>)
data class LocalDailyScene(val dayId: String, val sceneId: String, val visualVersion: Int,
    val slots: List<LocalDailySlot>, val current: LocalDailySlot,
    val timezone: String = "Asia/Shanghai", val fictional: Boolean = true)

/** A fictional OC plan; it describes neither the user's location nor physical events. */
object LocalDailyRules {
    const val TIMEZONE = "Asia/Shanghai"
    private val zone = ZoneId.of(TIMEZONE)
    private val mornings = listOf(
        slot("07:30", "早餐和清晨的光", "food", "morning"), slot("08:00", "做一份热早餐", "food", "morning"),
        slot("08:30", "整理窗边的绿植", "object", "morning"), slot("07:45", "喝点热饮慢慢醒来", "food", "morning"),
        slot("08:15", "去看看清晨的云", "landscape", "morning"), slot("08:45", "整理书桌", "object", "morning"),
        slot("07:50", "听音乐吃早餐", "food", "morning"), slot("08:20", "给绿植浇水", "object", "morning"))
    private val afternoons = listOf(
        slot("12:00", "午间散步", "landscape", "afternoon"), slot("12:30", "看看路边的小猫", "cat", "landscape", "afternoon"),
        slot("12:15", "读几页书", "object", "afternoon"), slot("13:00", "给自己做点午餐", "food", "afternoon"),
        slot("12:45", "看看窗外的树影", "landscape", "afternoon"), slot("13:15", "画一张小画", "object", "afternoon"),
        slot("12:20", "和小猫待一会儿", "cat", "afternoon"), slot("13:30", "整理书架", "object", "afternoon"))
    private val lateMornings = listOf(
        slot("10:00", "给花瓶换水", "object", "morning"), slot("10:15", "靠窗写几句随想", "object", "morning"),
        slot("10:30", "找一首新歌", "object", "morning"), slot("10:45", "记下一个小灵感", "object", "morning"),
        slot("11:00", "晾好刚洗的杯子", "object", "morning"), slot("10:20", "看看阳台的花", "object", "morning"),
        slot("11:15", "给自己切盘水果", "food", "morning"), slot("10:50", "试着折一张纸", "object", "morning"))
    private val lateAfternoons = listOf(
        slot("15:00", "泡一杯下午茶", "food", "afternoon"), slot("14:30", "看看小店橱窗", "landscape", "afternoon"),
        slot("15:30", "看看远处的天空", "landscape", "afternoon"), slot("16:00", "翻翻喜欢的画册", "object", "afternoon"),
        slot("14:45", "尝一块小点心", "food", "afternoon"), slot("15:15", "收集一片好看的落叶", "object", "afternoon"),
        slot("15:45", "看看慢慢开放的花蕾", "object", "afternoon"), slot("14:50", "剪一朵小纸花", "object", "afternoon"))
    private val evenings = listOf(
        slot("18:30", "准备晚餐", "food", "evening"), slot("18:00", "晚霞散步", "landscape", "evening"),
        slot("17:30", "看看傍晚的云", "landscape", "evening"), slot("18:15", "看看街角的灯", "landscape", "evening"),
        slot("18:45", "煮一碗热汤", "food", "evening"), slot("17:45", "陪小猫玩一会儿", "cat", "evening"),
        slot("19:00", "吃晚餐听首歌", "food", "evening"), slot("18:20", "整理今天拍的风景", "object", "evening"))
    private val nights = listOf(
        slot("21:00", "安静阅读", "object", "night"), slot("21:30", "整理一天", "object", "night"),
        slot("21:15", "安静听歌", "object", "night"), slot("22:00", "写一点日记", "object", "night"),
        slot("20:45", "窝着看看窗外", "landscape", "night"), slot("21:45", "收好手边的书", "object", "night"),
        slot("22:15", "和小猫道晚安", "cat", "night"), slot("21:20", "泡杯热饮准备休息", "food", "night"))
    private val afterDinners = listOf(
        slot("19:45", "收拾好餐桌", "object", "evening"), slot("20:00", "在阳台吹会儿晚风", "landscape", "evening"),
        slot("19:40", "在纸上试几个颜色", "object", "evening"), slot("20:15", "给小猫梳梳毛", "cat", "evening"),
        slot("20:10", "给花草看一眼", "object", "evening"), slot("19:50", "看看城市里的灯光", "landscape", "evening"),
        slot("20:20", "跟着旋律哼一小段", "object", "evening"), slot("20:30", "给书夹好书签", "object", "evening"))
    private fun varied(digest: ByteArray): List<LocalDailySlot> = listOf(slot("00:00", "休息", "night")) +
        listOf(mornings, lateMornings, afternoons, lateAfternoons, evenings, afterDinners, nights)
            .mapIndexed { index, pool -> pool[(digest[index].toInt() and 255) % pool.size] }
    fun scene(chat: String, visualVersion: Int, now: Instant): LocalDailyScene {
        MediaRules.path(chat)
        require(visualVersion >= 0)
        val day = dayId(now)
        val digest = MessageDigest.getInstance("SHA-256").digest("$chat:$day".toByteArray())
        val slots = varied(digest)
        val identity = digest.take(16).joinToString("") { "%02x".format(it) }
        val current = current(slots, now)
        return LocalDailyScene(day, identity, visualVersion, slots, current)
    }
    fun plannedScene(chat: String, visualVersion: Int, day: String, now: Instant): LocalDailyScene {
        val date = java.time.LocalDate.parse(day)
        require(date.toString() == day) { "日轨迹日期无效" }
        val candidate = scene(chat, visualVersion, date.atStartOfDay(zone).toInstant())
        return if (day == dayId(now)) candidate.copy(current = current(candidate.slots, now)) else candidate
    }
    fun revision(scene: LocalDailyScene): String {
        val canonical = buildString {
            fun token(value: String) { append(value.length).append(':').append(value) }
            token(scene.dayId); token(scene.sceneId); token(scene.visualVersion.toString()); token(scene.timezone)
            token(scene.slots.size.toString())
            scene.slots.forEach { slot -> token(slot.time); token(slot.activity); token(slot.tags.size.toString()); slot.tags.forEach(::token) }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun current(slots: List<LocalDailySlot>, now: Instant): LocalDailySlot {
        require(slots.size in 1..24 && slots.first().time == "00:00" &&
            slots.zipWithNext().all { (a, b) -> a.time < b.time })
        require(slots.all { slot -> Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(slot.time) &&
            slot.activity.length in 1..200 && slot.activity.none { it.isISOControl() } &&
            slot.tags.size in 1..16 && slot.tags.all { Regex("[a-z][a-z0-9_]{0,31}").matches(it) } })
        val time = now.atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        return slots.last { it.time <= time }
    }
    fun dayId(now: Instant): String = now.atZone(zone).toLocalDate().toString()
    fun nextMidnight(now: Instant): Instant = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    fun mediaAffinity(kind: String, tags: Set<String>, current: LocalDailySlot): Int =
        if (kind == "image") tags.intersect(current.tags.toSet()).size else 0
    private fun slot(time: String, activity: String, vararg tags: String) = LocalDailySlot(time, activity, tags.toList())
}
