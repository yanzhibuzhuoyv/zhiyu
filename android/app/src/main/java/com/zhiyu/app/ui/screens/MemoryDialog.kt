package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zhiyu.app.domain.MemoryEntry
import com.zhiyu.app.ui.AppState
import com.zhiyu.app.ui.ChatViewModel
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun MemoryDialog(state: AppState, viewModel: ChatViewModel) {
    Dialog(onDismissRequest = viewModel::closeMemory) {
        Surface(shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f).testTag("memory_dialog")) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("我们的记忆", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = viewModel::closeMemory) { Icon(Icons.Outlined.Close, "关闭记忆") }
                }
                Text("每句原话独立保留，归档后仍可找回。", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("启用语义标注时，会使用所选 AI 额外识别一次，可能产生额外费用；判断可手动调整。",
                    Modifier.padding(horizontal = 20.dp).testTag("memory_semantic_cost"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.testTag("memory_error")) {
                        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = viewModel::dismissError) { Icon(Icons.Outlined.Close, "关闭记忆提示") }
                        }
                    }
                }
                val selected = state.memory.selected
                when {
                    selected != null -> MemoryDetails(selected, state.isBusy, viewModel, Modifier.weight(1f))
                    state.memory.focusId != null -> Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (state.isBusy) "正在读取这句原话…" else "暂时无法读取这句原话")
                        if (!state.isBusy) TextButton(onClick = { viewModel.openMessageMemory(state.memory.focusId) }) { Text("重试") }
                    }
                    else -> MemoryList(state, viewModel, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable private fun MemoryList(state: AppState, viewModel: ChatViewModel, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth().testTag("memory_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (!state.memory.automaticAnnotations) Text("自动识别未启用；你仍可为每句原话选择层级。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.memory.viewingOlder) TextButton(onClick = viewModel::recentMemory, enabled = !state.isBusy,
                modifier = Modifier.testTag("recent_memories")) { Text("回到最近记忆") }
        }
        items(state.memory.entries, key = { it.messageId }) { entry ->
            OutlinedCard(onClick = { viewModel.openMessageMemory(entry.messageId) }, enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth().testTag("memory_entry_${entry.messageId}")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${author(entry)} · ${savedTime(entry.createdAt)}", style = MaterialTheme.typography.labelSmall)
                    Text(entry.text, maxLines = 4, style = MaterialTheme.typography.bodyMedium)
                    Text("${tierLabel(entry.tier)} · ${modeLabel(entry)} · ${indexLabel(entry)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MemoryRecognitionInfo(entry)
                }
            }
        }
        item {
            if (state.memory.entries.isEmpty() && !state.isBusy) Text("还没有原话记忆。开始聊天后会逐句保存。")
            if (state.memory.hasMore) TextButton(onClick = viewModel::olderMemory, enabled = !state.isBusy,
                modifier = Modifier.testTag("older_memories")) { Text("更早的记忆") }
        }
    }
}

@Composable private fun MemoryDetails(entry: MemoryEntry, busy: Boolean, viewModel: ChatViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = viewModel::backToMemoryList, enabled = !busy, modifier = Modifier.testTag("memory_back")) { Text("查看记忆列表") }
        Text("${author(entry)} · ${savedTime(entry.createdAt)}", style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(entry.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("memory_original")) }
        if (entry.charLength > entry.text.codePointCount(0, entry.text.length)) {
            Text("这里显示原文节选（原文共 ${entry.charLength} 字），完整原话保留在聊天中。", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text("${tierLabel(entry.tier)} · ${modeLabel(entry)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("memory_status"))
        MemoryRecognitionInfo(entry, Modifier.testTag("memory_recognition"))
        if (entry.recognitionStatus == "partial") Text("部分内容未识别，请核对判断或手动调整。", style = MaterialTheme.typography.bodySmall)
        Text("中期保留日常与近期约定；长期关注反复表达；永久重视稳定信息。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("mid", "long", "permanent").forEach { tier ->
                FilterChip(selected = entry.manualTier == tier, onClick = { viewModel.changeMemoryTier(tier) }, enabled = !busy,
                    label = { Text(tierLabel(tier)) }, modifier = Modifier.testTag("memory_tier_$tier"))
            }
        }
        TextButton(onClick = { viewModel.changeMemoryTier("auto") }, enabled = !busy && entry.manualTier != null,
            modifier = Modifier.testTag("memory_auto")) { Text("恢复自动判断") }
        Text(indexLabel(entry), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("memory_index_state"))
        if (entry.indexState == "cold") {
            OutlinedButton(onClick = viewModel::restoreMemory, enabled = !busy, modifier = Modifier.testTag("memory_restore")) { Text("恢复活跃记忆") }
        } else {
            val protected = entry.pinned || entry.tier != "mid"
            OutlinedButton(onClick = viewModel::archiveMemory, enabled = !busy && !protected,
                modifier = Modifier.testTag("memory_archive")) { Text("归档这句原话") }
            if (protected) Text("长期、永久和仍需记住的约定受到保护，暂不归档。", style = MaterialTheme.typography.bodySmall)
        }
        if (entry.canConfirmIndependent && entry.role == "user") {
            OutlinedButton(onClick = viewModel::confirmIndependentMemory, enabled = !busy,
                modifier = Modifier.testTag("memory_independent")) { Text("确认这是一次独立表达") }
            Text("仅在这次是你重新表达的想法、并非复制或转述时确认。原话不会合并。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun author(entry: MemoryEntry) = if (entry.role == "user") "你说" else "恋人回应"
private fun tierLabel(tier: String) = when (tier) { "permanent" -> "永久"; "long" -> "长期"; else -> "中期" }
private fun modeLabel(entry: MemoryEntry) = if (entry.manualTier == null) "自动判断" else "手动指定"
private fun indexLabel(entry: MemoryEntry) = if (entry.indexState == "cold") "已归档，原话完整保留" else "活跃记忆"
@Composable internal fun MemoryRecognitionInfo(entry: MemoryEntry, modifier: Modifier = Modifier) {
    Text(recognitionLabel(entry), modifier, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (needsRecognitionHelp(entry)) Text("原话已保留，层级可手动确认", style = MaterialTheme.typography.bodySmall)
}
private fun recognitionLabel(entry: MemoryEntry) = when {
    entry.recognitionStatus in setOf("pending", "running") -> "正在整理记忆，聊天可以继续"
    entry.recognitionStatus == "waiting_auth" -> "重新连接后继续整理记忆"
    entry.recognitionMode == "semantic" && entry.recognitionStatus in setOf("ok", "partial") -> "语义识别"
    entry.recognitionMode == "literal" && entry.recognitionStatus == "literal" -> "规则识别"
    else -> "未识别"
}
private fun needsRecognitionHelp(entry: MemoryEntry) = entry.recognitionStatus in setOf("failed", "unavailable", "skipped_large")
private fun savedTime(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrElse { value }
