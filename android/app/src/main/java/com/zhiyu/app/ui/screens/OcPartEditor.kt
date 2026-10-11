package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.zhiyu.app.domain.*
import java.util.UUID

@Composable internal fun OcPartEditor(parts: List<OcPart>, enabled: Boolean, threeDimensional: Boolean = false, change: (List<OcPart>) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf("wing") }
    var side by remember { mutableStateOf("left") }
    Text("每个附属物独立保存，单翼、多翼、武器和尾巴都可以分别放置。左右指角色自身，位置可自由移动。", style = MaterialTheme.typography.bodySmall)
    OcChoices(kind, listOf("wing" to "翅膀", "weapon" to "武器", "horn" to "角", "tail" to "尾巴", "ear" to "兽耳", "ornament" to "饰物", "custom" to "自由部件"), enabled) { kind = it }
    OcChoices(side, listOf("left" to "角色左侧", "right" to "角色右侧", "center" to "中央", "unspecified" to "不指定"), enabled) { side = it }
    OutlinedButton(onClick = {
        val id = UUID.randomUUID().toString().replace("-", "")
        val x = when (side) { "left" -> .72f; "right" -> .28f; else -> .5f }
        val part = OcPart(id, kind, side, x = x, y = if (kind == "horn" || kind == "ear") .13f else .47f,
            layer = if (kind == "wing" || kind == "tail") -1 else 1, label = "${partLabel(kind)} ${parts.count { it.kind == kind } + 1}")
        selected = id; change(parts + part)
    }, enabled = enabled && parts.size < OcIdentityRules.MAX_PARTS, modifier = Modifier.testTag("oc_add_part")) { Text("添加一个${partLabel(kind)}") }
    parts.forEach { part ->
        TextButton(onClick = { selected = if (selected == part.id) null else part.id }, enabled = enabled,
            modifier = Modifier.testTag("oc_part_${part.id}")) { Text("${part.label.ifBlank { partLabel(part.kind) }} · ${sideLabel(part.side)}") }
    }
    val active = parts.firstOrNull { it.id == selected } ?: return
    fun update(p: OcPart) { change(parts.map { if (it.id == p.id) p else it }) }
    OutlinedTextField(active.label, { if (it.length <= 80 && it.none { c -> c.code < 32 }) update(active.copy(label = it)) },
        label = { Text("部件名称") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OcSlider("水平位置", active.x, 0f..1f, enabled) { update(active.copy(x = it)) }
    OcSlider("垂直位置", active.y, 0f..1f, enabled) { update(active.copy(y = it)) }
    if (threeDimensional) OcSlider("前后位置", active.z, -1f..1f, enabled) { update(active.copy(z = it)) }
    OcSlider("宽度", active.width, .02f.. .9f, enabled) { update(active.copy(width = it)) }
    OcSlider("高度", active.height, .02f.. .9f, enabled) { update(active.copy(height = it)) }
    OcSlider("旋转", active.angle, -180f..180f, enabled) { update(active.copy(angle = it)) }
    if (!threeDimensional) OcChoices(if (active.layer < 0) "back" else "front", listOf("back" to "人物后面", "front" to "人物前面"), enabled) { update(active.copy(layer = if (it == "back") -1 else 1)) }
    OcColorPicker("部件颜色", active.color, partColors, enabled) { update(active.copy(color = it)) }
    TextButton(onClick = { change(parts.filter { it.id != active.id }); selected = null }, enabled = enabled) { Text("移除这个部件") }
}
internal fun partLabel(kind: String): String = when (kind) { "wing" -> "翅膀"; "weapon" -> "武器"; "horn" -> "角"; "tail" -> "尾巴"; "ear" -> "兽耳"; "ornament" -> "饰物"; else -> "自由部件" }
private fun sideLabel(side: String): String = when (side) { "left" -> "左侧"; "right" -> "右侧"; "center" -> "中央"; else -> "不指定" }
