package com.zhiyu.app.data.local.qwen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zhiyu.app.domain.AgnesImageProtocol
import com.zhiyu.app.domain.ChatPhotoRules
import java.io.File

/** Native output is exactly 512². Check its header before allocating decoded pixels. */
object QwenDecodedPng {
    fun read(file: File): ByteArray {
        require(file.length() in 20..ChatPhotoRules.MAX_UPLOAD_BYTES.toLong()) { "本机PNG大小无效" }
        return file.readBytes().also(::validate)
    }
    fun decode(bytes: ByteArray): Bitmap {
        validate(bytes)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "本机图片未解码" }
    }
    private fun validate(bytes: ByteArray) {
        require(bytes.size in 20..ChatPhotoRules.MAX_UPLOAD_BYTES && AgnesImageProtocol.imageMime(bytes) == "image/png" &&
            bytes.takeLast(8).toByteArray().contentEquals(byteArrayOf(73,69,78,68,-82,66,96,-126))) { "本机PNG未完整写入" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == 512 && bounds.outHeight == 512 && bounds.outMimeType == "image/png") { "本机图片尺寸无效" }
    }
}
