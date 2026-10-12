package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.data.local.qwen.LocalQwenBackgrounds
import com.zhiyu.app.domain.LocalDailySlot

class QwenSceneBackgroundSource(context: Context) : LocalSceneBackgroundSource {
    private val qwen = LocalQwenBackgrounds(context.applicationContext)
    override fun hasInstalledPack() = qwen.hasInstalledPack()
    override suspend fun get(chat: String, day: String, version: Int, slot: LocalDailySlot) = qwen.get(chat, day, version, slot)
    override suspend fun reviewComposite(chat: String, day: String, version: Int, slot: LocalDailySlot,
        png: ByteArray, referenceOriginal: ByteArray?) = qwen.reviewComposite(chat, day, version, slot, png, referenceOriginal)
}
