package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhiyu.app.ui.ProactiveController

@Composable fun ProactiveContactDialog(controller: ProactiveController, toggle: (Boolean) -> Unit, close: () -> Unit) {
    val preferences = controller.preferences
    AlertDialog(
        onDismissRequest = close,
        title = { Text("主动联系") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("允许恋人在你离开聊天后，按自己的性格主动来找你。")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("接收恋人的消息", Modifier.weight(1f))
                    Switch(preferences.enabled, toggle, enabled = !controller.busy && controller.ready, modifier = Modifier.testTag("proactive_enabled"))
                }
                Text("每天 %02d:00—%02d:00 保持安静（%s）。".format(preferences.quietStart, preferences.quietEnd, preferences.timeZone), style = MaterialTheme.typography.bodySmall)
                Text("手机省电或后台限制可能让消息晚些到达。", style = MaterialTheme.typography.bodySmall)
                if (controller.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("proactive_error")) }
                if (!controller.ready && controller.error != null) TextButton(
                    onClick = { controller.restore() }, enabled = !controller.busy, modifier = Modifier.testTag("retry_proactive_preferences"),
                ) { Text("重新读取设置") }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("完成") } },
    )
}
