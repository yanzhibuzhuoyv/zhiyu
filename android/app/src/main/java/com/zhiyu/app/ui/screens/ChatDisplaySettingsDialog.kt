package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiyu.app.domain.ChatTypographySettings
import kotlin.math.roundToInt

@Composable fun ChatDisplaySettingsDialog(
    settings: ChatTypographySettings,
    busy: Boolean,
    error: String?,
    onConfirm: (ChatTypographySettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var size by rememberSaveable(settings.fontSizeSp) { mutableFloatStateOf(settings.fontSizeSp.toFloat()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("聊天字号") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${size.roundToInt()} sp", style = MaterialTheme.typography.labelLarge)
                Slider(value = size, onValueChange = { size = it }, valueRange = 14f..26f, steps = 11,
                    enabled = !busy, modifier = Modifier.testTag("chat_font_slider"))
                Text("你慢慢说，我一直在听。", fontSize = size.roundToInt().sp,
                    lineHeight = (size.roundToInt() * 1.55f).sp, modifier = Modifier.testTag("chat_font_preview"))
                Text("只调整聊天文字，仍会跟随手机的系统字体设置。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(ChatTypographySettings(size.roundToInt())) }, enabled = !busy,
                modifier = Modifier.testTag("save_chat_font")) { Text(if (busy) "正在保存…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
