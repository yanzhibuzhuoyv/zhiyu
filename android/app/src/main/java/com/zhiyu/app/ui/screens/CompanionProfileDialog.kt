package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zhiyu.app.domain.CompanionProfile
import com.zhiyu.app.ui.AppState
import com.zhiyu.app.ui.ChatViewModel

private val styles = listOf("gentle" to "温柔体贴", "playful" to "俏皮亲昵", "steady" to "成熟稳重")
private val roles = listOf("girlfriend" to "女友", "boyfriend" to "男友", "partner" to "不限定")
internal fun styleLabel(profile: CompanionProfile?): String = styles.find { it.first == profile?.style }?.second ?: "温柔体贴"
internal fun roleLabel(profile: CompanionProfile?): String = roles.find { it.first == profile?.role }?.second ?: "不限定"
internal fun profileLabel(profile: CompanionProfile?): String = when {
    profile == null -> "聊天中自行培养"
    profile.mode == "custom" -> "自行调教"
    else -> "${styleLabel(profile)} · ${roleLabel(profile)}"
}
internal fun profileDetails(profile: CompanionProfile): String = buildString {
    append(if (profile.mode == "preset") "性格：${styleLabel(profile)}\n" else "模式：自行调教\n")
    append("恋人形象：${roleLabel(profile)}")
    if (profile.name.isNotBlank()) append("\n恋人名字：${profile.name}")
    if (profile.userName.isNotBlank()) append("\n对你的称呼：${profile.userName}")
}

@Composable internal fun CompanionProfileDialog(state: AppState, viewModel: ChatViewModel, dismiss: () -> Unit) {
    val initial = state.profile ?: state.binding?.profile
    val fixed = state.binding?.profile?.takeIf { it.mode == "preset" }
    val customBound = state.binding?.profile?.mode == "custom"
    if (fixed != null) {
        FixedCompanionProfileDialog(fixed, dismiss)
        return
    }
    var mode by remember { mutableStateOf(initial?.mode ?: "custom") }
    var enabled by remember { mutableStateOf(initial != null) }
    var style by remember { mutableStateOf(initial?.style ?: "gentle") }
    var role by remember { mutableStateOf(initial?.role ?: "partner") }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var userName by remember { mutableStateOf(initial?.userName.orEmpty()) }
    var instructions by remember { mutableStateOf(initial?.instructions.orEmpty()) }
    AlertDialog(
        onDismissRequest = dismiss, title = { Text("恋人设定") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.binding == null) {
                    ProfileChoice("我们调好的恋人", mode == "preset", !state.isBusy, "profile_mode_preset") { mode = "preset"; enabled = true }
                    ProfileChoice("自行调教", mode == "custom", !state.isBusy, "profile_mode_custom") { mode = "custom" }
                } else Text("你选择了自行培养这段关系，只能调整自行调教的设定，已绑定的 AI 公司与模型不会改变。", style = MaterialTheme.typography.bodySmall)
                if (mode == "custom") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("启用自行调教的设定", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !state.isBusy && !customBound, modifier = Modifier.testTag("profile_enable"))
                    }
                    if (!enabled) Text("不启用应用预设，在聊天中慢慢培养彼此的相处方式。", style = MaterialTheme.typography.bodySmall)
                    if (customBound) Text("自行调教已启用，可以修改或清空设定内容，不能关闭此模式。", style = MaterialTheme.typography.bodySmall)
                }
                if (mode == "preset" || enabled) {
                    if (mode == "preset") {
                        Text("性格", style = MaterialTheme.typography.labelLarge)
                        styles.forEach { (id, label) -> ProfileChoice(label, style == id, !state.isBusy, "style_$id") { style = id } }
                    }
                    Text("恋人形象", style = MaterialTheme.typography.labelLarge)
                    roles.forEach { (id, label) -> ProfileChoice(label, role == id, !state.isBusy, "role_$id") { role = id } }
                    ProfileNameField(name, { name = it }, "恋人名字（可选）", "companion_name", !state.isBusy)
                    ProfileNameField(userName, { userName = it }, "希望恋人如何称呼你（可选）", "user_name", !state.isBusy)
                    if (mode == "custom") OutlinedTextField(value = instructions, onValueChange = { instructions = it.take(2000) },
                        label = { Text("你想要的恋人设定（可选）") }, modifier = Modifier.fillMaxWidth().testTag("profile_instructions"),
                        enabled = !state.isBusy, minLines = 3, maxLines = 6, supportingText = { Text("${instructions.length}/2000 · 之后可以继续调整") })
                    else Text("首次验证成功后，这份预设的性格、形象、名字和称呼会固定，不能重新选择或修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            viewModel.setProfile(if (mode == "custom" && !enabled) null else CompanionProfile(mode = mode, style = style, role = role, name = name.trim(), userName = userName.trim(), instructions = if (mode == "custom") instructions.trim() else ""))
            dismiss()
        }, enabled = !state.isBusy, modifier = Modifier.testTag("save_profile")) { Text("保存设定") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}

@Composable private fun FixedCompanionProfileDialog(profile: CompanionProfile, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("已固定的恋人设定") },
        text = {
            Column(Modifier.testTag("profile_locked"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(profileDetails(profile))
                Text("这是首次绑定时选择的预设，性格、形象、名字和称呼都已固定，不能修改或重新选择。", color = MaterialTheme.colorScheme.primary)
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}

@Composable private fun ProfileChoice(label: String, selected: Boolean, enabled: Boolean, tag: String, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = choose).testTag(tag).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable private fun ProfileNameField(value: String, change: (String) -> Unit, label: String, tag: String, enabled: Boolean) {
    OutlinedTextField(value = value, onValueChange = { change(it.take(24)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth().testTag(tag),
        enabled = enabled, singleLine = true, supportingText = { Text("${value.length}/24") })
}
