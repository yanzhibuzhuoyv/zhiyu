package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import org.json.JSONObject

internal fun JSONObject.memoryEntry() = MemoryRules.entry(MemoryEntry(
    memoryString("message_id"), memoryString("role"), memoryString("text"), memoryString("created_at"),
    memoryInt("char_length"), memoryString("tier"), memoryString("index_state"), memoryInt("revision"),
    memoryBoolean("pinned"), memoryString("reason"),
    if (!has("manual_tier") || isNull("manual_tier")) null else memoryString("manual_tier"),
    if (has("can_confirm_independent")) memoryBoolean("can_confirm_independent") else false,
    if (has("recognition_mode")) memoryString("recognition_mode") else "none",
    if (has("recognition_status")) memoryString("recognition_status") else "unavailable",
))

internal fun JSONObject.memoryPage(before: String?) = MemoryRules.page(MemoryPage(
    getJSONArray("messages").mapObjects { it.memoryEntry() }, memoryBoolean("has_more"),
    if (isNull("next_before")) null else memoryString("next_before"), memoryBoolean("automatic_annotations"),
), before)

internal fun MemoryChange.json(): JSONObject {
    require(expectedRevision >= 0) { "记忆版本不正确" }
    val json = JSONObject().put("expected_revision", expectedRevision)
    tier?.let { json.put("tier", it) }
    indexState?.let { json.put("index_state", it) }
    if (confirmIndependent) json.put("confirm_independent", true)
    return json
}

private fun JSONObject.memoryString(key: String): String = get(key).let {
    require(it is String) { "记忆响应字段不正确" }; it
}
private fun JSONObject.memoryBoolean(key: String): Boolean = get(key).let {
    require(it is Boolean) { "记忆响应状态不正确" }; it
}
private fun JSONObject.memoryInt(key: String): Int {
    val value = get(key)
    require(value is Int || value is Long) { "记忆响应版本或长度不正确" }
    val number = (value as Number).toLong()
    require(number in 0..Int.MAX_VALUE.toLong()) { "记忆响应版本或长度超过范围" }
    return number.toInt()
}
