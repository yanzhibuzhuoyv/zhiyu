package com.zhiyu.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.zhiyu.app.data.ChatTypographyPreferences
import com.zhiyu.app.domain.ChatTypographySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalChatTypography = staticCompositionLocalOf { ChatTypographySettings() }

@Stable
class ChatTypographyController internal constructor(
    private val preferences: ChatTypographyPreferences,
    private val scope: CoroutineScope,
) {
    var settings by mutableStateOf(ChatTypographySettings()); private set
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    internal suspend fun load() {
        val loaded = withContext(Dispatchers.IO) { preferences.load() }
        settings = loaded.settings
        error = loaded.warning
        ready = true
    }

    fun save(value: ChatTypographySettings, saved: () -> Unit = {}) {
        if (busy || !ready) return
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { preferences.save(value) }
                settings = value
                saved()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "字号没有保存成功，请重试。"
            } finally { busy = false }
        }
    }
}

@Composable fun rememberChatTypographyController(): ChatTypographyController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { ChatTypographyController(ChatTypographyPreferences(context), scope) }
    LaunchedEffect(controller) { controller.load() }
    return controller
}
