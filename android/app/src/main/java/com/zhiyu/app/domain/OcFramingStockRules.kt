package com.zhiyu.app.domain

object OcFramingStockRules {
    fun tags(policy: OcFramingPolicy): List<String> {
        val hash = OcFramingRules.hash(policy)
        return listOf("frame0_${hash.take(32)}", "frame1_${hash.takeLast(32)}",
            if (OcFramingRules.permits(policy, true)) "frame_full" else "frame_crop")
    }
    fun permits(policy: OcFramingPolicy?, tags: Set<String>, containsPerson: Boolean): Boolean {
        if (policy == null || !containsPerson) return true
        val expected = tags(policy)
        return listOf("frame0_", "frame1_").all { prefix ->
            tags.singleOrNull { it.startsWith(prefix) } == expected.single { it.startsWith(prefix) }
        } && tags.intersect(setOf("frame_full", "frame_crop")) == setOf(expected.last())
    }
}
