package com.zhiyu.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import com.zhiyu.app.data.ownedOcBitmap
import com.zhiyu.app.data.remotescene.ScenePhotoManagement
import com.zhiyu.app.data.remotescene.ScenePhotoPage
import com.zhiyu.app.data.remotescene.ScenePhotoRow
import com.zhiyu.app.domain.Image2Protocol
import com.zhiyu.app.domain.MediaRules
import kotlinx.coroutines.*

fun interface ScenePhotoPreviewDecoder { fun decode(bytes: ByteArray): Bitmap }

internal object AndroidScenePhotoPreviewDecoder : ScenePhotoPreviewDecoder {
    override fun decode(bytes: ByteArray): Bitmap {
        Image2Protocol.imageMime(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 &&
            bounds.outWidth.toLong() * bounds.outHeight <= Image2Protocol.MAX_IMAGE_PIXELS)
        var sample = 1
        while ((maxOf(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > 512) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        return try {
            require(bitmap.width in 1..512 && bitmap.height in 1..512); bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
}

enum class ScenePhotoAction { RETRY_FAILED, CLEAR_PIXELS }
data class ScenePhotoConfirmation internal constructor(val requestId: String, val action: ScenePhotoAction, val revision: Long)

/** One chat and one owned preview at a time. No credentials or prompt text enter this controller. */
@Stable class ScenePhotoController(
    private val manager: ScenePhotoManagement,
    private val scope: CoroutineScope,
    private val decoder: ScenePhotoPreviewDecoder = AndroidScenePhotoPreviewDecoder,
) {
    private var chat: String? = null
    private var job: Job? = null
    private var serial = 0L
    private var previewLoading = false
    private var cursor: String? = null
    private var trail = emptyList<String?>()
    var items by mutableStateOf<List<ScenePhotoRow>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var confirmation by mutableStateOf<ScenePhotoConfirmation?>(null); private set
    var nextCursor by mutableStateOf<String?>(null); private set
    var canGoBack by mutableStateOf(false); private set
    var pageNumber by mutableStateOf(1); private set
    var bindingRevision by mutableStateOf(0L); private set

    fun connect(chat: String?) {
        chat?.let(MediaRules::path)
        if (chat == this.chat) return
        bindingRevision++; serial++; job?.cancel(); job = null; this.chat = chat
        releasePreview(); items = emptyList(); confirmation = null; cursor = null; trail = emptyList()
        nextCursor = null; canGoBack = false; pageNumber = 1; busy = false; previewLoading = false; error = null
        if (chat != null) reload()
    }
    fun reload() = page(cursor, trail)
    fun nextPage() { nextCursor?.let { page(it, trail + listOf(cursor)) } }
    fun previousPage() { if (trail.isNotEmpty()) page(trail.last(), trail.dropLast(1)) }

    private fun page(next: String?, history: List<String?>) = task(Kind.PAGE) { stamp ->
        releasePreview()
        val result = withContext(Dispatchers.IO) { manager.page(stamp.chat, next) }
        checkCurrent(stamp); applyPage(result, next, history)
    }
    private fun applyPage(page: ScenePhotoPage, at: String?, history: List<String?>) {
        require(page.items.size <= 30 && page.items.map { it.requestId }.distinct().size == page.items.size)
        page.items.forEach { MediaRules.path(it.requestId) }
        page.nextCursor?.let { require(it.length in 1..256 && it.all { char -> char.code in 33..126 } && it != at) }
        items = page.items.toList(); cursor = at; trail = history.toList(); nextCursor = page.nextCursor
        canGoBack = trail.isNotEmpty(); pageNumber = trail.size + 1
    }
    fun showPreview(requestId: String) {
        if (items.none { it.requestId == requestId && it.hasPicture }) return
        task(Kind.PREVIEW) { stamp ->
            releasePreview(); selectedId = requestId
            val bytes = withContext(Dispatchers.IO) {
                val value = manager.preview(stamp.chat, requestId)
                Image2Protocol.imageMime(value); value.copyOf()
            }
            checkCurrent(stamp)
            val bitmap = ownedOcBitmap { decoder.decode(bytes) }
            var adopted = false
            try {
                checkCurrent(stamp)
                require(!bitmap.isRecycled && bitmap.width in 1..512 && bitmap.height in 1..512)
                preview = bitmap; adopted = true
            } finally { if (!adopted) bitmap.recycle() }
        }
    }
    fun closePreview() {
        if (previewLoading) { serial++; job?.cancel(); job = null; previewLoading = false; busy = false }
        releasePreview()
    }
    private fun releasePreview() { preview?.recycle(); preview = null; selectedId = null }

    fun recheck(requestId: String) {
        if (items.none { it.requestId == requestId && it.canReview && it.hasPicture }) return
        mutate(Kind.REVIEW, requestId)
    }
    fun requestRetry(requestId: String) = request(requestId, ScenePhotoAction.RETRY_FAILED)
    fun requestClear(requestId: String) = request(requestId, ScenePhotoAction.CLEAR_PIXELS)
    private fun request(id: String, action: ScenePhotoAction) {
        if (busy || chat == null || confirmation != null || !allowed(id, action)) return
        confirmation = ScenePhotoConfirmation(id, action, bindingRevision)
    }
    private fun allowed(id: String, action: ScenePhotoAction) = items.any {
        it.requestId == id && when (action) {
            ScenePhotoAction.RETRY_FAILED -> it.canRetry && it.status == "failed"
            ScenePhotoAction.CLEAR_PIXELS -> it.canClear
        }
    }
    fun cancelConfirmation() { confirmation = null }
    fun confirm(value: ScenePhotoConfirmation) {
        if (value !== confirmation || value.revision != bindingRevision || busy || !allowed(value.requestId, value.action)) return
        confirmation = null
        mutate(if (value.action == ScenePhotoAction.RETRY_FAILED) Kind.RETRY else Kind.CLEAR, value.requestId)
    }
    private fun mutate(kind: Kind, id: String) = task(kind) { stamp ->
        releasePreview()
        // A lost paid result must not leave a stale retry button enabled before the next refresh.
        if (kind == Kind.RETRY) items = items.map { if (it.requestId == id) it.copy(canRetry = false) else it }
        withContext(Dispatchers.IO) {
            when (kind) {
                Kind.REVIEW -> manager.recheck(stamp.chat, id)
                Kind.RETRY -> manager.retryFailed(stamp.chat, id)
                Kind.CLEAR -> manager.clear(stamp.chat, id)
                else -> error("Invalid candidate action")
            }
        }
        checkCurrent(stamp)
        val result = withContext(Dispatchers.IO) { manager.page(stamp.chat, null) }
        checkCurrent(stamp); applyPage(result, null, emptyList())
    }
    private fun task(kind: Kind, action: suspend (Stamp) -> Unit) {
        val owner = chat ?: return
        if (busy || confirmation != null) return
        val stamp = Stamp(owner, bindingRevision, ++serial)
        busy = true; error = null; previewLoading = kind == Kind.PREVIEW
        job = scope.launch {
            try { checkCurrent(stamp); action(stamp); checkCurrent(stamp) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (current(stamp)) error = when (kind) {
                    Kind.PAGE -> "候选列表暂时无法读取，请刷新后重试"
                    Kind.PREVIEW -> "这张候选图片暂时无法预览，请刷新后重试"
                    Kind.RETRY -> "重新生成未完成，请刷新查看记录；结果未确认时不要重复提交"
                    Kind.REVIEW -> "重新审图未完成，请刷新后重试"
                    Kind.CLEAR -> "候选图片没有清理完成，请刷新后重试"
                }
            } finally { if (current(stamp)) { busy = false; previewLoading = false } }
        }
    }
    private fun current(stamp: Stamp) = stamp.chat == chat && stamp.revision == bindingRevision && stamp.serial == serial
    private suspend fun checkCurrent(stamp: Stamp) {
        currentCoroutineContext().ensureActive()
        if (!current(stamp)) throw CancellationException("Candidate view changed")
    }
    fun dispose() {
        bindingRevision++; serial++; job?.cancel(); job = null; chat = null; releasePreview()
        items = emptyList(); confirmation = null; nextCursor = null; cursor = null; trail = emptyList()
        canGoBack = false; pageNumber = 1; busy = false; previewLoading = false; error = null
    }
    private enum class Kind { PAGE, PREVIEW, REVIEW, RETRY, CLEAR }
    private data class Stamp(val chat: String, val revision: Long, val serial: Long)
}

internal fun scenePhotoStatus(status: String): String = when (status) {
    "reserved" -> "等待处理"
    "running" -> "正在准备"
    "unknown" -> "结果未确认"
    "failed" -> "生成失败"
    "success" -> "已生成，待复核"
    "stale" -> "资料已更新"
    "review_APPROVED" -> "视觉复核通过"
    "review_REJECTED" -> "需要调整"
    "review_UNKNOWN" -> "复核结果未确认"
    "review_UNAVAILABLE" -> "暂不能审图"
    "removed" -> "候选图片已清理"
    "invalid" -> "候选记录无法读取"
    else -> "状态待确认"
}
