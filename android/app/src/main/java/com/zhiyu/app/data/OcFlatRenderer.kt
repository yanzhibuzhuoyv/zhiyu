package com.zhiyu.app.data

import android.graphics.*
import com.zhiyu.app.domain.*
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Fixed-component illustration renderer. Photo RGB is never sent through a generative model here. */
object OcFlatRenderer {
    fun png(identity: OcIdentity, scene: OcScene = OcScene(), width: Int = 768, height: Int = 1024,
        source: Bitmap? = null, background: Bitmap? = null): ByteArray {
        val bitmap = render(identity, scene, width, height, source, background)
        return try { ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() } }
        finally { bitmap.recycle() }
    }
    fun render(identity: OcIdentity, scene: OcScene, width: Int, height: Int,
        source: Bitmap? = null, background: Bitmap? = null): Bitmap {
        OcIdentityRules.check(identity); OcIdentityRules.scene(scene)
        require(width in 32..1536 && height in 32..1536 && width.toLong() * height <= 2_400_000)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(bitmap), identity, scene, width, height, source, background)
            return bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
    fun draw(canvas: Canvas, identity: OcIdentity, scene: OcScene, width: Int, height: Int,
        source: Bitmap? = null, background: Bitmap? = null) {
        OcIdentityRules.check(identity); OcIdentityRules.scene(scene)
        require(width > 0 && height > 0)
        canvas.drawColor(scene.background)
        background?.let { drawFitted(canvas, it, width.toFloat(), height.toFloat(), fill = true) }
        if (identity.route == "photo_subject") {
            require(source != null && !source.isRecycled) { "请先确认角色图片" }
            photo(canvas, identity.rig, scene.motion, source, width, height)
        } else {
            require(identity.route == "parts_2d") { "请选择二维角色制作路线" }
            val bounds = bounds(identity)
            val scale = min(width / bounds.width(), height / bounds.height()) * scene.zoom
            canvas.save(); canvas.translate(width / 2f, height / 2f); canvas.scale(scale, scale)
            canvas.translate(-bounds.centerX(), -bounds.centerY())
            Character(canvas, identity.body, scene.motion).draw(identity.parts)
            canvas.restore()
        }
    }
    private fun bounds(identity: OcIdentity): RectF {
        val result = RectF(80f, 20f, 920f, maxOf(1320f, 550f + 740f * identity.body.height))
        identity.parts.forEach { part ->
            val a = Math.toRadians(part.angle.toDouble())
            val w = part.width * 1000f * .65f; val h = part.height * 1400f * .65f
            val rx = (abs(cos(a)) * w + abs(sin(a)) * h).toFloat()
            val ry = (abs(sin(a)) * w + abs(cos(a)) * h).toFloat()
            result.union(part.x * 1000f - rx, part.y * 1400f - ry, part.x * 1000f + rx, part.y * 1400f + ry)
        }
        result.inset(-35f, -35f)
        return result
    }
    private fun photo(canvas: Canvas, rig: OcRig, motion: OcMotion, source: Bitmap, width: Int, height: Int) {
        require(source.width <= 4096 && source.height <= 4096 && source.width.toLong() * source.height <= 8_000_000)
        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        canvas.save(); canvas.translate((width - source.width * scale) / 2, (height - source.height * scale) / 2)
        canvas.scale(scale, scale)
        if (motion == OcMotion()) canvas.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        else canvas.drawBitmapMesh(source, 48, 64,
            OcRigRules.vertices(48, 64, source.width, source.height, rig, motion), 0, null, 0,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }
    private fun drawFitted(canvas: Canvas, image: Bitmap, width: Float, height: Float, fill: Boolean) {
        val scale = if (fill) maxOf(width / image.width, height / image.height) else minOf(width / image.width, height / image.height)
        val w = image.width * scale; val h = image.height * scale
        canvas.drawBitmap(image, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
    private class Character(val c: Canvas, val b: OcBody, val motion: OcMotion) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private fun oval(rect: RectF, color: Int) { paint.color = color; paint.style = Paint.Style.FILL; c.drawOval(rect, paint) }
        private fun path(color: Int, block: Path.() -> Unit) { paint.color = color; paint.style = Paint.Style.FILL; c.drawPath(Path().apply(block), paint) }
        private fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
            paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = width; c.drawLine(x1, y1, x2, y2, paint)
        }
        fun draw(parts: List<OcPart>) {
            parts.filter { it.layer < 0 }.sortedBy { it.layer }.forEach(::part)
            val length = 150f + b.hairLength * 440f
            oval(RectF(500f - 165f * b.headWidth, 190f, 500f + 165f * b.headWidth, 400f + length), b.hair)
            c.save(); c.translate(500f, 550f); c.scale(1f + motion.breath * .008f, b.height * (1f + motion.breath * .004f)); c.translate(-500f, -550f)
            body()
            c.restore()
            c.save(); c.rotate(motion.headTurn * 3.5f, 500f, 520f); c.translate(motion.headTurn * 13f, -motion.breath * 2f)
            head()
            c.restore()
            parts.filter { it.layer >= 0 }.sortedBy { it.layer }.forEach(::part)
        }
        private fun body() {
            val h = 1f; val bw = b.bodyWidth
            oval(RectF(394f, 890f, 480f, 1275f * h), 0xff565866.toInt())
            oval(RectF(520f, 890f, 606f, 1275f * h), 0xff565866.toInt())
            oval(RectF(372f, 1200f * h, 485f, 1290f * h), 0xff534840.toInt())
            oval(RectF(515f, 1200f * h, 628f, 1290f * h), 0xff534840.toInt())
            oval(RectF(500f - 145f * bw, 550f, 500f + 145f * bw, 970f * h), b.clothes)
            for (side in listOf(-1, 1)) {
                c.save(); c.rotate(side * -6f, 500f + side * 140f * bw, 600f)
                oval(RectF(500f + side * 160f * bw - 46f, 584f, 500f + side * 160f * bw + 46f, 885f * h), b.clothes)
                oval(RectF(500f + side * 160f * bw - 38f, 838f * h, 500f + side * 160f * bw + 38f, 950f * h), b.skin)
                c.restore()
            }
            oval(RectF(458f, 495f, 542f, 613f), b.skin)
            line(440f, 637f, 470f, 732f, 0xff8f9588.toInt(), 4f)
            line(560f, 637f, 530f, 732f, 0xff8f9588.toInt(), 4f)
            oval(RectF(493f, 698f, 507f, 712f), 0xff777f70.toInt())
            oval(RectF(493f, 768f, 507f, 782f), 0xff777f70.toInt())
        }
        private fun head() {
            val rx = 147f * b.headWidth; val ry = 188f * b.headHeight
            oval(RectF(500f - rx - 20f, 230f, 500f + rx + 20f, 435f), b.hair)
            oval(RectF(500f - rx, 345f - ry, 500f + rx, 345f + ry), b.skin)
            val eyeWidth = 29f * b.eyeSize; val eyeHeight = 39f * b.eyeSize * (1 - motion.blink * .94f)
            for (side in listOf(-1, 1)) {
                val x = 500f + side * 58f * b.eyeSpacing
                oval(RectF(x - eyeWidth, 345f - eyeHeight, x + eyeWidth, 345f + eyeHeight), 0xfffcfaf4.toInt())
                oval(RectF(x - eyeWidth * .53f, 345f - eyeHeight * .91f, x + eyeWidth * .53f, 345f + eyeHeight * .91f), b.eyes)
                oval(RectF(x - eyeWidth * .22f, 345f - eyeHeight * .7f, x + eyeWidth * .22f, 345f + eyeHeight * .7f), 0xff343039.toInt())
                if (motion.blink < .8f) oval(RectF(x - 8f, 326f, x - 1f, 338f), 0xfffcfaf4.toInt())
                line(x - eyeWidth, 290f, x + eyeWidth, 283f, b.hair, 5f)
            }
            line(500f, 373f, 507f + b.noseSize * 4f, 403f, 0xffca9e90.toInt(), 4f)
            val mw = 26f * b.mouthWidth
            oval(RectF(500f - mw, 446f - (3f + motion.mouthOpen * 13f), 500f + mw, 446f + (3f + motion.mouthOpen * 13f)), 0xffad7975.toInt())
            path(b.hair) {
                moveTo(500f - rx - 9f, 270f); cubicTo(365f, 80f, 675f, 85f, 500f + rx + 9f, 270f)
                lineTo(500f + rx - 4f, 330f)
                when (b.hairStyle) {
                    "short" -> { lineTo(600f, 267f); lineTo(553f, 296f); lineTo(503f, 250f); lineTo(445f, 285f) }
                    "side" -> { cubicTo(580f, 225f, 510f, 320f, 418f, 318f); lineTo(439f, 260f) }
                    else -> { lineTo(583f, 257f); lineTo(533f, 305f); lineTo(490f, 244f); lineTo(437f, 300f) }
                }
                lineTo(500f - rx, 326f); close()
            }
            if (b.hairStyle == "twin") for (side in listOf(-1, 1))
                oval(RectF(500f + side * 195f - 48f, 248f, 500f + side * 195f + 48f, 450f + b.hairLength * 350f), b.hair)
        }
        private fun part(p: OcPart) {
            val x = p.x * 1000f; val y = p.y * 1400f; val w = p.width * 1000f; val h = p.height * 1400f
            c.save(); c.translate(x, y); c.rotate(p.angle + motion.accessorySway * if (p.kind == "wing" || p.kind == "tail") 4f else 0f)
            when (p.kind) {
                "wing" -> {
                    path(p.color) { moveTo(0f, h * .45f); cubicTo(-w * .6f, h * .1f, -w * .55f, -h * .45f, 0f, -h * .5f)
                        cubicTo(w * .55f, -h * .3f, w * .6f, h * .2f, 0f, h * .45f); close() }
                    for (i in -2..2) line(0f, -h * .35f, i * w * .13f, h * (.23f - kotlin.math.abs(i) * .04f), 0xffb9b5a6.toInt(), 2.5f)
                }
                "weapon" -> {
                    path(p.color) { moveTo(0f, -h * .5f); lineTo(w * .07f, -h * .4f); lineTo(w * .06f, h * .23f)
                        lineTo(-w * .06f, h * .23f); lineTo(-w * .07f, -h * .4f); close() }
                    line(-w * .35f, h * .23f, w * .35f, h * .23f, 0xffbfa16e.toInt(), 12f)
                    line(0f, h * .25f, 0f, h * .47f, 0xff655048.toInt(), 14f)
                }
                "horn", "ear" -> path(p.color) { moveTo(-w * .4f, h * .4f); quadTo(-w * .15f, -h * .2f, 0f, -h * .5f)
                    quadTo(w * .3f, 0f, w * .4f, h * .4f); close() }
                "tail" -> { paint.color = p.color; paint.style = Paint.Style.STROKE; paint.strokeWidth = w * .3f
                    c.drawPath(Path().apply { moveTo(0f, -h * .4f); cubicTo(-w * .6f, 0f, w * .6f, h * .2f, 0f, h * .5f) }, paint) }
                else -> oval(RectF(-w * .5f, -h * .5f, w * .5f, h * .5f), p.color)
            }
            c.restore()
        }
    }
}
