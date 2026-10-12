package com.zhiyu.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhiyu.app.data.LocalDailyScheduler
import com.zhiyu.app.data.local.qwen.QwenPackStore
import com.zhiyu.app.data.local.qwen.QwenRelease
import kotlinx.coroutines.*

@Composable fun QwenModelDialog(onSaved: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val store = remember(app) { QwenPackStore(app) }
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableLongStateOf(0) }
    var total by remember { mutableLongStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var cloud by remember { mutableStateOf(false) }
    LaunchedEffect(store) { try { installed = withContext(Dispatchers.IO) { store.installed() != null } }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "模型文件不完整，请重新导入" } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && !busy) {
            busy = true; copied = 0; total = 0; error = null
            operation = scope.launch {
                try {
                    store.import(uri) { done, size -> scope.launch { copied = done; total = size } }
                    installed = true; onSaved(); LocalDailyScheduler.refreshBoundPlan(app)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message?.take(160) ?: "模型没有导入完成，原来的模型仍会保留"
                } finally { busy = false }
            }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("本机照片") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("导入学生模型包后，会在手机上提前准备生活照片。模型文件约 5.29 GB；导入时还需预留空间。聊天不用等出图。")
            Text(if (installed) "学生模型已导入" else "还没有导入学生模型包", Modifier.testTag("qwen_pack_status"))
            if (!QwenRelease.supported()) Text("本机生图需要 Android 10 及以上的 64 位手机。")
            if (busy) Text(if (total > 0) "正在导入：${copied * 100 / total}%" else "正在读取模型文件夹…")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { picker.launch(null) }, enabled = !busy && QwenRelease.supported(),
                modifier = Modifier.testTag("qwen_import_pack")) { Text(if (installed) "重新校验模型包" else "导入模型文件夹") }
            TextButton(onClick = { cloud = true }, enabled = !busy) { Text("参考形象生成服务") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } }, dismissButton = {
        if (busy) TextButton(onClick = { operation?.cancel() }) { Text("取消导入") }
    })
    if (cloud) ImageServiceDialog(onSaved) { cloud = false }
}
