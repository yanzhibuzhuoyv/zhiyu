package com.zhiyu.app.data

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.zhiyu.app.domain.ApiFailure
import com.zhiyu.app.domain.ChatPhotoPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

fun interface ChatPhotoOcr { suspend fun read(photo: ChatPhotoPayload): String }

/** Bundled Chinese/Latin OCR on the phone. No Play download, remote URL or credentials. */
class OcrChatPhotoReader : ChatPhotoOcr {
    companion object { private val pending = Semaphore(2) }
    override suspend fun read(photo: ChatPhotoPayload): String = withContext(Dispatchers.Default) {
        if (!pending.tryAcquire()) throw ApiFailure(503, "图片正在读取，请稍后再试", "photo_ocr_busy")
        val released = AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) pending.release() }
        val bytes = photo.bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) { release(); throw IllegalArgumentException("图片副本无法读取，原图已保留") }
        val reader = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
        catch (error: Exception) { bitmap.recycle(); release(); throw error }
        var completionOwnsBitmap = false
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { reader.close() }
                val task = reader.process(InputImage.fromBitmap(bitmap, 0))
                completionOwnsBitmap = true
                task.addOnCompleteListener { task ->
                    try {
                        if (continuation.isActive) {
                            val result = if (!task.isSuccessful) Result.failure(ApiFailure(502, "这张图片里的文字暂时没看清", "photo_ocr_failed"))
                            else {
                                val text = task.result.text
                                if (text.length > 8000) Result.failure(ApiFailure(422, "图片文字过多，请分成几张发送", "photo_ocr_limit"))
                                else Result.success(text)
                            }
                            continuation.resumeWith(result)
                        }
                    } finally { reader.close(); bitmap.recycle(); release() }
                }
            }
        } catch (error: Exception) {
            // Completion owns the bitmap after process(); this path handles synchronous setup failures only.
            if (!completionOwnsBitmap && !released.get()) { reader.close(); bitmap.recycle(); release() }
            throw error
        }
    }
}
