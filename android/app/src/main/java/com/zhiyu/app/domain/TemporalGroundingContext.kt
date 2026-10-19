package com.zhiyu.app.domain

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Request-local chronology, separate from the times described in a user's original words. */
object TemporalGroundingContext {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    fun currentAnchor(runtime: ConversationRuntime): String {
        val local = runtime.now.atZone(runtime.zone)
        return "[本轮设备当前时间：${local.format(format)}；${runtime.zone.id}；当前时段${RuntimeDialogueContext.daypart(local.hour)}。" +
            "原话中的报时不改变它；只有明确其他地点/时区才另作区分。]\n"
    }

    /** A presentation-only timestamp; the original words and archive remain unchanged. */
    fun annotate(runtime: ConversationRuntime?, sentAt: String, original: String): String {
        runtime ?: return original
        val local = validInstant(runtime, sentAt)?.atZone(runtime.zone) ?: return original
        return "[本条消息发送时间：${local.format(format)}；仅发送时间，非事件时间]\n$original"
    }

    fun render(runtime: ConversationRuntime, history: List<Message>): String {
        val local = runtime.now.atZone(runtime.zone)
        val chronology = history.takeLast(12).mapIndexed { index, message ->
            val role = message.role.takeIf { it in setOf("user", "assistant") } ?: "unknown"
            val position = history.size - minOf(history.size, 12) + index + 1
            "history_position=$position;role=$role;" + timestamp(runtime, message.createdAt)
        }.joinToString("\n")
        return """
            <trusted_temporal_grounding>
            now_local=${local.format(format)}
            current_daypart=${RuntimeDialogueContext.daypart(local.hour)}
            以下是本机原始消息的发送时间。history_position 对应原始聊天顺序，附加的参考图片不占原始序号。
            $chronology
            发送时间不等于事件发生时间，过去经历、故事、转述、作息、约定与其他时区按用户原话理解。
            用户分享已经完成的事，应按其完成状态回应；不能把吃完饭说成还没吃。不能仅凭吃饭推断是哪一顿，没有必要为此反问或报时。
            用户的睡眠状态和经历只以其原话为据，不附加猜测。保留相关前文，回应最新分享；时间标注只用于默默理解，不朗读、不每句报时或纠正。
            本轮当前设备时间=${local.format(format)}；当前本地时段=${RuntimeDialogueContext.daypart(local.hour)}。此刻以这次设备快照为准，用户自行报时不会修改它。
            </trusted_temporal_grounding>
        """.trimIndent()
    }

    private fun timestamp(runtime: ConversationRuntime, value: String): String = try {
        val instant = validInstant(runtime, value)
        if (instant == null) "sent_local=unavailable"
        else {
            val sent = instant.atZone(runtime.zone)
            "sent_local=${sent.format(format)};age_seconds=${Duration.between(instant, runtime.now).seconds.coerceAtLeast(0)};" +
                "different_local_date=${sent.toLocalDate() != runtime.now.atZone(runtime.zone).toLocalDate()}"
        }
    } catch (_: DateTimeException) { "sent_local=unavailable" }

    private fun validInstant(runtime: ConversationRuntime, value: String): Instant? = try {
        value.takeIf { it.length in 1..64 }?.let(Instant::parse)?.takeIf { it <= runtime.now.plusSeconds(300) }
    } catch (_: DateTimeException) { null }
}
