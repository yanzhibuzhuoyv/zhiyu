package com.zhiyu.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zhiyu.app.ui.MediaController
import com.zhiyu.app.ui.MediaLibraryController

@Composable fun LocalMediaLibraryDialog(library: MediaLibraryController, media: MediaController, close: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf("sticker") }
    var description by rememberSaveable { mutableStateOf("") }
    var containsPerson by rememberSaveable { mutableStateOf(true) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { library.import(it, kind, description, containsPerson, media.profile?.visualVersion ?: 0) }
    }
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().heightIn(max = 660.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("图片与表情包", style = MaterialTheme.typography.titleLarge)
                Text("图片 ${library.images.size} 张 · 表情包 ${library.stickers.size} 张")
                Text("素材保存在这台手机里。给它一句描述，TA 会在聊天里选择合适的时机发出。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "sticker", { kind = "sticker" }, label = { Text("表情包") }, enabled = !library.busy)
                    FilterChip(kind == "image", { kind = "image" }, label = { Text("图片") }, enabled = !library.busy)
                }
                OutlinedTextField(description, { description = it.take(500) }, label = { Text("画面或表情含义") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = !library.busy)
                if (kind == "image") {
                    Row { Checkbox(containsPerson, { containsPerson = it }, enabled = !library.busy); Text("含人物、手、影子或其他 OC 部件") }
                    Text("含人物的图片会关联当前恋人外观。更换参考图后，不会继续使用旧外观的图片。", style = MaterialTheme.typography.bodySmall)
                }
                if (library.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                library.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                FilledTonalButton(onClick = { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif")) },
                    enabled = !library.busy && description.isNotBlank()) { Text("选择并导入") }
                (if (kind == "sticker") library.stickers else library.images).takeLast(6).forEach {
                    MediaImage(it.id, it.sha256, sticker = kind == "sticker", description = "本地素材")
                }
                TextButton(onClick = close) { Text("完成") }
            }
        }
    }
}
