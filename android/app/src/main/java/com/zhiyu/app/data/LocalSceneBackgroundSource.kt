package com.zhiyu.app.data

import android.graphics.Bitmap
import com.zhiyu.app.domain.LocalDailySlot

class LocalSceneBackgroundUnavailable(val reason: String) : IllegalStateException("这张生活画面尚不可用") {
    init { require(reason in setOf("terminal", "unknown", "stale")) }
}

/** get queues a single heavy job or returns verified pixels; an ordinary stock worker never waits for sampling. */
interface LocalSceneBackgroundSource {
    fun hasInstalledPack(): Boolean
    suspend fun get(chat: String, day: String, version: Int, slot: LocalDailySlot): Bitmap?
    suspend fun reviewComposite(chat: String, day: String, version: Int, slot: LocalDailySlot,
        png: ByteArray, referenceOriginal: ByteArray?): Boolean
}
