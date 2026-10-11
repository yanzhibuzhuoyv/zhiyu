package com.zhiyu.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
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
import com.zhiyu.app.ui.AppState
import com.zhiyu.app.ui.ChatViewModel
import com.zhiyu.app.ui.theme.WatercolorPaper
import com.zhiyu.app.ui.theme.WatercolorWelcomeHeader

@Composable fun ConnectionScreen(state: AppState, viewModel: ChatViewModel) {
    var bindingConfirmation by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        WatercolorPaper(Modifier.matchParentSize())
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WatercolorWelcomeHeader()
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text("恋人仅此唯一，此择不可挽回", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("partner_choice_motto"))
            Text(if (state.binding == null) "选择相伴的 AI，输入 API Key 后开启这段聊天。" else "你的选择已保留，验证 API Key 后继续这段聊天。", style = MaterialTheme.typography.bodyMedium)
            state.binding?.let { binding ->
                InfoCard("已绑定的 AI", "${state.provider?.name ?: binding.providerId}\n模型：${binding.model}\n公司与模型已固定，重新验证 Key 不会改变此选择。")
            }
            if (state.providers.isEmpty()) {
                Text("AI 公司列表暂未准备好，请重试。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (state.binding == null) ProviderPicker(state, viewModel)
                LoginCredentials(state, viewModel)
                if (state.binding == null) ModelOptions(state, viewModel)
                OutlinedButton(onClick = { styleOpen = true }, enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("恋人设定（可选）", Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                }
                if (state.profile != null) Text("已选择：${profileLabel(state.profile)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("profile_summary"))
                if (state.isDemo) InfoCard("本地演示", "填写 demo-key 即可体验。演示回复会明确标注，不调用云端 AI。")
                else Text("聊天与 Key 保存在本机，AI 请求直接发给你选的公司。首次验证可能产生少量调用费用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { if (state.binding == null) bindingConfirmation = true else viewModel.connect() }, enabled = !state.isBusy && state.apiKey.isNotBlank() && state.model.isNotBlank(), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("login")) {
                    if (state.busy == "验证 API Key") CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.busy == "验证 API Key") "正在验证…" else if (state.binding != null) "验证并继续" else "确认选择并开始")
                }
            }
            if (state.providers.isEmpty() || state.canRestoreConnection) {
                OutlinedButton(onClick = viewModel::loadProviders, enabled = !state.isBusy && !state.restoring,
                    modifier = Modifier.fillMaxWidth().testTag("retry_connection")) {
                    Text(if (state.canRestoreConnection) "重新尝试连接" else "重新加载")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("知语 · 只与你相伴", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (styleOpen) CompanionProfileDialog(state, viewModel) { styleOpen = false }
    if (bindingConfirmation && state.binding == null) AlertDialog(
        onDismissRequest = { bindingConfirmation = false },
        title = { Text("确认唯一的选择") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("恋人仅此唯一，此择不可挽回", color = MaterialTheme.colorScheme.primary)
                Text("AI 公司：${state.provider?.name ?: state.providerId}\n模型：${state.model}")
                state.profile?.let { profile ->
                    Text(profileDetails(profile))
                    Text(if (profile.mode == "preset") "这份恋人预设将同时固定，之后不能修改。" else "自行调教的设定之后可以继续调整。")
                }
                Text("API Key 验证成功后，本机将固定这家公司和模型，之后不能更换。验证失败不会绑定；现在取消也不会绑定。")
                if (!state.isDemo) Text("此次验证会产生一次极短的模型调用，可能有少量费用。")
            }
        },
        confirmButton = { TextButton(onClick = { bindingConfirmation = false; viewModel.connect() }, modifier = Modifier.testTag("confirm_binding")) { Text("确认并验证") } },
        dismissButton = { TextButton(onClick = { bindingConfirmation = false }) { Text("再想想") } },
    )
}

@Composable private fun ProviderPicker(state: AppState, viewModel: ChatViewModel) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("AI 公司", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = !state.isBusy, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("provider_selector")) {
                Text(state.provider?.name ?: "选择 AI 公司", Modifier.weight(1f))
                Icon(Icons.Outlined.ExpandMore, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.providers.forEach { provider ->
                    DropdownMenuItem(text = { Text(provider.name) }, onClick = { viewModel.provider(provider.id); open = false })
                }
            }
        }
    }
}

@Composable private fun LoginCredentials(state: AppState, viewModel: ChatViewModel) {
    var showKey by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = state.apiKey, onValueChange = viewModel::apiKey, label = { Text("API Key") },
        modifier = Modifier.fillMaxWidth().testTag("api_key"), enabled = !state.isBusy, singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = MaterialTheme.shapes.medium,
        trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "隐藏 Key" else "显示 Key") } },
    )
}

@Composable private fun ModelOptions(state: AppState, viewModel: ChatViewModel) {
    var expanded by remember(state.providerId) { mutableStateOf(false) }
    val requiresModel = state.provider?.defaultModel.isNullOrBlank()
    if (!requiresModel) TextButton(onClick = { expanded = !expanded }, enabled = !state.isBusy) {
        Text(if (expanded) "收起模型设置" else "模型设置（可选）")
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
    }
    if (requiresModel || expanded) {
        OutlinedTextField(
            value = state.model, onValueChange = viewModel::model, label = { Text("模型或接入点 ID") },
            singleLine = true, enabled = !state.isBusy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("model_id"),
            supportingText = { Text(state.provider?.modelHint.orEmpty()) },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.provider?.models.orEmpty().forEach { model ->
                SuggestionChip(onClick = { viewModel.model(model) }, label = { Text(model) }, enabled = !state.isBusy)
            }
        }
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
