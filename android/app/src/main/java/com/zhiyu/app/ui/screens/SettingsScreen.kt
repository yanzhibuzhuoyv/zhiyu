package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.zhiyu.app.BuildConfig
import com.zhiyu.app.ui.AppState
import com.zhiyu.app.ui.ChatViewModel

@Composable fun SettingsScreen(state: AppState, viewModel: ChatViewModel) {
    var confirming by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("你的连接", style = MaterialTheme.typography.headlineSmall)
        InfoCard("服务商", (state.provider?.name ?: state.providerId) + if (state.isDemo) " · 本地演示" else " · Key 已验证")
        InfoCard("模型", state.model)
        InfoCard("后端", state.serverUrl)
        InfoCard("API Key 与数据", "API Key 和会话令牌通过 Android Keystore 加密保存在本机，禁止系统备份。对话与知识库保存在所连接的后端；同一服务商和 Key 可继续访问已有数据。")
        InfoCard("会话恢复", "会话过期或后端重启时，使用已保存的 Key 自动重新验证一次。重新验证也可能产生少量费用；失败会显示原因并返回连接页。")
        val docUrl = state.provider?.docUrl.orEmpty()
        if (docUrl.startsWith("https://")) OutlinedButton(onClick = { uriHandler.openUri(docUrl) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text("查看服务商 API 文档")
        }
        Button(onClick = { confirming = true }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("更换服务商 / 模型 / 后端") }
        TextButton(onClick = { confirming = true }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) { Text("退出并清除本机 Key") }
        Text("知语 ${BuildConfig.VERSION_NAME}${if (BuildConfig.DEBUG) " · 调试版" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false }, title = { Text("退出当前连接？") },
        text = { Text("会清除本机保存的 Key 和会话，返回连接页。后端的对话与知识库会保留。使用同一服务商和 Key 重新连接即可恢复。") },
        confirmButton = { TextButton(onClick = { confirming = false; viewModel.disconnect() }) { Text("退出连接") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } },
    )
}
