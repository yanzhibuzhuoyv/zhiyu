package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhiyu.app.data.LocalVoiceRules
import com.zhiyu.app.ui.VoiceController
import com.zhiyu.app.domain.VoiceTuningSettings
import java.util.Locale
import kotlin.math.roundToInt

@Composable fun VoiceSettingsDialog(controller: VoiceController, onDismiss: () -> Unit) {
    DisposableEffect(controller) { onDispose { controller.stop() } }
    var speed by rememberSaveable(controller.tuning) { mutableFloatStateOf(controller.tuning.speed) }
    var pitch by rememberSaveable(controller.tuning) { mutableFloatStateOf(controller.tuning.pitchSemitones) }
    var volume by rememberSaveable(controller.tuning) { mutableFloatStateOf(controller.tuning.volume) }
    val editable = !controller.busy && !controller.settingsSaving
    val close = { if (!controller.settingsSaving) { controller.stop(); onDismiss() } }
    val draft = VoiceTuningSettings(speed, pitch, volume)
    AlertDialog(onDismissRequest = close, title = { Text("恋人的声音") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("先选声音，再慢慢调到合你的心意。", style = MaterialTheme.typography.bodyMedium)
            LocalVoiceRules.choices.forEach { voice ->
                Row(Modifier.fillMaxWidth().testTag("voice_choice_${voice.id}"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = controller.selectedVoiceId == voice.id, onClick = { controller.selectVoice(voice.id) },
                        enabled = editable)
                    Text(voice.name, Modifier.weight(1f))
                    TextButton(onClick = { controller.preview(voice.id, draft) }, enabled = editable,
                        modifier = Modifier.testTag("voice_preview_${voice.id}")) { Text("试听") }
                }
            }
            VoiceTimbreSelector(controller, draft, editable)
            VoiceTuningSlider("语速", "${String.format(Locale.ROOT, "%.2f", speed)} 倍", speed,
                VoiceTuningSettings.MIN_SPEED..VoiceTuningSettings.MAX_SPEED, 11, editable, "voice_speed_slider") { speed = it }
            VoiceTuningSlider("音高", "${String.format(Locale.ROOT, "%+.1f", pitch)} 半音", pitch,
                VoiceTuningSettings.MIN_PITCH..VoiceTuningSettings.MAX_PITCH, 15, editable, "voice_pitch_slider") { pitch = it }
            VoiceTuningSlider("声音音量", "${(volume * 100).roundToInt()}%", volume,
                0f..1f, 19, editable, "voice_volume_slider") { volume = it }
            Text("试听使用当前调节；保存后，之后的通话也会使用。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { speed = 1f; pitch = 0f; volume = 1f }, enabled = editable,
                modifier = Modifier.testTag("voice_tuning_reset")) { Text("恢复原声音参数") }
            controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (controller.busy) TextButton(onClick = controller::stop) { Text("停止试听") }
        }
    }, confirmButton = {
        TextButton(onClick = { controller.stop(); controller.saveTuning(draft) { onDismiss() } },
            enabled = !controller.settingsSaving, modifier = Modifier.testTag("voice_tuning_save")) {
            Text(if (controller.settingsSaving) "正在保存…" else "好了")
        }
    }, dismissButton = { TextButton(onClick = close, enabled = !controller.settingsSaving) { Text("取消") } })
}

@Composable private fun VoiceTuningSlider(label: String, displayed: String, value: Float,
    range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean, tag: String, change: (Float) -> Unit) {
    Text("$label · $displayed", style = MaterialTheme.typography.labelLarge)
    Slider(value = value, onValueChange = change, valueRange = range, steps = steps, enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label })
}
