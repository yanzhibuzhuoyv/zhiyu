package com.zhiyu.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.zhiyu.app.domain.ChatPhotoPayload
import com.zhiyu.app.domain.ChatPhotoRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Creates an EXIF/GPS-free upload; does not modify the original byte array or its digest. */
class ChatPhotoImageProcessor {
    fun normalize(original: ByteArray): ChatPhotoPayload {
        require(original.size in 1..ChatPhotoRules.MAX_ORIGINAL_BYTES) { "请选择 5 MiB 以内的聊天图片" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        require(bounds.outWidth in 1..ChatPhotoRules.MAX_SIDE && bounds.outHeight in 1..ChatPhotoRules.MAX_SIDE &&
            bounds.outWidth.toLong() * bounds.outHeight <= ChatPhotoRules.MAX_PIXELS &&
            bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp")) {
            "请选择 2000 万像素以内的 JPEG、PNG 或 WebP 图片"
        }
        val decoded = decode(original, bounds)
        try {
            val output = ByteArrayOutputStream()
            val format = if (decoded.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            check(decoded.compress(format, 90, output)) { "图片处理失败，原图已保留" }
            val bytes = output.toByteArray()
            require(bytes.size in 1..ChatPhotoRules.MAX_UPLOAD_BYTES) { "图片处理后仍过大，请选择较小的图片" }
            val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
            return ChatPhotoPayload(ChatPhotoRules.sha(original), ChatPhotoRules.sha(bytes), mime, bytes)
        } finally { decoded.recycle() }
    }

    private fun decode(bytes: ByteArray, bounds: BitmapFactory.Options): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val ratio = minOf(1.0, ChatPhotoRules.UPLOAD_SIDE.toDouble() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * ratio).roundToInt()), maxOf(1, (info.size.height * ratio).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
        }
        val orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > ChatPhotoRules.UPLOAD_SIDE) sample *= 2
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
