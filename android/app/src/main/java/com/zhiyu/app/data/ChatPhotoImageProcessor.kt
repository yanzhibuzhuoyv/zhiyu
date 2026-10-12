package com.zhiyu.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.zhiyu.app.domain.ChatPhotoPayload
import com.zhiyu.app.domain.ChatPhotoRules
import com.zhiyu.app.domain.PhotoInputRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Creates an EXIF/GPS-free upload; does not modify the original byte array or its digest. */
class ChatPhotoImageProcessor {
    fun normalize(original: ByteArray): ChatPhotoPayload {
        require(original.size in 1..ChatPhotoRules.MAX_ORIGINAL_BYTES) { "请选择 64 MiB 以内的聊天图片" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        PhotoInputRules.checkSource(bounds.outWidth, bounds.outHeight)
        require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp")) { "请选择 JPEG、PNG 或 WebP 图片" }
        val decoded = decode(original, bounds)
        try {
            val (bytes, mime) = encodeWithinBudget(decoded)
            return ChatPhotoPayload(ChatPhotoRules.sha(original), ChatPhotoRules.sha(bytes), mime, bytes)
        } finally { decoded.recycle() }
    }

    private fun encodeWithinBudget(original: Bitmap): Pair<ByteArray, String> {
        var working = original
        try {
            repeat(10) {
                val alpha = working.hasAlpha()
                val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val qualities = if (alpha) listOf(100) else listOf(95, 90, 85)
                for (quality in qualities) encode(working, format, quality)?.let {
                    return it to if (alpha) "image/png" else "image/jpeg"
                }
                val width = maxOf(1, (working.width * .8).toInt())
                val height = maxOf(1, (working.height * .8).toInt())
                require(width != working.width || height != working.height) { "图片处理后仍过大，原图已保留" }
                val smaller = Bitmap.createScaledBitmap(working, width, height, true)
                if (working !== original) working.recycle()
                working = smaller
            }
            throw IllegalArgumentException("图片处理后仍过大，原图已保留")
        } finally { if (working !== original) working.recycle() }
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val output = object : ByteArrayOutputStream() {
            var exceeded = false
            override fun write(value: Int) {
                if (count >= ChatPhotoRules.MAX_UPLOAD_BYTES) exceeded = true
                if (!exceeded) super.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (count.toLong() + length > ChatPhotoRules.MAX_UPLOAD_BYTES) exceeded = true
                if (!exceeded) super.write(bytes, offset, length)
            }
        }
        val compressed = bitmap.compress(format, quality, output)
        if (output.exceeded) return null // Do not throw through the platform's native encoder callback.
        check(compressed) { "图片处理失败，原图已保留" }
        return output.toByteArray().also { require(it.isNotEmpty()) { "图片处理失败，原图已保留" } }
    }

    private fun decode(bytes: ByteArray, bounds: BitmapFactory.Options): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val (width, height) = PhotoInputRules.uploadSize(info.size.width, info.size.height)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
        }
        val orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val sample = PhotoInputRules.sampleSize(bounds.outWidth, bounds.outHeight,
            ChatPhotoRules.UPLOAD_SIDE, ChatPhotoRules.UPLOAD_PIXELS)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw IllegalArgumentException("图片损坏，无法发送；原图未改写")
        val matrix = orientationMatrix(orientation)
        return try {
            if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (error: Exception) { bitmap.recycle(); throw error }
    }

    internal fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
}
