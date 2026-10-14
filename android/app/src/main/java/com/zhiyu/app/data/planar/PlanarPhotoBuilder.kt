package com.zhiyu.app.data.planar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.zhiyu.app.domain.planar.*
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import rig2d.planar.PlanarContract
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Native source-neighborhood patches intentionally differ from desktop Telea generation. */
class PlanarPhotoBuilder {
    fun build(source: Bitmap, draft: PlanarDraft, name: String, contract: PlanarContract): PlanarDocument {
        require(!source.isRecycled && source.config != Bitmap.Config.HARDWARE) { "请使用可读取的本机图片" }
        val width = source.width
        val height = source.height
        draft.validate(width, height)
        require(max(width, height) <= contract.maxSide) { "图片超出网格尺寸限制" }
        require(draft.suggestionsConfirmed) { "请先检查并确认眼嘴和头颈标记" }
        val checkedName = PlanarDraft.validName(name)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val subject = draft.subjectMask?.let { PlanarDraft.decodeMask(it) } ?: ByteArray(pixels.size) { 1 }
        val work = RasterBudget()
        applyBrush(MaskRegion(0, 0, width, height, subject), draft.subjectBrush, work)
        val owned = ByteArray(pixels.size)
        val specs = ArrayList<LayerSpec>()
        specs.add(LayerSpec("base", "base", -1001.0, draft.anchors.neck, 1.0))
        listOf("eye_left" to draft.anchors.eyeLeft, "eye_right" to draft.anchors.eyeRight, "mouth" to draft.anchors.mouth)
            .forEach { (role, rectangle) ->
                specs.add(LayerSpec(role, role, 100.0, draft.anchors.head, 1.0))
                assignRectangle(rectangle, specs.lastIndex, owned, subject, pixels, width)
            }
        draft.parts.sortedWith(compareByDescending<PlanarPart> { it.z }.thenByDescending { it.id }).forEach { part ->
            specs.add(LayerSpec(part.id, part.role, part.z, part.pivot, part.strength))
            val mask = partMask(part, width, height)
            applyBrush(mask, part.brush, work)
            assignMask(mask, specs.lastIndex, owned, subject, pixels, width)
        }
        val bounds = textureBounds(pixels, subject, owned, specs.size, width)
        require(bounds.any { it != null }) { "主体为空，请保留一些原图像素" }
        require(bounds.filterNotNull().sumOf { it[2].toLong() * it[3] * 4 } <= PlanarDraft.MAX_TEXTURE_BYTES) {
            "纹理超过手机24MB预算，请缩小附件范围或图片尺寸"
        }
        val base = IntArray(pixels.size) { index -> if (subject[index] != 0.toByte() && owned[index] == 0.toByte()) pixels[index] else 0 }
        var repaired = false
        for (owner in 1..3) bounds[owner]?.let { rectangle ->
            if (patchNeighborhood(base, pixels, subject, owned, owner, rectangle, width, height)) repaired = true
        }
        val layers = specs.mapIndexedNotNull { owner, spec -> bounds[owner]?.let { rectangle ->
            val texture = croppedPixels(if (owner == 0) base else pixels, rectangle, width) { index ->
                owner == 0 || owned[index].toInt() == owner
            }
            PlanarLayer(spec.id, spec.role, spec.z, encode(texture, rectangle[2], rectangle[3]),
                PlanarBasisFactory.create(rectangle, spec.role, draft.anchors, spec.pivot, spec.strength, width, height, contract))
        } }.sortedWith(compareBy({ it.z }, { it.id }))
        return PlanarDocument(width, height, checkedName, encode(pixels, width, height), layers,
            definition(draft, repaired), contract)
    }

    private fun assignRectangle(rect: PlanarRect, owner: Int, owned: ByteArray, subject: ByteArray, pixels: IntArray, width: Int) {
        for (y in rect.top.toInt() until ceil(rect.bottom).toInt()) for (x in rect.left.toInt() until ceil(rect.right).toInt()) {
            val index = y * width + x
            if (owned[index] == 0.toByte() && subject[index] != 0.toByte() && Color.alpha(pixels[index]) > 0) owned[index] = owner.toByte()
        }
    }

