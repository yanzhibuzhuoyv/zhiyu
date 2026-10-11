package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zhiyu.app.data.AgnesCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable fun ImageServiceDialog(onSaved: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val credentials = remember(context) { AgnesCredentialStore(context) }
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(credentials) {
        try { configured = withContext(Dispatchers.IO) { credentials.read() != null } }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "图像服务 Key 无法读取，请重新保存" }
    }
    fun save(clear: Boolean = false) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try {
                withContext(Dispatchers.IO) { if (clear) credentials.clear() else credentials.write(key.trim()) }
                key = ""; onSaved(); onDismiss()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "图像服务 Key 未保存，请检查后重试"
            } finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("图像服务") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Agnes 用于描述生成 OC、生成生活场景和辅助识图。Key 在本机加密保存，与聊天 Key 分开。每段关系有两次描述生成机会，生成生活场景另按服务商计费。")
            if (configured) Text("已保存 Key；留空可保留，重新填写可更新。")
            OutlinedTextField(value = key, onValueChange = { key = it }, enabled = !busy, singleLine = true,
                label = { Text("Agnes API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { save() }, enabled = !busy && key.isNotBlank()) { Text("保存") } },
        dismissButton = { Row {
            if (configured) TextButton(onClick = { save(true) }, enabled = !busy) { Text("移除 Key") }
            TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
        } })
}
