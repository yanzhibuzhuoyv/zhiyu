package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhiyu.app.data.LocalVoiceRules
import com.zhiyu.app.domain.VoiceTuningSettings
import com.zhiyu.app.ui.VoiceController

@Composable internal fun VoiceTimbreSelector(controller: VoiceController, draft: VoiceTuningSettings, enabled: Boolean) {
    var number by rememberSaveable(controller.selectedVoiceId) { mutableStateOf((controller.selectedVoiceId + 1).toString()) }
    val id = number.toIntOrNull()?.minus(1)
    val valid = id != null && id in 0 until LocalVoiceRules.SPEAKER_COUNT
    HorizontalDivider()
    Text("音色", style = MaterialTheme.typography.titleSmall)
    Text("可以试听不同声线，再选喜欢的音色。", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = number, onValueChange = { value ->
            if (value.length <= 3 && value.all { it in '0'..'9' }) number = value
        }, label = { Text("音色编号 · 1—${LocalVoiceRules.SPEAKER_COUNT}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
            enabled = enabled, isError = !valid, modifier = Modifier.weight(1f).testTag("voice_timbre_number"))
        TextButton(onClick = { id?.let(controller::selectVoice) }, enabled = enabled && valid,
            modifier = Modifier.testTag("voice_timbre_apply")) { Text("选用") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { controller.selectVoice(controller.selectedVoiceId - 1) },
            enabled = enabled && controller.selectedVoiceId > 0, modifier = Modifier.testTag("voice_timbre_previous")) { Text("上一种") }
        TextButton(onClick = { controller.preview(controller.selectedVoiceId, draft) }, enabled = enabled,
            modifier = Modifier.testTag("voice_timbre_preview")) { Text("试听当前音色") }
        TextButton(onClick = { controller.selectVoice(controller.selectedVoiceId + 1) },
            enabled = enabled && controller.selectedVoiceId < LocalVoiceRules.SPEAKER_COUNT - 1,
            modifier = Modifier.testTag("voice_timbre_next")) { Text("下一种") }
    }
}
