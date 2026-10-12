package com.zhiyu.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.zhiyu.app.data.ChatPhotoStore
import com.zhiyu.app.domain.ChatImageDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** ViewModel owns draft state. This controller copies a chosen content document and previews its derivative. */
@Stable
class ChatPhotoController(context: Context, private val scope: CoroutineScope,
    private val onDraft: (ChatImageDraft?) -> Unit) {
    private val store = ChatPhotoStore(context.applicationContext)
    private val mutex = Mutex()
    var preview by mutableStateOf<Bitmap?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var displayedId: String? = null

    fun choose(uri: Uri) = operation {
        val draft = withContext(Dispatchers.IO) { store.import(uri).also(store::saveDraft) }
        show(draft)
        onDraft(draft)
    }

    fun remove(draft: ChatImageDraft?) = operation {
        withContext(Dispatchers.IO) { store.clearDraftIf(draft?.attachment?.id) }
        displayedId = null
        preview = null
        onDraft(null)
    }

    suspend fun display(draft: ChatImageDraft?) = mutex.withLock {
        if (draft?.attachment?.id == displayedId) return@withLock
        try { show(draft) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = "这张图片暂时无法预览，可以重新选择；发送草稿已保留"
        }
    }

    fun dismissError() { error = null }

    private suspend fun show(draft: ChatImageDraft?) {
        val image = draft?.let {
            withContext(Dispatchers.IO) {
                val bytes = store.payload(it.attachment).bytes
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sample = com.zhiyu.app.domain.PhotoInputRules.sampleSize(bounds.outWidth, bounds.outHeight, 256, 256L * 256)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: throw IllegalArgumentException("图片预览失败")
            }
        }
        displayedId = draft?.attachment?.id
        preview = image
    }

    private fun operation(block: suspend () -> Unit) {
        if (busy || !scope.isActive) return
        busy = true
        error = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
            mutex.withLock {
                try { block() }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = when (failure) {
                        is SecurityException -> "暂时读不到这张图片，请通过图片选择器重新选择"
                        is IllegalArgumentException -> failure.message ?: "这张图片无法发送，请重新选择"
                        else -> "图片没有准备好，可以重试；原有草稿已保留"
                    }
                }
            }
            } finally { busy = false }
        }
    }
}

@Composable fun rememberChatPhotoController(draft: ChatImageDraft?, onDraft: (ChatImageDraft?) -> Unit): ChatPhotoController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val latestOnDraft by rememberUpdatedState(onDraft)
    val controller = remember(context, scope) { ChatPhotoController(context, scope) { latestOnDraft(it) } }
    LaunchedEffect(controller, draft?.attachment?.id) { controller.display(draft) }
    return controller
}
