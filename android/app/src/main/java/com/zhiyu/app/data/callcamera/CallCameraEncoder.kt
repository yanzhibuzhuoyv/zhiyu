package com.zhiyu.app.data.callcamera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import com.zhiyu.app.domain.CallCameraFrameRules
import com.zhiyu.app.domain.ChatPhotoPayload
import com.zhiyu.app.domain.ChatPhotoRules
import java.io.ByteArrayOutputStream

/** Ephemeral JPEG derivative only. Neither camera frames nor JPEGs are persisted. */
internal class CallCameraEncoder {
    fun encode(image: ImageProxy): ChatPhotoPayload {
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3)
        val rect = image.cropRect
        val planes = image.planes.map { CallCameraPlane(it.buffer, it.rowStride, it.pixelStride) }
        val raster = CallCameraYuv.convert(image.width, image.height,
            CallCameraCrop(rect.left, rect.top, rect.right, rect.bottom), image.imageInfo.rotationDegrees,
            planes[0], planes[1], planes[2])
        return encode(raster)
    }

    internal fun encode(pixels: CallCameraPixels): ChatPhotoPayload {
        require(pixels.width in 1..CallCameraYuv.MAX_SIDE && pixels.height in 1..CallCameraYuv.MAX_SIDE &&
            pixels.colors.size == pixels.width * pixels.height)
        val bitmap = Bitmap.createBitmap(pixels.colors, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        try {
            for (quality in listOf(85, 70, 55, 40, 25)) {
                val output = BoundedJpeg()
                val success = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                check(success) { "摄像头画面暂时无法处理" }
                if (!output.exceeded) {
                    val bytes = output.toByteArray()
                    require(bytes.isNotEmpty())
                    val digest = ChatPhotoRules.sha(bytes)
                    return ChatPhotoPayload(digest, digest, "image/jpeg", bytes)
                }
            }
            throw IllegalArgumentException("摄像头画面过大，请稍后重试")
        } finally { bitmap.recycle() }
    }

    private class BoundedJpeg : ByteArrayOutputStream() {
        var exceeded = false
            private set
        override fun write(value: Int) {
            if (count >= MAX_BYTES) exceeded = true
            if (!exceeded) super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (count.toLong() + length > MAX_BYTES) exceeded = true
            if (!exceeded) super.write(bytes, offset, length)
        }
    }

    companion object { const val MAX_BYTES = CallCameraFrameRules.MAX_BYTES }
}
