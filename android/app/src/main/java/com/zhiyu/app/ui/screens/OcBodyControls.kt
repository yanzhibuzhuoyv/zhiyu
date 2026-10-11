package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhiyu.app.domain.OcBody

@Composable internal fun OcBodyControls(body: OcBody, enabled: Boolean, change: (OcBody) -> Unit) {
    Text("脸与五官", style = MaterialTheme.typography.titleSmall)
    OcSlider("脸宽", body.headWidth, .65f..1.35f, enabled, "oc_face_width") { change(body.copy(headWidth = it)) }
    OcSlider("脸长", body.headHeight, .65f..1.35f, enabled) { change(body.copy(headHeight = it)) }
    OcSlider("眼睛大小", body.eyeSize, .65f..1.35f, enabled) { change(body.copy(eyeSize = it)) }
    OcSlider("眼间距", body.eyeSpacing, .65f..1.35f, enabled) { change(body.copy(eyeSpacing = it)) }
    OcSlider("鼻子大小", body.noseSize, .65f..1.35f, enabled) { change(body.copy(noseSize = it)) }
    OcSlider("嘴宽", body.mouthWidth, .65f..1.35f, enabled) { change(body.copy(mouthWidth = it)) }
    Text("体型与头发", style = MaterialTheme.typography.titleSmall)
    OcSlider("肩与身体宽度", body.bodyWidth, .65f..1.35f, enabled) { change(body.copy(bodyWidth = it)) }
    OcSlider("身高比例", body.height, .65f..1.35f, enabled) { change(body.copy(height = it)) }
    OcSlider("头发长度", body.hairLength, 0f..1f, enabled) { change(body.copy(hairLength = it)) }
    OcChoices(body.hairStyle, listOf("soft" to "柔软刘海", "short" to "短发", "side" to "侧分", "twin" to "双束"), enabled) { change(body.copy(hairStyle = it)) }
    OcColorPicker("肤色", body.skin, listOf(0xffffdccc, 0xffe8b69e, 0xffb87f62, 0xff805741, 0xffe4dfed).map { it.toInt() }, enabled) { change(body.copy(skin = it)) }
    OcColorPicker("发色", body.hair, hairColors, enabled) { change(body.copy(hair = it)) }
    OcColorPicker("瞳色", body.eyes, hairColors, enabled) { change(body.copy(eyes = it)) }
    OcColorPicker("衣服颜色", body.clothes, partColors, enabled) { change(body.copy(clothes = it)) }
}

internal val hairColors = listOf(0xff4e3f3a, 0xff252733, 0xffcab7a0, 0xffb4b7ba, 0xffaf7974, 0xff6d8a73, 0xff8a88a6).map { it.toInt() }
internal val partColors = listOf(0xffddd9c5, 0xffb7c4bb, 0xffb5c6d1, 0xffc99e9b, 0xff9e95b2, 0xff545466, 0xffd4b16b).map { it.toInt() }

@Composable internal fun OcSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    tag: String = "oc_slider_$label", change: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Slider(value.coerceIn(range), change, valueRange = range, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag))
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun OcChoices(value: String, choices: List<Pair<String, String>>, enabled: Boolean, choose: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { (key, label) -> FilterChip(value == key, { choose(key) }, label = { Text(label) }, enabled = enabled) }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun OcColorPicker(label: String, value: Int, values: List<Int>, enabled: Boolean, choose: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        values.forEachIndexed { index, color ->
            FilterChip(value == color, { choose(color) }, label = { Text("●", color = Color(color)) }, enabled = enabled,
                modifier = Modifier.testTag("oc_color_${label}_$index"))
        }
    }
}
