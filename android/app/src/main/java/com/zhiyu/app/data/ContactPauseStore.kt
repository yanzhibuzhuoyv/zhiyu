package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.ContactPauseCommand
import com.zhiyu.app.domain.ContactPauseState
import com.zhiyu.app.domain.ContactDeliveryBlockedException
import com.zhiyu.app.domain.ProactiveRules

/** Independent contact pause state; no credentials, notification permission or personality are stored. */
class ContactPauseStore(context: Context, preferencesName: String = "contact_pause_v1",
    private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val preferences = context.getSharedPreferences(validPreferencesName(preferencesName), Context.MODE_PRIVATE)
    private val scope = "${context.applicationInfo.dataDir}/$preferencesName"

    fun read(chatId: String): ContactPauseState = synchronized(lock) {
        ProactiveRules.conversationId(chatId)
        check(failureKey(chatId) !in failedWrites) { "主动联系状态未持久保存，暂不能发送" }
        readRecord(chatId)
    }

    fun apply(chatId: String, command: ContactPauseCommand, sourceMessageId: String): ContactPauseState = synchronized(lock) {
        ProactiveRules.conversationId(chatId)
        require(sourceMessageId.matches(sourceIdPattern)) { "联系命令来源标识无效" }
        val previous = readRecord(chatId)
        val paused = command == ContactPauseCommand.PAUSE
        if (previous.sourceMessageId == sourceMessageId) {
            require(previous.paused == paused) { "同一联系命令来源不能改变命令" }
            if (failureKey(chatId) in failedWrites) persist(chatId, previous)
            return@synchronized previous
        }
        check(previous.revision < Int.MAX_VALUE) { "联系状态版本已用尽" }
        val now = nowMillis()
        require(now in 0..maxExactJsonInteger) { "联系状态时间无效" }
        val next = ContactPauseState(paused, sourceMessageId, previous.revision + 1, maxOf(now, previous.changedAtMillis))
        persist(chatId, next)
        next
    }

    /** Hold only around local append/notification publication, never around a network request. */
    fun <T> withDeliveryAllowed(chatId: String, expectedRevision: Int, block: () -> T): T = synchronized(lock) {
        require(expectedRevision >= 0) { "联系状态版本无效" }
        val state = read(chatId)
        if (state.paused || state.revision != expectedRevision) throw ContactDeliveryBlockedException()
        block()
    }

    private fun readRecord(chatId: String): ContactPauseState {
        try {
            if (!preferences.contains(chatId)) return ContactPauseState()
            val text = checkNotNull(preferences.getString(chatId, null))
            check(text.length <= 1024)
            val record = LocalMemoryStrictJson.parse(text) as? Map<*, *> ?: error("联系状态必须是对象")
            check(record.keys == recordFields && record["schema"] == 1.0)
            val paused = record["paused"] as? Boolean ?: error("联系暂停标记无效")
            val source = record["sourceMessageId"] as? String ?: error("联系命令来源无效")
            check(source.matches(sourceIdPattern))
            val revision = exactInteger(record["revision"], 1L, Int.MAX_VALUE.toLong()).toInt()
            val changedAt = exactInteger(record["changedAtMillis"], 0L, maxExactJsonInteger)
            return ContactPauseState(paused, source, revision, changedAt)
        } catch (failure: Exception) {
            throw IllegalStateException("主动联系状态读取失败，暂不能发送", failure)
        }
    }

    private fun persist(chatId: String, state: ContactPauseState) {
        val json = "{\"schema\":1,\"paused\":${state.paused},\"sourceMessageId\":${LocalMemoryStrictJson.quote(checkNotNull(state.sourceMessageId))}," +
            "\"revision\":${state.revision},\"changedAtMillis\":${state.changedAtMillis}}"
        val key = failureKey(chatId)
        // commit() can update its memory cache even when disk writing fails; keep delivery closed until a successful retry.
        failedWrites.add(key)
        check(preferences.edit().putString(chatId, json).commit()) { "主动联系状态保存失败，暂不能发送" }
        failedWrites.remove(key)
    }

    private fun failureKey(chatId: String) = "$scope:$chatId"

    private companion object {
        val lock = Any()
        val failedWrites = mutableSetOf<String>()
        val sourceIdPattern = Regex("[A-Za-z0-9_-]{1,160}")
        val recordFields = setOf("schema", "paused", "sourceMessageId", "revision", "changedAtMillis")
        const val maxExactJsonInteger = 9007199254740991L

        fun validPreferencesName(name: String): String {
            require(name.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "联系状态存储标识无效" }
            return name
        }

        fun exactInteger(value: Any?, minimum: Long, maximum: Long): Long {
            val number = value as? Double ?: error("联系状态数值无效")
            check(number.isFinite() && number >= minimum.toDouble() && number <= maximum.toDouble())
            val integer = number.toLong()
            check(integer.toDouble() == number)
            return integer
        }
    }
}
