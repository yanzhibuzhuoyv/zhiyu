package com.zhiyu.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zhiyu.app.BuildConfig
import com.zhiyu.app.ui.AppState
import com.zhiyu.app.ui.ChatViewModel

@Composable fun ConnectionScreen(state: AppState, viewModel: ChatViewModel) {
    var showKey by remember { mutableStateOf(false) }
    var providersOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.padding(16.dp).size(36.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text("知语", style = MaterialTheme.typography.displaySmall)
        Text("连接你的 AI，聊出新的可能。", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("使用自己的 API Key 进入，无需注册账号。", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.serverUrl, onValueChange = viewModel::serverUrl, label = { Text("后端地址") },
            placeholder = { Text(if (BuildConfig.DEBUG) "http://10.0.2.2:8790" else "https://你的后端域名") },
            modifier = Modifier.fillMaxWidth().testTag("server_url"), enabled = !state.isBusy, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = { Text(if (BuildConfig.DEBUG) "模拟器默认地址；真机请输入电脑局域网 IP 或 HTTPS 域名。" else "请输入部署后的 HTTPS 后端地址。") },
        )
        OutlinedButton(onClick = viewModel::loadProviders, enabled = !state.isBusy && state.serverUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Link, null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.busy == "检测后端") "正在检测…" else "连接后端 · 获取服务商")
        }
        if (state.providers.isNotEmpty()) {
            Box {
                OutlinedButton(onClick = { providersOpen = true }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth().testTag("provider_selector")) {
                    Text(state.provider?.name ?: "选择服务商", Modifier.weight(1f))
                    Icon(Icons.Outlined.ExpandMore, null)
                }
                DropdownMenu(expanded = providersOpen, onDismissRequest = { providersOpen = false }) {
                    state.providers.forEach { provider ->
                        DropdownMenuItem(text = { Text(provider.name) }, onClick = { viewModel.provider(provider.id); providersOpen = false })
                    }
                }
            }
            OutlinedTextField(
                value = state.model, onValueChange = viewModel::model, label = { Text("模型或接入点 ID") },
                singleLine = true, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth().testTag("model_id"),
                supportingText = { Text(state.provider?.modelHint.orEmpty()) },
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.provider?.models.orEmpty().forEach { model ->
                    SuggestionChip(onClick = { viewModel.model(model) }, label = { Text(model) }, enabled = !state.isBusy)
                }
            }
            OutlinedTextField(
                value = state.apiKey, onValueChange = viewModel::apiKey, label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth().testTag("api_key"), enabled = !state.isBusy, singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "隐藏 Key" else "显示 Key") } },
            )
            if (state.isDemo) {
                InfoCard("演示模式", "使用 demo-key 进入。仅验证本地功能，不会请求云端模型，也不会产生真实 AI 回复。")
            } else {
                InfoCard("关于连接", "Key 将通过上方指定的后端验证，仅加密保存在本机。验证会请求一次极短的真实模型输出，可能产生少量费用。后端是 Key 的受信接收方，请使用你信任的地址。")
            }
            if (state.serverUrl.startsWith("http://")) Text("当前使用调试 HTTP 连接，传输未加密。远程部署请使用 HTTPS。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = viewModel::connect, enabled = !state.isBusy && state.apiKey.isNotBlank() && state.model.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp).testTag("login")) {
                if (state.busy == "验证 API Key") CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.ArrowForward, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.busy == "验证 API Key") "正在验证…" else "开始对话")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("知语 · 你的私人 AI 对话空间", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun InfoCard(title: String, content: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