    private fun assignMask(mask: MaskRegion, owner: Int, owned: ByteArray, subject: ByteArray, pixels: IntArray, width: Int) {
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            val index = (y + mask.y) * width + x + mask.x
            if (mask.values[y * mask.width + x] != 0.toByte() && owned[index] == 0.toByte() &&
                subject[index] != 0.toByte() && Color.alpha(pixels[index]) > 0) owned[index] = owner.toByte()
        }
    }

    private fun textureBounds(pixels: IntArray, subject: ByteArray, owned: ByteArray, count: Int, width: Int): List<IntArray?> {
        val result = Array(count) { intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1) }
        fun include(owner: Int, x: Int, y: Int) {
            val bound = result[owner]
            bound[0] = min(bound[0], x); bound[1] = min(bound[1], y)
            bound[2] = max(bound[2], x); bound[3] = max(bound[3], y)
        }
        for (index in pixels.indices) {
            if (subject[index] == 0.toByte() || Color.alpha(pixels[index]) == 0) continue
            val owner = owned[index].toInt()
            include(owner, index % width, index / width)
            if (owner in 1..3 && Color.alpha(pixels[index]) == 255) include(0, index % width, index / width)
        }
        return result.map { if (it[2] < 0) null else intArrayOf(it[0], it[1], it[2] - it[0] + 1, it[3] - it[1] + 1) }
    }

    /** Sample the nearest usable source pixels outside all eye/mouth rectangles. Never sample their original marks. */
    private fun patchNeighborhood(base: IntArray, source: IntArray, subject: ByteArray, owned: ByteArray,
        owner: Int, bounds: IntArray, width: Int, height: Int): Boolean {
        val x0 = bounds[0]; val y0 = bounds[1]; val x1 = x0 + bounds[2]; val y1 = y0 + bounds[3]
        fun usable(x: Int, y: Int): Boolean {
            val index = y * width + x
            return subject[index] != 0.toByte() && owned[index].toInt() !in 1..3 && Color.alpha(source[index]) == 255
        }
        fun find(x: Int, y: Int, dx: Int, dy: Int): Int {
            var px = x; var py = y
            while (px in 0 until width && py in 0 until height) {
                if (usable(px, py)) return py * width + px
                px += dx; py += dy
            }
            return -1
        }
        val left = IntArray(bounds[3]) { find(x0 - 1, y0 + it, -1, 0) }
        val right = IntArray(bounds[3]) { find(x1, y0 + it, 1, 0) }
        val top = IntArray(bounds[2]) { find(x0 + it, y0 - 1, 0, -1) }
        val bottom = IntArray(bounds[2]) { find(x0 + it, y1, 0, 1) }
        var fallback = -1
        var fallbackDistance = Double.POSITIVE_INFINITY
        var changed = false
        for (y in y0 until y1) for (x in x0 until x1) {
            val index = y * width + x
            if (owned[index].toInt() != owner || Color.alpha(source[index]) != 255) continue
            var nearest = -1
            var bestDistance = Long.MAX_VALUE
            for (direction in 0..3) {
                val candidate = when (direction) { 0 -> left[y - y0]; 1 -> right[y - y0]; 2 -> top[x - x0]; else -> bottom[x - x0] }
                if (candidate < 0) continue
                val dx = candidate % width - x; val dy = candidate / width - y
                val distance = dx.toLong() * dx + dy.toLong() * dy
                if (distance < bestDistance) { nearest = candidate; bestDistance = distance }
            }
            if (nearest < 0) {
                if (fallback < 0) for (candidate in source.indices) {
                    val px = candidate % width; val py = candidate / width
                    if (!usable(px, py)) continue
                    val dx = px - (x0 + bounds[2] / 2.0); val dy = py - (y0 + bounds[3] / 2.0)
                    val distance = dx * dx + dy * dy
                    if (distance < fallbackDistance) { fallback = candidate; fallbackDistance = distance }
                }
                require(fallback >= 0) { "眼嘴框外没有可用的原图邻域，请缩小或调整框" }
                nearest = fallback
            }
            base[index] = source[nearest]
            changed = true
        }
        return changed
    }

    private fun partMask(part: PlanarPart, width: Int, height: Int): MaskRegion {
        part.baseMaskPng?.let { return importedMask(it, width, height) }
        val extent = part.polygon + part.brush.flatMap { stroke -> stroke.points.flatMap { p ->
            listOf(PlanarPoint(p.x - stroke.radius, p.y - stroke.radius), PlanarPoint(p.x + stroke.radius, p.y + stroke.radius))
        } }
        val x0 = floor(extent.minOf { it.x }).toInt().coerceIn(0, width - 1)
        val y0 = floor(extent.minOf { it.y }).toInt().coerceIn(0, height - 1)
        val x1 = (ceil(extent.maxOf { it.x }).toInt() + 1).coerceIn(x0 + 1, width)
        val y1 = (ceil(extent.maxOf { it.y }).toInt() + 1).coerceIn(y0 + 1, height)
        val mask = MaskRegion(x0, y0, x1 - x0, y1 - y0, ByteArray((x1 - x0) * (y1 - y0)))
        fillPolygon(mask, part.polygon)
        return mask
    }

    private fun importedMask(encoded: String, width: Int, height: Int): MaskRegion {
        val bytes = PlanarDraft.decodeMask(encoded)
        require(bytes.size <= PlanarDraft.MAX_MASK_BYTES && bytes.size >= 8 &&
            bytes.take(8) == listOf(-119, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)) { "附件遮罩须为PNG且不超过8MB" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth == width && options.outHeight == height) { "附件遮罩尺寸必须与图片相同" }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPremultiplied = false })) { "附件PNG遮罩损坏" }
        try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val values = ByteArray(pixels.size) { index ->
                val pixel = pixels[index]
                val red = Color.red(pixel)
                require(red == Color.green(pixel) && red == Color.blue(pixel) && (red == 0 || red == 255)) {
                    "附件遮罩必须仅含黑白二值"
                }
                if (red == 255) 1 else 0
            }
            return MaskRegion(0, 0, width, height, values)
        } finally { bitmap.recycle() }
    }

    private fun fillPolygon(mask: MaskRegion, polygon: List<PlanarPoint>) {
        for (y in mask.y until mask.y + mask.height) {
            val sampleY = y + .5
            val intersections = ArrayList<Double>()
            polygon.indices.forEach { index ->
                val a = polygon[index]; val b = polygon[(index + 1) % polygon.size]
                if ((a.y <= sampleY && b.y > sampleY) || (b.y <= sampleY && a.y > sampleY))
                    intersections.add(a.x + (sampleY - a.y) * (b.x - a.x) / (b.y - a.y))
            }
            intersections.sort()
            for (pair in 0 until intersections.size / 2) {
                val left = ceil(intersections[pair * 2] - .5).toInt().coerceAtLeast(mask.x)
                val right = floor(intersections[pair * 2 + 1] - .5).toInt().coerceAtMost(mask.x + mask.width - 1)
                for (x in left..right) mask.values[(y - mask.y) * mask.width + x - mask.x] = 1
            }
        }
    }

    private fun applyBrush(mask: MaskRegion, strokes: List<PlanarBrushStroke>, budget: RasterBudget) {
        strokes.forEach { stroke ->
            for (index in 0 until max(1, stroke.points.size - 1)) {
                val a = stroke.points[index]; val b = stroke.points[min(index + 1, stroke.points.lastIndex)]
                brushSegment(mask, a, b, stroke.radius, if (stroke.keep) 1 else 0, budget)
            }
        }
    }

    private fun brushSegment(mask: MaskRegion, a: PlanarPoint, b: PlanarPoint, radius: Double, value: Byte, budget: RasterBudget) {
        val x0 = max(mask.x, floor(min(a.x, b.x) - radius).toInt())
        val y0 = max(mask.y, floor(min(a.y, b.y) - radius).toInt())
        val x1 = min(mask.x + mask.width, ceil(max(a.x, b.x) + radius).toInt() + 1)
        val y1 = min(mask.y + mask.height, ceil(max(a.y, b.y) + radius).toInt() + 1)
        if (x0 >= x1 || y0 >= y1) return
        budget.consume((x1 - x0).toLong() * (y1 - y0))
        val dx = b.x - a.x; val dy = b.y - a.y; val length = dx * dx + dy * dy
        for (y in y0 until y1) for (x in x0 until x1) {
            val px = x + .5; val py = y + .5
            val t = if (length == 0.0) 0.0 else (((px - a.x) * dx + (py - a.y) * dy) / length).coerceIn(0.0, 1.0)
            val distanceX = px - a.x - t * dx; val distanceY = py - a.y - t * dy
            if (distanceX * distanceX + distanceY * distanceY <= radius * radius)
                mask.values[(y - mask.y) * mask.width + x - mask.x] = value
        }
    }

    private fun croppedPixels(source: IntArray, bounds: IntArray, width: Int, selected: (Int) -> Boolean): IntArray =
        IntArray(bounds[2] * bounds[3]) { local ->
            val index = (local / bounds[2] + bounds[1]) * width + local % bounds[2] + bounds[0]
            if (selected(index)) source[index] else 0
        }

    private fun encode(pixels: IntArray, width: Int, height: Int): ByteArray {
        val image = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return try {
            val output = ByteArrayOutputStream()
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) { "本机PNG编码失败" }
            output.toByteArray()
        } finally { image.recycle() }
    }

    private fun definition(draft: PlanarDraft, repaired: Boolean): String {
        fun point(value: PlanarPoint) = JSONArray().put(value.x).put(value.y)
        fun rect(value: PlanarRect) = JSONArray().put(value.left).put(value.top).put(value.right).put(value.bottom)
        fun brush(values: List<PlanarBrushStroke>) = JSONArray().apply { values.forEach { stroke ->
            put(JSONObject().put("points", JSONArray().apply { stroke.points.forEach { put(point(it)) } })
                .put("radius", stroke.radius).put("keep", stroke.keep))
        } }
        return JSONObject().apply {
            put("anchors", JSONObject().put("head", point(draft.anchors.head)).put("neck", point(draft.anchors.neck))
                .put("eyeLeft", rect(draft.anchors.eyeLeft)).put("eyeRight", rect(draft.anchors.eyeRight)).put("mouth", rect(draft.anchors.mouth)))
            put("parts", JSONArray().apply { draft.parts.forEach { part ->
                put(JSONObject().put("id", part.id).put("name", part.name).put("role", part.role).put("z", part.z)
                    .put("polygon", JSONArray().apply { part.polygon.forEach { put(point(it)) } })
                    .put("pivot", point(part.pivot)).put("strength", part.strength).put("brush", brush(part.brush)).apply {
                        part.baseMaskPng?.let { put("mask", it) }
                    })
            } })
            draft.subjectMask?.let { put("subjectMask", it) }
            put("subjectBrush", brush(draft.subjectBrush))
            put("metadata", JSONObject().put("generator", "android-neighborhood-v1").put("reviewRequired", true)
                .put("suggestionsConfirmed", draft.suggestionsConfirmed).put("automaticSegmentation", false)
                .put("neutralSourceExact", true).put("hiddenPatchGenerated", repaired).put("hiddenPatchMethod", "source-neighborhood")
                .put("desktopPixelEquivalent", false).put("maxTextureBytes", PlanarDraft.MAX_TEXTURE_BYTES))
        }.toString()
    }

    private data class LayerSpec(val id: String, val role: String, val z: Double, val pivot: PlanarPoint, val strength: Double)
    private data class MaskRegion(val x: Int, val y: Int, val width: Int, val height: Int, val values: ByteArray)
    private class RasterBudget {
        private var processed = 0L
        fun consume(count: Long) {
            processed += count
            require(processed <= 64_000_000) { "笔迹处理量超过手机预算，请减少或缩小笔迹" }
        }
    }
}
