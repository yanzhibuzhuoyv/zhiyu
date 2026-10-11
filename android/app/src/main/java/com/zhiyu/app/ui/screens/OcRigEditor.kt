package com.zhiyu.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zhiyu.app.domain.*
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

@Composable internal fun OcRigEditor(rig: OcRig, source: Bitmap?, enabled: Boolean, change: (OcRig) -> Unit) {
    var selected by remember { mutableStateOf(rig.regions.firstOrNull()?.id) }
    var kind by remember { mutableStateOf("accessory") }
    Text("把框移到对应部位，点击图片可定位。每个翅膀、道具都能另加区域。", style = MaterialTheme.typography.bodySmall)
    val active = rig.regions.firstOrNull { it.id == selected }
    fun update(region: OcRigRegion) { change(rig.copy(regions = rig.regions.map { if (it.id == region.id) region else it })) }
    source?.let { bitmap -> RegionCanvas(bitmap, rig, selected, enabled) { x, y ->
        active?.let { update(it.copy(x = (x - it.width / 2).coerceIn(0f, 1f - it.width), y = (y - it.height / 2).coerceIn(0f, 1f - it.height))) }
    } }
    rig.regions.forEach { region ->
        TextButton({ selected = region.id }, enabled = enabled) { Text("${region.label.ifBlank { rigLabel(region.kind) }}${if (selected == region.id) " · 已选择" else ""}") }
    }
    OcChoices(kind, listOf("head" to "头部", "body" to "呼吸", "eyes" to "眼睛", "mouth" to "嘴", "accessory" to "附属物"), enabled) { kind = it }
    OutlinedButton({
        val region = OcRigRegion(UUID.randomUUID().toString().replace("-", ""), kind, label = "${rigLabel(kind)} ${rig.regions.count { it.kind == kind } + 1}")
        selected = region.id; change(rig.copy(regions = rig.regions + region))
    }, enabled = enabled && rig.regions.size < 128) { Text("添加动画区域") }
    if (active == null) return
    OutlinedTextField(active.label, { if (it.length <= 80 && it.none { c -> c.code < 32 }) update(active.copy(label = it)) },
        label = { Text("区域名称") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    OcSlider("区域横向位置", active.x, 0f..(1f - active.width).coerceAtLeast(.001f), enabled) { update(active.copy(x = it.coerceAtMost(1f - active.width))) }
    OcSlider("区域纵向位置", active.y, 0f..(1f - active.height).coerceAtLeast(.001f), enabled) { update(active.copy(y = it.coerceAtMost(1f - active.height))) }
    OcSlider("区域宽度", active.width, .02f..1f, enabled) { update(active.copy(width = it, x = active.x.coerceAtMost(1 - it))) }
    OcSlider("区域高度", active.height, .02f..1f, enabled) { update(active.copy(height = it, y = active.y.coerceAtMost(1 - it))) }
    OcSlider("横向锚点", active.pivotX, 0f..1f, enabled) { update(active.copy(pivotX = it)) }
    OcSlider("纵向锚点", active.pivotY, 0f..1f, enabled) { update(active.copy(pivotY = it)) }
    OcSlider("动作幅度", active.strength, 0f..2f, enabled) { update(active.copy(strength = it)) }
    TextButton({ change(rig.copy(regions = rig.regions.filter { it.id != active.id })); selected = null }, enabled = enabled) { Text("移除这个区域") }
}
private fun rigLabel(kind: String) = when (kind) { "head" -> "头部"; "body" -> "身体呼吸"; "eyes" -> "眼睛"; "mouth" -> "嘴"; else -> "附属物" }

@Composable private fun RegionCanvas(source: Bitmap, rig: OcRig, selected: String?, enabled: Boolean, locate: (Float, Float) -> Unit) {
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(source) { source.asImageBitmap() }
    val latestLocate by rememberUpdatedState(locate)
    fun fit(): Float = min(dimensions.width.toFloat() / source.width, dimensions.height.toFloat() / source.height)
    Canvas(Modifier.fillMaxWidth().height(220.dp).onSizeChanged { dimensions = it }
        .pointerInput(source, selected, dimensions, enabled) {
            if (!enabled || selected == null) return@pointerInput
            detectTapGestures { offset ->
                val scale = fit(); val w = source.width * scale; val h = source.height * scale
                val x = (offset.x - (dimensions.width - w) / 2) / w
                val y = (offset.y - (dimensions.height - h) / 2) / h
                if (x in 0f..1f && y in 0f..1f) latestLocate(x, y)
            }
        }) {
        drawRect(Color(0xffe5e2db))
        val scale = min(size.width / source.width, size.height / source.height)
        val w = source.width * scale; val h = source.height * scale; val left = (size.width - w) / 2; val top = (size.height - h) / 2
        drawImage(image, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)))
        rig.regions.forEach { region ->
            val color = if (region.id == selected) Color(0xffbb6765) else Color(0xff65836e)
            drawRect(color, Offset(left + w * region.x, top + h * region.y), Size(w * region.width, h * region.height), style = Stroke(if (region.id == selected) 4f else 2f))
            drawCircle(color, 4f, Offset(left + w * (region.x + region.width * region.pivotX), top + h * (region.y + region.height * region.pivotY)))
        }
    }
}
