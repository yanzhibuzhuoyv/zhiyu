package com.zhiyu.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhiyu.app.domain.Document
import com.zhiyu.app.ui.AppState
import com.zhiyu.app.ui.ChatViewModel

@Composable fun LibraryScreen(state: AppState, viewModel: ChatViewModel) {
    var deleting by remember { mutableStateOf<Document?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importDocument) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("把资料变成对话的依据", style = MaterialTheme.typography.titleLarge)
            Text("导入文档后，开启对话中的「知识检索」。AI 回复会展示实际检索到的引用。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { picker.launch(arrayOf("text/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream")) }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.UploadFile, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.busy == "导入知识库") "正在处理文档…" else "导入文档")
            }
            Text("TXT / Markdown / PDF / DOCX · 单个文件不超过 5 MB\n扫描 PDF 需要先转为可选中文字的文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${state.documents.size} 份文档", style = MaterialTheme.typography.labelLarge)
        }
        if (state.documents.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("知识库等待你的第一份资料", style = MaterialTheme.typography.titleMedium)
                Text("文档与会话保存在后端，按 API Key 身份隔离。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.documents, key = { it.id }) { document ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(document.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text("${formatSize(document.size)} · ${document.chunks} 个片段", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { deleting = document }, enabled = !state.isBusy) { Icon(Icons.Outlined.DeleteOutline, "删除文档 ${document.name}") }
                    }
                }
            }
        }
    }
    deleting?.let { document ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这份资料？") }, text = { Text("「${document.name}」将从知识库永久删除。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteDocument(document.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
private fun formatSize(bytes: Long): String = if (bytes < 1024 * 1024) "${(bytes / 1024).coerceAtLeast(1)} KB" else "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
