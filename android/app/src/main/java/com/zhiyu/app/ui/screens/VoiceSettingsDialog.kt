package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhiyu.app.data.LocalVoiceRules
import com.zhiyu.app.ui.VoiceController

@Composable fun VoiceSettingsDialog(controller: VoiceController, onDismiss: () -> Unit) {
    DisposableEffect(controller) { onDispose { controller.stop() } }
    val close = { controller.stop(); onDismiss() }
    AlertDialog(onDismissRequest = close, title = { Text("恋人的声音") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("选一个听起来合心意的声音。", style = MaterialTheme.typography.bodyMedium)
            LocalVoiceRules.choices.forEach { voice ->
                Row(Modifier.fillMaxWidth().testTag("voice_choice_${voice.id}"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = controller.selectedVoiceId == voice.id, onClick = { controller.selectVoice(voice.id) },
                        enabled = !controller.busy)
                    Text(voice.name, Modifier.weight(1f))
                    TextButton(onClick = { controller.preview(voice.id) }, enabled = !controller.busy,
                        modifier = Modifier.testTag("voice_preview_${voice.id}")) { Text("试听") }
                }
            }
            controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (controller.busy) TextButton(onClick = controller::stop) { Text("停止试听") }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("好了") } })
}
