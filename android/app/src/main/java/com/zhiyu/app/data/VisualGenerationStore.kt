package com.zhiyu.app.data

import android.content.Context
import java.util.UUID
import org.json.JSONObject

data class PendingVisualGeneration(val id: String, val description: String)

/** Persistent request identity prevents spending a second opportunity after a lost response or restart. */
class VisualGenerationStore(context: Context, name: String = "visual_generation_pending") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    companion object { private val lock = Any() }
    private fun key(chat: String): String {
        require(Regex("[a-f0-9]{32}").matches(chat)) { "恋人会话编号无效" }
        return "request_$chat"
    }
    fun read(chat: String): PendingVisualGeneration? = synchronized(lock) {
        val stored = prefs.getString(key(chat), null) ?: return@synchronized null
        try {
            val value = JSONObject(stored)
            val id = value.getString("id")
            UUID.fromString(id)
            val description = value.getString("description")
            require(description.isNotBlank() && description.length <= 1000)
            PendingVisualGeneration(id, description)
        } catch (error: Exception) { throw IllegalStateException("上一次外观生成的状态无法读取，请检查应用数据", error) }
    }
    fun begin(chat: String, description: String): PendingVisualGeneration = synchronized(lock) {
        require(description.isNotBlank() && description.length <= 1000) { "外观描述应为 1 至 1000 字" }
        read(chat)?.let {
            require(it.description == description) { "上一次生成结果尚未确认，请先用原描述重试" }
            return@synchronized it
        }
        val value = PendingVisualGeneration(UUID.randomUUID().toString(), description)
        check(prefs.edit().putString(key(chat), JSONObject().put("id", value.id).put("description", description).toString()).commit()) { "外观生成请求未保存" }
        value
    }
    fun complete(chat: String, request: String) = synchronized(lock) {
        if (read(chat)?.id == request) check(prefs.edit().remove(key(chat)).commit()) { "外观生成状态未保存" }
    }
}
