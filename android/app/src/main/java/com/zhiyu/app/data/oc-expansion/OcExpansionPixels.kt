package com.zhiyu.app.data.ocexpansion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zhiyu.app.data.ChatPhotoImageProcessor
import com.zhiyu.app.domain.*
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Complete-image transport derivatives; never cut out, mirror, or overwrite the original. */
internal object OcExpansionPixels {
    fun png(original: ByteArray, budget: Int = Image2Protocol.MAX_IMAGE_BYTES): ByteArray {
        val normalized = ChatPhotoImageProcessor().normalize(original)
        var bitmap = requireNotNull(BitmapFactory.decodeByteArray(normalized.bytes, 0, normalized.byteCount)) { "原图无法解码" }
        try {
            repeat(6) { attempt ->
                val limit = if (attempt == 0) 1536 else maxOf(128, maxOf(bitmap.width, bitmap.height) / 2)
                val longest = maxOf(bitmap.width, bitmap.height)
                if (longest > limit) {
                    val scale = limit.toDouble() / longest
                    val small = Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).roundToInt().coerceAtLeast(1),
                        (bitmap.height*scale).roundToInt().coerceAtLeast(1),true)
                    if (small !== bitmap) {bitmap.recycle();bitmap=small}
                }
                val output=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output))
                if(output.size()<=budget)return output.toByteArray()
            }
            throw IllegalArgumentException("参考图发送副本仍过大，原图已保留")
        } finally {bitmap.recycle()}
    }
    fun review(original: ByteArray): ChatPhotoPayload {
        val bytes=png(original,2*1024*1024)
        return ChatPhotoPayload(ChatPhotoRules.sha(original),ChatPhotoRules.sha(bytes),"image/png",bytes)
    }
}
