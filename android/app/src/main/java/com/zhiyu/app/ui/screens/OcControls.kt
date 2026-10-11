package com.zhiyu.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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
