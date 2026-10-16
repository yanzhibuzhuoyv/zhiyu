package com.zhiyu.app.data

import android.graphics.Bitmap
import com.zhiyu.app.domain.ChatPhotoPayload
import com.zhiyu.app.domain.ChatPhotoRules
import java.io.ByteArrayOutputStream

/** Small EXIF/GPS-free visual-comparison derivative; the user's primary photo retains its budget. */
internal object OcReferencePhotoEncoder {
    fun encode(original: ByteArray, budget: Int): ChatPhotoPayload? {
        require(budget in 1..OcPhotoIdentityContext.MAX_REFERENCE_BYTES)
        var bitmap = LocalPortraitProcessor.decode(original, 512)
        try {
            repeat(9) {
                val out = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "恋人参考图暂时无法准备" }
                val bytes = out.toByteArray()
                if (bytes.size <= budget) return ChatPhotoPayload(ChatPhotoRules.sha(original), ChatPhotoRules.sha(bytes), "image/png", bytes)
                val width = (bitmap.width * .7).toInt().coerceAtLeast(1)
                val height = (bitmap.height * .7).toInt().coerceAtLeast(1)
                if (width == bitmap.width && height == bitmap.height) return null
                val smaller = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (smaller !== bitmap) bitmap.recycle()
                bitmap = smaller
            }
            return null
        } finally { bitmap.recycle() }
    }
}
