package com.zhiyu.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.zhiyu.app.domain.ChatPhotoRules
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream

/** Whole-image equality only. This is deliberately not face or character similarity. */
internal object OcPhotoPixelMatch {
    fun same(reference: ByteArray, current: ByteArray): Boolean = try {
        decode(reference)?.let { first ->
            try {
                decode(current)?.let { second ->
                    try { equal(first, second) } finally { second.recycle() }
                } ?: false
            } finally { first.recycle() }
        } ?: false
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        false // Optional exact-image evidence; decoding failure never becomes a claimed match.
    }

    private fun equal(first: Bitmap, second: Bitmap): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        val left = IntArray(first.width); val right = IntArray(second.width)
        for (y in 0 until first.height) {
            first.getPixels(left, 0, first.width, 0, y, first.width, 1)
            second.getPixels(right, 0, second.width, 0, y, second.width, 1)
            if (!left.contentEquals(right)) return false
        }
        return true
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.size !in 1..ChatPhotoRules.MAX_ORIGINAL_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // No sampled fingerprint can establish whole-image equality. Larger pictures keep the
        // original SHA/explicit user attribution/normal model comparison routes instead.
        if (bounds.outWidth !in 1..ChatPhotoRules.UPLOAD_SIDE || bounds.outHeight !in 1..ChatPhotoRules.UPLOAD_SIDE ||
            bounds.outWidth.toLong() * bounds.outHeight > ChatPhotoRules.UPLOAD_PIXELS ||
            bounds.outMimeType !in setOf("image/png", "image/jpeg", "image/webp")) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        try {
            val orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = ChatPhotoImageProcessor().orientationMatrix(orientation)
            if (matrix.isIdentity) return bitmap
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
                .also { if (it !== bitmap) bitmap.recycle() }
        } catch (failure: Exception) { bitmap.recycle(); throw failure }
    }
}
