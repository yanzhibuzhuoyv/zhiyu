package com.zhiyu.app.domain

import kotlin.math.sqrt
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Large originals are retained; every pixel allocation has its own smaller working limit. */
object PhotoInputRules {
    fun boundedBytes(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val next = input.read()
                if (next < 0) break
                require(output.size() < ChatPhotoRules.MAX_ORIGINAL_BYTES) { "原图超过 64 MiB，请换一张较小的图片" }
                output.write(next)
            } else {
                require(output.size().toLong() + count <= ChatPhotoRules.MAX_ORIGINAL_BYTES) { "原图超过 64 MiB，请换一张较小的图片" }
                output.write(buffer, 0, count)
            }
        }
        require(output.size() > 0) { "这张图片是空文件，请重新选择" }
        return output.toByteArray()
    }

    fun checkSource(width: Int, height: Int) {
        require(width in 1..ChatPhotoRules.MAX_SIDE && height in 1..ChatPhotoRules.MAX_SIDE &&
            width.toLong() * height <= ChatPhotoRules.MAX_PIXELS) {
            "图片无法读取，或超过 1.2 亿像素／单边 32768 像素"
        }
    }

    fun uploadSize(width: Int, height: Int): Pair<Int, Int> {
        checkSource(width, height)
        val ratio = minOf(1.0, ChatPhotoRules.UPLOAD_SIDE.toDouble() / maxOf(width, height),
            sqrt(ChatPhotoRules.UPLOAD_PIXELS.toDouble() / (width.toLong() * height)))
        return maxOf(1, (width * ratio).toInt()) to maxOf(1, (height * ratio).toInt())
    }

    fun sampleSize(width: Int, height: Int, edge: Int, pixels: Long): Int {
        checkSource(width, height)
        require(edge in 1..ChatPhotoRules.MAX_SIDE && pixels in 1..ChatPhotoRules.MAX_PIXELS)
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > edge || (height.toLong() + sample - 1) / sample > edge ||
            ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) > pixels) sample *= 2
        return sample
    }
}
