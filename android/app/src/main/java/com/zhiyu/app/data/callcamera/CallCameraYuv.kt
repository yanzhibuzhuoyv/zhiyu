package com.zhiyu.app.data.callcamera

import java.nio.ByteBuffer

internal data class CallCameraPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)
internal data class CallCameraCrop(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class CallCameraPixels(val width: Int, val height: Int, val colors: IntArray)

/** Samples camera planes directly into a bounded raster. No full-resolution RGB copy or mirroring. */
internal object CallCameraYuv {
    const val MAX_SIDE = 768

    fun convert(width: Int, height: Int, crop: CallCameraCrop, rotation: Int,
        y: CallCameraPlane, u: CallCameraPlane, v: CallCameraPlane): CallCameraPixels {
        require(width in 1..16384 && height in 1..16384)
        require(crop.left in 0 until width && crop.top in 0 until height &&
            crop.right > crop.left && crop.right <= width && crop.bottom > crop.top && crop.bottom <= height)
        require(rotation in setOf(0, 90, 180, 270))
        val luma = reader(y, crop.right - 1, crop.bottom - 1)
        val chromaU = reader(u, (crop.right - 1) / 2, (crop.bottom - 1) / 2)
        val chromaV = reader(v, (crop.right - 1) / 2, (crop.bottom - 1) / 2)
        val sourceWidth = crop.right - crop.left
        val sourceHeight = crop.bottom - crop.top
        val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(sourceWidth, sourceHeight))
        val sampledWidth = maxOf(1, (sourceWidth * scale).toInt())
        val sampledHeight = maxOf(1, (sourceHeight * scale).toInt())
        val turned = rotation == 90 || rotation == 270
        val outputWidth = if (turned) sampledHeight else sampledWidth
        val outputHeight = if (turned) sampledWidth else sampledHeight
        val output = IntArray(outputWidth * outputHeight)
        repeat(sampledHeight) { row ->
            val sourceY = crop.top + row * sourceHeight / sampledHeight
            repeat(sampledWidth) { col ->
                val sourceX = crop.left + col * sourceWidth / sampledWidth
                val pixel = rgb(luma.at(sourceX, sourceY), chromaU.at(sourceX / 2, sourceY / 2),
                    chromaV.at(sourceX / 2, sourceY / 2))
                val index = when (rotation) {
                    90 -> col * outputWidth + sampledHeight - row - 1
                    180 -> (sampledHeight - row - 1) * outputWidth + sampledWidth - col - 1
                    270 -> (sampledWidth - col - 1) * outputWidth + row
                    else -> row * outputWidth + col
                }
                output[index] = pixel
            }
        }
        return CallCameraPixels(outputWidth, outputHeight, output)
    }

    private fun reader(plane: CallCameraPlane, maxX: Int, maxY: Int): PlaneReader {
        require(plane.rowStride > 0 && plane.pixelStride > 0)
        val lastColumn = maxX.toLong() * plane.pixelStride
        require(lastColumn < plane.rowStride)
        val buffer = plane.buffer.duplicate()
        val lastIndex = buffer.position().toLong() + maxY.toLong() * plane.rowStride + lastColumn
        require(lastIndex in 0 until buffer.limit().toLong()) { "摄像头帧不完整" }
        return PlaneReader(buffer, buffer.position(), plane.rowStride, plane.pixelStride)
    }

    private class PlaneReader(val buffer: ByteBuffer, val offset: Int, val row: Int, val pixel: Int) {
        fun at(x: Int, y: Int): Int = buffer.get(offset + y * row + x * pixel).toInt() and 255
    }

    private fun rgb(y: Int, u: Int, v: Int): Int {
        val light = 298 * (y - 16).coerceAtLeast(0)
        val blue = u - 128
        val red = v - 128
        val r = ((light + 409 * red + 128) shr 8).coerceIn(0, 255)
        val g = ((light - 100 * blue - 208 * red + 128) shr 8).coerceIn(0, 255)
        val b = ((light + 516 * blue + 128) shr 8).coerceIn(0, 255)
        return (255 shl 24) or (r shl 16) or (g shl 8) or b
    }
}
