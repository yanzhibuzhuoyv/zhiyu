package com.zhiyu.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.zhiyu.app.data.AppearanceImageReader
import com.zhiyu.app.data.AppearanceStore
import com.zhiyu.app.domain.AppearanceSelection
import com.zhiyu.app.domain.AppearanceTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppearanceImages(
    val userAvatar: Bitmap? = null,
    val companionAvatar: Bitmap? = null,
    val background: Bitmap? = null,
) {
    operator fun get(target: AppearanceTarget) = when (target) {
        AppearanceTarget.USER_AVATAR -> userAvatar
        AppearanceTarget.COMPANION_AVATAR -> companionAvatar
        AppearanceTarget.BACKGROUND -> background
    }

    fun with(target: AppearanceTarget, image: Bitmap?) = when (target) {
        AppearanceTarget.USER_AVATAR -> copy(userAvatar = image)
        AppearanceTarget.COMPANION_AVATAR -> copy(companionAvatar = image)
        AppearanceTarget.BACKGROUND -> copy(background = image)
    }
}

val LocalAppearanceImages = staticCompositionLocalOf { AppearanceImages() }

@Stable
class AppearanceController(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val store = AppearanceStore(appContext)
    private val reader = AppearanceImageReader(appContext)
    private val mutex = Mutex()
    var selection by mutableStateOf(AppearanceSelection())
        private set
    var images by mutableStateOf(AppearanceImages())
        private set
    var busy by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun restore() = operation {
        val loaded = withContext(Dispatchers.IO) { store.load() }
        var restored = loaded.selection
        var decoded = AppearanceImages()
        val failed = loaded.invalidTargets.toMutableList()
        AppearanceTarget.entries.forEach { target ->
            val value = restored[target] ?: return@forEach
            try {
                val image = withContext(Dispatchers.IO) { reader.read(value, target) }
                decoded = decoded.with(target, image)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                restored = restored.with(target, null)
                failed.add(target)
            }
        }
        if (failed.isNotEmpty()) {
            withContext(Dispatchers.IO) { store.save(restored) }
            error = "${failed.distinct().joinToString("、") { it.label }}已无法读取，已恢复默认。可以重新选择图片。"
        }
        selection = restored
        images = decoded
    }

    fun choose(target: AppearanceTarget, uri: Uri) = operation {
        val image = withContext(Dispatchers.IO) {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            reader.read(uri.toString(), target)
        }
        val changed = selection.with(target, uri.toString())
        withContext(Dispatchers.IO) { store.save(changed) }
        selection = changed
        images = images.with(target, image)
    }

    fun reset(target: AppearanceTarget) = operation {
        val changed = selection.with(target, null)
        withContext(Dispatchers.IO) { store.save(changed) }
        selection = changed
        images = images.with(target, null)
    }

    fun dismissError() { error = null }

    private fun operation(block: suspend () -> Unit) = scope.launch {
        mutex.withLock {
            busy = true
            error = null
            try { block() } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = when (failure) {
                    is SecurityException -> "暂时无法读取这张图片，请通过文件选择器重新授权。"
                    is IllegalArgumentException, is IllegalStateException -> failure.message ?: "图片设置失败，请重新选择。"
                    else -> "图片读取失败，请确认文件仍然存在，再重新选择。"
                }
            } finally { busy = false; ready = true }
        }
    }
}

@Composable
fun rememberAppearanceController(): AppearanceController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context.applicationContext, scope) { AppearanceController(context, scope) }
    LaunchedEffect(controller) { controller.restore() }
    return controller
}
