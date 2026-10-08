package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhiyu.app.ui.AppState
import com.zhiyu.app.ui.ChatViewModel

@Composable internal fun DevelopmentServerDialog(state: AppState, viewModel: ChatViewModel, dismiss: () -> Unit) {
    var url by remember { mutableStateOf(state.serverUrl) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("开发设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("仅供本地联调。USB 调试默认使用 127.0.0.1:8791，需电脑建立 ADB 端口转发；重新插线后需重新建立。也可使用可达的局域网地址。手动更换服务器会清除本机 Key，已绑定的公司、模型及恋人设定仍会保留。取消不会改动连接或 Key。")
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("服务器地址") },
                    modifier = Modifier.fillMaxWidth().testTag("server_url"), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                if (url.startsWith("http://")) Text("调试 HTTP 传输未加密，仅用于可信本地网络。")
            }
        },
        confirmButton = { TextButton(onClick = {
            if (url != state.serverUrl) viewModel.serverUrl(url)
            dismiss()
        }, enabled = !state.isBusy && url.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}
