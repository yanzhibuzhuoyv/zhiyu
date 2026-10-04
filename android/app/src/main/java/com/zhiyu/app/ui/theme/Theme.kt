package com.zhiyu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF6653C7), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAE5FF), onPrimaryContainer = Color(0xFF332466),
    secondary = Color(0xFF71658D), surface = Color(0xFFFAF9FD),
    surfaceContainer = Color(0xFFF1EEF7), background = Color(0xFFFAF9FD),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFC6B7FF), onPrimary = Color(0xFF30205F),
    primaryContainer = Color(0xFF463478), onPrimaryContainer = Color(0xFFEAE5FF),
    secondary = Color(0xFFC4B7D8), surface = Color(0xFF17141E),
    surfaceContainer = Color(0xFF24202C), background = Color(0xFF17141E),
)
@Composable fun ZhiYuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
