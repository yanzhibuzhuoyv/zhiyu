package com.zhiyu.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.zhiyu.app.domain.AppearanceRules
import com.zhiyu.app.domain.AppearanceTarget
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Call on Dispatchers.IO. Bounds both encoded bytes and decoded pixels before allocating an image. */
class AppearanceImageReader(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun read(value: String, target: AppearanceTarget): Bitmap {
        val uri = Uri.parse(AppearanceRules.documentUri(value))
        val bytes = boundedBytes(uri)
        return if (Build.VERSION.SDK_INT >= 28) decodeModern(bytes, target) else decodeLegacy(bytes, target)
    }

    private fun boundedBytes(uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IllegalArgumentException("无法打开这张图片，请重新选择")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= AppearanceRules.MAX_IMAGE_BYTES) { "图片超过 25 MB，请换一张较小的图片" }
                output.write(buffer, 0, count)
            }
            require(total > 0) { "这张图片是空文件，请重新选择" }
            output.toByteArray()
        }
    }

    @RequiresApi(28)
    private fun decodeModern(bytes: ByteArray, target: AppearanceTarget): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val size = AppearanceRules.decodedSize(info.size.width, info.size.height, target)
            decoder.setTargetSize(size.width, size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { false }
        }

    private fun decodeLegacy(bytes: ByteArray, target: AppearanceTarget): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val size = AppearanceRules.decodedSize(bounds.outWidth, bounds.outHeight, target)
        var sample = 1
        while (bounds.outWidth / sample > size.width || bounds.outHeight / sample > size.height) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("图片格式不受支持，请换一张图片")
    }
}
