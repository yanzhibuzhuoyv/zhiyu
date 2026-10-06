package com.zhiyu.app.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.zhiyu.app.domain.Message
import com.zhiyu.app.ui.AppState
import com.zhiyu.app.ui.ChatViewModel

@Composable fun ChatScreen(state: AppState, viewModel: ChatViewModel) {
    val list = rememberLazyListState()
    val firstId = state.messages.firstOrNull()?.id
    val lastId = state.messages.lastOrNull()?.id
    LaunchedEffect(state.viewingOlder, if (state.viewingOlder) firstId else lastId, state.messages.size, state.pendingMessage) {
        if (state.viewingOlder && state.pendingMessage == null) list.scrollToItem(0)
        else {
            val count = state.messages.size + 1 + if (state.pendingMessage != null) 2 else 0
            if (state.messages.isNotEmpty() || state.pendingMessage != null) list.animateScrollToItem(count - 1)
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(if (state.isDemo) "本地演示 · 无云端 AI 请求" else "同一段聊天，慢慢了解彼此", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        if (state.messages.isEmpty() && state.pendingMessage == null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(20.dp))
                Text("我在，慢慢说。", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                Text("今天发生了什么，或有什么想放在心里的话？\n我们从这里开始，慢慢熟悉彼此。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                SuggestionChip(onClick = { viewModel.draft("今天我想和你分享一件事。") }, enabled = !state.isBusy, label = { Text("先聊聊今天") })
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_messages"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "history_controls") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.viewingOlder) TextButton(onClick = viewModel::refresh, enabled = !state.isBusy, modifier = Modifier.testTag("recent_messages")) { Text("回到最近消息") }
                        if (state.hasOlder) TextButton(onClick = viewModel::loadOlder, enabled = !state.isBusy, modifier = Modifier.testTag("older_messages")) { Text("更早的消息") }
                        else Text("这段聊天从这里开始", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message, !state.isBusy) { viewModel.openMessageMemory(message.id) }
                }
                state.pendingMessage?.let { pending ->
                    item { PendingBubble(pending) }
                    item {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("正在想怎么回应你…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = state.draft, onValueChange = viewModel::draft, modifier = Modifier.weight(1f).testTag("message_draft"), placeholder = { Text("想和你说…") }, maxLines = 5, enabled = !state.isBusy)
                FilledIconButton(onClick = viewModel::send, enabled = !state.isBusy && state.draft.isNotBlank(), modifier = Modifier.size(52.dp)) { Icon(Icons.AutoMirrored.Outlined.Send, "发送消息") }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable private fun MessageBubble(message: Message, enabled: Boolean, memory: () -> Unit) {
    val user = message.role == "user"
    val clipboard = LocalClipboardManager.current
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(if (user) "你" else "知语", Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.large, color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(max = 640.dp).testTag("message_${message.id}")
                .combinedClickable(enabled = enabled, onClick = {}, onLongClickLabel = "管理这句原话的记忆", onLongClick = memory)) {
            Column(Modifier.padding(16.dp)) {
                Text(message.content, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (message.sources.isNotEmpty()) TextButton(onClick = { expanded = !expanded }) {
                        Icon(Icons.Outlined.Description, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${message.sources.size} 条知识引用")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = memory, enabled = enabled, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.BookmarkBorder, "管理消息记忆", Modifier.size(16.dp)) }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.ContentCopy, "复制消息", Modifier.size(16.dp)) }
                }
                if (expanded) message.sources.forEach { source ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(source.name, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(source.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable private fun PendingBubble(text: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Text("正在发送", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) { Text(text, Modifier.padding(16.dp)) }
    }
}
