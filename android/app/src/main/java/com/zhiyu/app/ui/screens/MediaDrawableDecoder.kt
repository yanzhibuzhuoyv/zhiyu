package com.zhiyu.app.ui.screens

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.zhiyu.app.domain.MediaRules
import java.nio.ByteBuffer

/** IO-only decoding. Neither backend assets nor animation dimensions may allocate unbounded pixels. */
object MediaDrawableDecoder {
    fun decode(resources: Resources, bytes: ByteArray, sticker: Boolean): Drawable {
        require(bytes.isNotEmpty() && bytes.size <= MediaRules.MAX_BYTES) { "图片大小无效" }
        val maximum = if (sticker) 256 else 1024
        return if (Build.VERSION.SDK_INT >= 28) decodeModern(bytes, maximum) else decodeLegacy(resources, bytes, maximum)
    }

    private fun decodeModern(bytes: ByteArray, maximum: Int): Drawable =
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            checkDimensions(info.size.width, info.size.height)
            val ratio = maxOf(1.0, maxOf(info.size.width, info.size.height).toDouble() / maximum)
            decoder.setTargetSize(maxOf(1, (info.size.width / ratio).toInt()), maxOf(1, (info.size.height / ratio).toInt()))
            decoder.setOnPartialImageListener { false }
        }

    private fun decodeLegacy(resources: Resources, bytes: ByteArray, maximum: Int): Drawable {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        checkDimensions(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maximum) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("图片无法解码")
        return BitmapDrawable(resources, bitmap)
    }

    private fun checkDimensions(width: Int, height: Int) {
        require(width > 0 && height > 0 && width.toLong() * height <= 20_000_000) { "图片尺寸过大或无效" }
    }
}
