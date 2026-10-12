package com.zhiyu.app.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import com.zhiyu.app.domain.MediaRules
import com.zhiyu.app.domain.PortraitRefinementRules
import com.zhiyu.app.domain.PortraitStroke
import com.zhiyu.app.domain.ChatPhotoRules
import com.zhiyu.app.domain.PhotoInputRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.*

data class LocalPortrait(val subject: ByteArray, val width: Int, val height: Int,
    val bounds: List<Int>, val method: String, val sourceWidth: Int = width, val sourceHeight: Int = height)

/** Offline salient foreground preview; user confirmation is always required. */
class LocalPortraitProcessor(context: Context) {
    private val app = context.applicationContext
    companion object {
        private const val MODEL_SHA = "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8"
        const val MAX_WORK_EDGE = 1536
        private val inferenceLock = Any()
        fun decode(bytes: ByteArray, maxEdge: Int = MAX_WORK_EDGE): Bitmap {
            require(bytes.size in 1..ChatPhotoRules.MAX_ORIGINAL_BYTES) { "图片须在 64 MiB 以内" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            PhotoInputRules.checkSource(options.outWidth, options.outHeight)
            require(maxEdge in 1..MAX_WORK_EDGE)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            options.inSampleSize = PhotoInputRules.sampleSize(options.outWidth, options.outHeight, maxEdge, 4_000_000)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw IllegalArgumentException("图片无法解码")
            val orientation = try { orientation(bytes, options.outMimeType) }
            catch (error: Exception) { bitmap.recycle(); throw error }
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
                else -> return bitmap
            }
            return try {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    .also { if (it !== bitmap) bitmap.recycle() }
            } catch (error: Exception) { bitmap.recycle(); throw error }
        }
        private fun png(bitmap: Bitmap): ByteArray {
            val output = object : ByteArrayOutputStream() {
                override fun write(value: Int) { require(count + 1 <= MediaRules.MAX_BYTES) { "人物图片超过 5 MB" }; super.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    require(length >= 0 && count.toLong() + length <= MediaRules.MAX_BYTES) { "人物图片超过 5 MB" }
                    super.write(bytes, offset, length)
                }
            }
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "人物图片无法保存" }
            return output.toByteArray().also { require(it.size <= MediaRules.MAX_BYTES) { "分离后的图片超过 5 MB，请换用较小参考图" } }
        }
        private fun alpha(bitmap: Bitmap): ByteArray {
            val result = ByteArray(bitmap.width * bitmap.height)
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in row.indices) result[y * bitmap.width + x] = Color.alpha(row[x]).toByte()
            }
            return result
        }
        private fun originalDimensions(bytes: ByteArray): Pair<Int, Int> {
            val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
            PhotoInputRules.checkSource(info.outWidth, info.outHeight)
            val orientation = orientation(bytes, info.outMimeType)
            return if (orientation in setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)) info.outHeight to info.outWidth
                else info.outWidth to info.outHeight
        }
        private fun orientation(bytes: ByteArray, mime: String?): Int =
            if (mime in setOf("image/jpeg", "image/png", "image/webp")) ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            else ExifInterface.ORIENTATION_NORMAL
        private fun withOriginal(result: LocalPortrait, bytes: ByteArray): LocalPortrait {
            val (width, height) = originalDimensions(bytes)
            return result.copy(sourceWidth = width, sourceHeight = height)
        }
        private fun compose(source: Bitmap, mask: ByteArray, method: String): LocalPortrait {
            val width = source.width; val height = source.height
            require(mask.size == width * height)
            var left = width; var top = height; var right = 0; var bottom = 0
            val row = IntArray(width)
            for (y in 0 until height) {
                source.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val a = min(mask[y * width + x].toInt() and 255, Color.alpha(row[x]))
                    mask[y * width + x] = a.toByte()
                    if (a > 0) { left = min(left, x); top = min(top, y); right = max(right, x + 1); bottom = max(bottom, y + 1) }
                }
            }
            require(right > left && bottom > top) { "人物区域为空，请用保留笔刷选中角色" }
            val result = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
            try {
                for (y in top until bottom) {
                    source.getPixels(row, 0, width, 0, y, width, 1)
                    for (x in left until right) row[x] = (row[x] and 0x00ffffff) or ((mask[y * width + x].toInt() and 255) shl 24)
                    result.setPixels(row, left, width, 0, y - top, right - left, 1)
                }
                return LocalPortrait(png(result), width, height, listOf(left, top, right, bottom), method)
            } finally { result.recycle() }
        }
    }
    fun extract(bytes: ByteArray): LocalPortrait {
        val source = decode(bytes)
        try {
            val existing = alpha(source)
            if (hasSubjectAlpha(existing, source.width, source.height)) return withOriginal(compose(source, existing, "existing_alpha"), bytes)
            val predicted = predict(source)
            val scaled = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
            try {
                val pixels = IntArray(predicted.size) { (predicted[it] shl 24) or 0x00ffffff }
                scaled.setPixels(pixels, 0, 320, 0, 0, 320, 320)
                val full = Bitmap.createScaledBitmap(scaled, source.width, source.height, true)
                try {
                    val mask = alpha(full)
                    for (i in mask.indices) mask[i] = ((mask[i].toInt() and 255) * (existing[i].toInt() and 255) / 255).toByte()
                    return withOriginal(compose(source, mask, "u2netp"), bytes)
                } finally { if (full !== scaled) full.recycle() }
            } finally { scaled.recycle() }
        } finally { source.recycle() }
    }
    /** A blank, explicitly manual canvas is not a successful model prediction. */
    fun manualStart(bytes: ByteArray): LocalPortrait {
        val source = decode(bytes)
        try {
            val blank = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            try { return withOriginal(LocalPortrait(png(blank), source.width, source.height,
                listOf(0, 0, source.width, source.height), "manual_selection"), bytes) }
            finally { blank.recycle() }
        } finally { source.recycle() }
    }
    fun refine(original: ByteArray, subject: ByteArray, bounds: List<Int>, strokes: List<PortraitStroke>): LocalPortrait {
        val checked = PortraitRefinementRules.check(strokes)
        val source = decode(original)
        try {
            require(bounds.size == 4) { "人物范围无效" }
            val (left, top, right, bottom) = bounds
            require(left >= 0 && top >= 0 && right > left && bottom > top && right <= source.width && bottom <= source.height) { "人物范围超出图片" }
            val current = decode(subject)
            val mask = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ALPHA_8)
            try {
                require(current.width == right - left && current.height == bottom - top) { "人物图片与选取范围不一致" }
                val canvas = Canvas(mask)
                canvas.drawBitmap(current, left.toFloat(), top.toFloat(), null)
                checked.forEach { stroke -> drawStroke(canvas, source.width, source.height, stroke) }
                val buffer = ByteBuffer.allocate(mask.rowBytes * mask.height)
                mask.copyPixelsToBuffer(buffer)
                val raw = buffer.array()
                val compact = ByteArray(source.width * source.height)
                for (y in 0 until source.height) System.arraycopy(raw, y * mask.rowBytes, compact, y * source.width, source.width)
                return withOriginal(compose(source, compact, "manual_refinement"), original)
            } finally { current.recycle(); mask.recycle() }
        } finally { source.recycle() }
    }
    fun hasVisiblePixels(bytes: ByteArray): Boolean {
        val image = decode(bytes)
        try { return alpha(image).any { (it.toInt() and 255) > 0 } } finally { image.recycle() }
    }
    private fun drawStroke(canvas: Canvas, width: Int, height: Int, stroke: PortraitStroke) {
        val radius = max(1, (stroke.radius * min(width, height)).roundToInt()).toFloat()
        val paint = Paint().apply {
            color = Color.WHITE; isAntiAlias = false; strokeWidth = radius * 2 + 1
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            xfermode = PorterDuffXfermode(if (stroke.action == "remove") PorterDuff.Mode.CLEAR else PorterDuff.Mode.SRC)
        }
        val points = stroke.points.map { PointF((it.x * (width - 1)).roundToInt().toFloat(), (it.y * (height - 1)).roundToInt().toFloat()) }
        for (i in 1 until points.size) canvas.drawLine(points[i-1].x, points[i-1].y, points[i].x, points[i].y, paint)
        points.forEach { canvas.drawCircle(it.x, it.y, radius, paint) }
    }
    private fun hasSubjectAlpha(mask: ByteArray, width: Int, height: Int): Boolean {
        val visible = mask.count { (it.toInt() and 255) > 8 }.toDouble() / mask.size
        var transparent = 0; var border = 0
        for (y in 0 until height) for (x in 0 until width) if (x == 0 || y == 0 || x == width-1 || y == height-1) {
            border++; if ((mask[y * width + x].toInt() and 255) <= 8) transparent++
        }
        return visible in .001.. .995 && transparent.toDouble() / border >= .05
    }
    private fun predict(source: Bitmap): IntArray = synchronized(inferenceLock) {
        val bytes = app.assets.open("u2netp.onnx").use { it.readBytes() }
        check(LocalMediaStore.sha(bytes) == MODEL_SHA) { "人物分离模型校验失败" }
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(2)
            environment.createSession(bytes, options).use { session ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(input(source)), longArrayOf(1, 3, 320, 320)).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                        val output = result[0] as? OnnxTensor ?: throw IllegalStateException("人物模型输出无效")
                        val buffer = output.floatBuffer ?: throw IllegalStateException("人物模型输出格式无效")
                        require(buffer.remaining() == 320 * 320) { "人物模型输出尺寸无效" }
                        val values = FloatArray(320 * 320); buffer.get(values)
                        require(values.all { it.isFinite() }) { "人物模型返回无效数值" }
                        val low = values.minOrNull()!!; val high = values.maxOrNull()!!
                        require(high - low > .000001f) { "没有识别到可用的人物轮廓" }
                        val mask = IntArray(values.size) { ((values[it] - low) / (high - low) * 255).roundToInt().coerceIn(0, 255) }
                        val fraction = mask.count { it > 8 }.toFloat() / mask.size
                        require(fraction in .001f.. .995f) { "人物轮廓不明确，请手动修整" }
                        mask
                    }
                }
            }
        }
    }
    private fun input(source: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(source, 320, 320, true)
        try {
            val pixels = IntArray(320 * 320); resized.getPixels(pixels, 0, 320, 0, 0, 320, 320)
            val maximum = pixels.maxOf { max(Color.red(it), max(Color.green(it), Color.blue(it))) }.coerceAtLeast(1).toFloat()
            val means = floatArrayOf(.485f, .456f, .406f); val deviations = floatArrayOf(.229f, .224f, .225f)
            return FloatArray(3 * pixels.size) { index ->
                val channel = index / pixels.size; val color = pixels[index % pixels.size]
                val value = when (channel) { 0 -> Color.red(color); 1 -> Color.green(color); else -> Color.blue(color) }
                (value / maximum - means[channel]) / deviations[channel]
            }
        } finally { if (resized !== source) resized.recycle() }
    }
}
