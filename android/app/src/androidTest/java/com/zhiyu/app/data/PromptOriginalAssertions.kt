package com.zhiyu.app.data

import org.junit.Assert.assertTrue

/** Accept only the two application time headers, then compare every original character exactly. */
internal fun promptOriginal(content: String): String {
    val stamp = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:Z|[+-]\\d{2}:\\d{2})"
    val current = Regex("\\[本轮设备当前时间：$stamp；[A-Za-z0-9_+:/-]+；当前时段[\\p{IsHan}]{1,8}。" +
        "原话中的报时不改变它；只有明确其他地点/时区才另作区分。]")
    val sent = Regex("\\[本条消息发送时间：$stamp；仅发送时间，非事件时间]")
    var remaining = content
    if (remaining.startsWith("[本轮设备当前时间：")) {
        assertTrue("Unexpected current-time presentation header", current.matches(remaining.substringBefore('\n')))
        remaining = remaining.substringAfter('\n')
    }
    if (remaining.startsWith("[本条消息发送时间：")) {
        assertTrue("Unexpected send-time presentation header", sent.matches(remaining.substringBefore('\n')))
        remaining = remaining.substringAfter('\n')
    }
    return remaining
}
