package com.zhiyu.app.ui.screens

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import com.zhiyu.app.ui.AppState
import kotlinx.coroutines.flow.first

/** IME belongs to ChatScreen; resizing only follows the tail if the user was already there. */
@Composable internal fun FollowChatTimeline(state: AppState, list: LazyListState, viewportHeight: Int, fontSizeSp: Int) {
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var followingTail by rememberSaveable { mutableStateOf(true) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) {
        snapshotFlow { list.canScrollForward }.collect { canScrollForward ->
            if (dragging || !canScrollForward) followingTail = !canScrollForward
        }
    }
    val firstId = state.messages.firstOrNull()?.id
    val lastId = state.messages.lastOrNull()?.id
    val pending = state.pendingMessage != null || state.pendingImage != null
    val typing = pending || state.busy in listOf("AI 正在输入", "AI 正在思考")
    val count = 2 + state.messages.size + (if (pending) 1 else 0) + (if (typing) 1 else 0)
    LaunchedEffect(pending) { if (pending) followingTail = true }
    LaunchedEffect(state.viewingOlder, firstId) {
        if (state.viewingOlder && !pending) list.scrollToItem(0)
    }
    LaunchedEffect(state.viewingOlder, firstId, lastId, pending, typing, imeBottom, viewportHeight, fontSizeSp, followingTail) {
        if (state.messages.isEmpty() && !pending) return@LaunchedEffect
        if (state.viewingOlder && !pending) return@LaunchedEffect
        if (!followingTail) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.totalItemsCount }.first { it >= count }
        withFrameNanos { }
        list.scrollToItem(count - 1)
    }
}
