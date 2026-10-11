package com.zhiyu.app.domain

object LocalDayStockRules {
    fun view(index: Int): String { require(index in 0..23); return when(index % 4) { 1 -> "portrait"; 2 -> "pov"; else -> "landscape" } }
    fun available(asset: MediaAttachment, tags: Set<String>, day: String, time: String, version: Int): Boolean {
        if ("contains_person" in tags && (version <= 0 || asset.visualVersion != version)) return false
        if ("daily_stock" !in tags) return true
        if (asset.dayId != day || asset.visualVersion != version) return false
        val slot = tags.singleOrNull { it.startsWith("slot_") }?.removePrefix("slot_") ?: return false
        return Regex("(?:[01][0-9]|2[0-3])[0-5][0-9]").matches(slot) && slot <= time.replace(":", "")
    }
}
