package com.zhiyu.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.zhiyu.app.data.LocalMediaStore
import com.zhiyu.app.data.VisualReferenceReader
import com.zhiyu.app.domain.MediaAttachment
import kotlinx.coroutines.*

@Stable class MediaLibraryController(context: Context, private val scope: CoroutineScope) {
    private val store = LocalMediaStore(context)
    private val reader = VisualReferenceReader(context)
    var images by mutableStateOf<List<MediaAttachment>>(emptyList()); private set
    var stickers by mutableStateOf<List<MediaAttachment>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    fun reload() = operation { refresh() }
    fun import(uri: Uri, kind: String, description: String, containsPerson: Boolean, visualVersion: Int) = operation {
        require(kind in setOf("image", "sticker") && description.length <= 500) { "素材描述无效" }
        if (kind == "image" && containsPerson) require(visualVersion > 0) { "请先设定恋人外观，再导入含人物的素材" }
        val bytes = reader.read(uri)
        withContext(Dispatchers.IO) { store.ingest(kind, bytes, "用户本地素材", visualVersion =
            if (kind == "image" && containsPerson) visualVersion else 0,
            description = description, tags = if (kind == "image" && containsPerson) listOf("contains_person") else emptyList()) }
        refresh()
    }
    private suspend fun refresh() {
        val rows = withContext(Dispatchers.IO) { store.list("image") to store.list("sticker") }
        images = rows.first; stickers = rows.second
    }
    private fun operation(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "素材操作未完成，请重试"
            } finally { busy = false }
        }
    }
}
@Composable fun rememberMediaLibraryController(): MediaLibraryController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(context, scope) { MediaLibraryController(context, scope) }.also { controller ->
        LaunchedEffect(controller) { controller.reload() }
    }
}
