package com.zhiyu.app.ui

import com.zhiyu.app.domain.MemoryEntry

data class MemoryPanelState(
    val open: Boolean = false, val entries: List<MemoryEntry> = emptyList(),
    val hasMore: Boolean = false, val nextBefore: String? = null, val viewingOlder: Boolean = false,
    val automaticAnnotations: Boolean = false, val focusId: String? = null, val selected: MemoryEntry? = null,
) {
    fun updated(entry: MemoryEntry) = copy(
        entries = entries.map { if (it.messageId == entry.messageId) entry else it },
        selected = if (focusId == entry.messageId) entry else selected,
    )
}
