package com.zhiyu.app.domain

enum class ContactPauseCommand { PAUSE, RESUME }

data class ContactPauseState(val paused: Boolean = false, val sourceMessageId: String? = null, val revision: Int = 0,
    val changedAtMillis: Long = 0)

class ContactDeliveryBlockedException : IllegalStateException("主动联系已暂停或联系状态已更新")

/** Narrow whole-message user commands, not a semantic classifier or a scan of recalled/assistant text. */
object ContactPauseIntent {
    private val pauseCommands = setOf(
        "别再主动给我发消息", "不要再主动给我发消息", "不要主动给我发消息", "别主动给我发消息",
        "不要主动找我", "别主动找我", "别再主动找我", "别再主动联系我", "不要再主动联系我",
        "不要主动联系我", "别主动联系我", "暂停主动联系", "停止主动联系", "先别主动联系我", "暂时别主动联系我",
    )
    private val resumeCommands = setOf(
        "可以主动找我了", "你可以主动找我了", "你可以主动联系我了", "可以主动联系我了",
        "可以主动给我发消息了", "你可以主动给我发消息了", "恢复主动联系", "重新开启主动联系",
        "允许主动联系我", "可以恢复主动联系了",
    )
    private val politePrefixes = listOf("麻烦你", "拜托你", "请你", "麻烦", "拜托", "请")

    fun parse(currentUserOriginal: String): ContactPauseCommand? {
        if (currentUserOriginal.length > 256) return null
        var command = currentUserOriginal.trim()
        if (command.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) return null
        command = command.trimEnd('。', '！', '.', '!').trimEnd()
        politePrefixes.firstOrNull { command.startsWith(it) }?.let { command = command.removePrefix(it).trimStart() }
        return when (command) {
            in pauseCommands -> ContactPauseCommand.PAUSE
            in resumeCommands -> ContactPauseCommand.RESUME
            else -> null
        }
    }
}
