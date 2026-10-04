package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
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
    LaunchedEffect(state.messages.size, state.pendingMessage, state.selectedId) {
        val count = state.messages.size + if (state.pendingMessage != null) 2 else 0
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.selected?.model ?: state.model, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(if (state.isDemo) "本地演示 · 无云端 AI 请求" else state.provider?.name ?: state.providerId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("知识检索", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.selected?.useRag ?: state.defaultRag, onCheckedChange = viewModel::setRag, enabled = !state.isBusy, modifier = Modifier.testTag("rag_toggle"))
        }
        HorizontalDivider()
        if (state.messages.isEmpty() && state.pendingMessage == null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(20.dp))
                Text("从一个问题开始", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                Text("聊想法、整理思路，或围绕知识库中的资料提问。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                SuggestionChip(onClick = { viewModel.draft("请根据我的知识库，总结主要内容。") }, enabled = !state.isBusy, label = { Text("帮我梳理知识库") })
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(state.messages, key = { it.id }) { MessageBubble(it) }
                state.pendingMessage?.let { pending ->
                    item { PendingBubble(pending) }
                    item {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("AI 正在思考…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = state.draft, onValueChange = viewModel::draft, modifier = Modifier.weight(1f).testTag("message_draft"), placeholder = { Text("写下你的问题…") }, maxLines = 5, enabled = !state.isBusy)
                FilledIconButton(onClick = viewModel::send, enabled = !state.isBusy && state.draft.isNotBlank(), modifier = Modifier.size(52.dp)) { Icon(Icons.Outlined.Send, "发送消息") }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable private fun MessageBubble(message: Message) {
    val user = message.role == "user"
    val clipboard = LocalClipboardManager.current
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(if (user) "你" else "知语", Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.large, color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.widthIn(max = 640.dp)) {
            Column(Modifier.padding(16.dp)) {
                SelectionContainer { Text(message.content, style = MaterialTheme.typography.bodyLarge) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (message.sources.isNotEmpty()) TextButton(onClick = { expanded = !expanded }) {
                        Icon(Icons.Outlined.Description, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${message.sources.size} 条知识引用")
                    }
                    Spacer(Modifier.weight(1f))
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
