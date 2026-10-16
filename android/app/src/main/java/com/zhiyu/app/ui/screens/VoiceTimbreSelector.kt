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
import com.zhiyu.app.data.LocalVoiceCatalog
import com.zhiyu.app.data.VoiceGender
import com.zhiyu.app.domain.VoiceTuningSettings
import com.zhiyu.app.ui.VoiceController

@Composable internal fun VoiceTimbreSelector(controller: VoiceController, draft: VoiceTuningSettings, enabled: Boolean) {
    var number by rememberSaveable(controller.selectedVoiceId) { mutableStateOf((controller.selectedVoiceId + 1).toString()) }
    var group by rememberSaveable { mutableStateOf(LocalVoiceCatalog.gender(controller.selectedVoiceId)) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(controller.selectedVoiceId) { group = LocalVoiceCatalog.gender(controller.selectedVoiceId) }
    val candidates = remember(group) { LocalVoiceCatalog.ids(group) }
    val position = candidates.indexOf(controller.selectedVoiceId)
    val id = number.toIntOrNull()?.minus(1)
    val valid = id != null && id in 0 until LocalVoiceRules.SPEAKER_COUNT
    HorizontalDivider()
    Text("音色", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceGender.entries.filter { LocalVoiceCatalog.ids(it).isNotEmpty() }.forEach { gender ->
            FilterChip(selected = group == gender, onClick = { group = gender; expanded = false }, enabled = enabled,
                label = { Text(gender.label) }, modifier = Modifier.testTag("voice_gender_${gender.name}"))
        }
    }
    Text("当前：${LocalVoiceCatalog.gender(controller.selectedVoiceId).label} · 音色 ${controller.selectedVoiceId + 1}",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("voice_selected_label"))
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag("voice_catalog_menu")) {
            Text("从${group.label}中挑选 · ${candidates.size} 种")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 280.dp)) {
            candidates.forEach { candidate ->
                DropdownMenuItem(text = { Text("音色 ${candidate + 1}" + if (candidate == controller.selectedVoiceId) " ✓" else "") },
                    onClick = { expanded = false; controller.selectVoice(candidate) }, enabled = enabled,
                    modifier = Modifier.testTag("voice_catalog_$candidate"))
            }
        }
    }
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
        TextButton(onClick = { candidates.getOrNull(position - 1)?.let(controller::selectVoice) },
            enabled = enabled && position > 0, modifier = Modifier.testTag("voice_timbre_previous")) { Text("上一种${group.label}") }
        TextButton(onClick = { controller.preview(controller.selectedVoiceId, draft) }, enabled = enabled,
            modifier = Modifier.testTag("voice_timbre_preview")) { Text("试听当前音色") }
        TextButton(onClick = { candidates.getOrNull(position + 1)?.let(controller::selectVoice) },
            enabled = enabled && position < candidates.lastIndex,
            modifier = Modifier.testTag("voice_timbre_next")) { Text("下一种${group.label}") }
    }
}
