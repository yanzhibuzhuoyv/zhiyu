package rig2d.parts

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Straight ARGB working image. pixelSha256 is not the original PNG file SHA. */
class PartRaster(val width: Int, val height: Int, argb: IntArray) {
    private val pixels: IntArray
    val pixelSha256: String

    init {
        val count = PixelMask.checkedCount(width, height)
        require(argb.size == count) { "原图ARGB像素数量错误" }
        pixels = argb.copyOf()
        pixelSha256 = hashPixels()
    }
    fun toArgb(): IntArray = pixels.copyOf()
    fun alphaMask(): PixelMask = PixelMask.fromBooleans(width, height, BooleanArray(pixels.size) {
        pixels[it] ushr 24 != 0
    })
    fun selectedArgb(mask: PixelMask): IntArray {
        require(mask.width == width && mask.height == height) { "遮罩与原图尺寸不一致" }
        return IntArray(pixels.size) { if (mask[it]) pixels[it] else 0 }
    }
    private fun hashPixels(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("OcPartRaster-v1\u0000".toByteArray(Charsets.UTF_8))
        digest.update(ByteBuffer.allocate(8).putInt(width).putInt(height).array())
        val block = ByteBuffer.allocate(4096)
        for (pixel in pixels) {
            block.putInt(pixel)
            if (!block.hasRemaining()) {
                digest.update(block.array())
                block.clear()
            }
        }
        if (block.position() > 0) digest.update(block.array(), 0, block.position())
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
