package com.zhiyu.app.domain.planar

import java.util.Collections
import java.util.Base64
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class PlanarPoint(val x: Double, val y: Double)
data class PlanarRect(val left: Double, val top: Double, val right: Double, val bottom: Double)
data class PlanarAnchors(
    val head: PlanarPoint,
    val neck: PlanarPoint,
    val eyeLeft: PlanarRect,
    val eyeRight: PlanarRect,
    val mouth: PlanarRect,
)

/** Ordered sparse source-coordinate edits. keep=false erases; keep=true restores source pixels. */
class PlanarBrushStroke(points: List<PlanarPoint>, val radius: Double, val keep: Boolean) {
    val points: List<PlanarPoint> = immutable(points)
    fun copy(points: List<PlanarPoint> = this.points, radius: Double = this.radius, keep: Boolean = this.keep) =
        PlanarBrushStroke(points, radius, keep)
}

class PlanarPart(
    val id: String,
    val name: String,
    val role: String,
    val z: Double,
    polygon: List<PlanarPoint>,
    val pivot: PlanarPoint,
    val strength: Double = 1.0,
    brush: List<PlanarBrushStroke> = emptyList(),
    val baseMaskPng: String? = null,
) {
    val polygon: List<PlanarPoint> = immutable(polygon)
    val brush: List<PlanarBrushStroke> = immutable(brush)
    fun copy(
        id: String = this.id, name: String = this.name, role: String = this.role, z: Double = this.z,
        polygon: List<PlanarPoint> = this.polygon, pivot: PlanarPoint = this.pivot,
        strength: Double = this.strength, brush: List<PlanarBrushStroke> = this.brush,
        baseMaskPng: String? = this.baseMaskPng,
    ) = PlanarPart(id, name, role, z, polygon, pivot, strength, brush, baseMaskPng)
}

class PlanarDraft(
    val anchors: PlanarAnchors,
    parts: List<PlanarPart> = emptyList(),
    subjectBrush: List<PlanarBrushStroke> = emptyList(),
    val suggestionsConfirmed: Boolean = false,
    val subjectMask: String? = null,
    val featureVisibility: PlanarFeatureVisibility = PlanarFeatureVisibility(),
) {
    val parts: List<PlanarPart> = immutable(parts)
    val subjectBrush: List<PlanarBrushStroke> = immutable(subjectBrush)
    fun copy(
        anchors: PlanarAnchors = this.anchors, parts: List<PlanarPart> = this.parts,
        subjectBrush: List<PlanarBrushStroke> = this.subjectBrush,
        suggestionsConfirmed: Boolean = this.suggestionsConfirmed,
        subjectMask: String? = this.subjectMask,
        featureVisibility: PlanarFeatureVisibility = this.featureVisibility,
    ) = PlanarDraft(anchors, parts, subjectBrush, suggestionsConfirmed, subjectMask, featureVisibility)

    fun validate(width: Int, height: Int) {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "图片最长边不得超过1536像素" }
        point(anchors.head, width, height)
        point(anchors.neck, width, height)
        listOf(anchors.eyeLeft, anchors.eyeRight, anchors.mouth).forEach { rectangle(it, width, height) }
        require(parts.size <= MAX_PARTS) { "最多支持60个附件" }
        val ids = mutableSetOf("base", "eye_left", "eye_right", "mouth")
        var encodedMaskBytes = 0L
        val strokes = ArrayList<PlanarBrushStroke>(subjectBrush)
        parts.forEach { part ->
            require(SAFE_ID.matches(part.id) && ids.add(part.id)) { "附件ID无效或重复" }
            validName(part.name)
            require(part.role in PART_ROLES) { "附件角色无效" }
            require(part.z.isFinite() && part.z in -1000.0..90.0) { "附件层级超出范围" }
            require(part.strength.isFinite() && part.strength in 0.0..1.0) { "随动强度超出范围" }
            point(part.pivot, width, height)
            if (part.baseMaskPng == null) require(part.polygon.size in 3..256) { "请先完成附件多边形" }
            else require(part.polygon.size <= 256) { "多边形最多256点" }
            part.polygon.forEach { point(it, width, height) }
            part.baseMaskPng?.let {
                require(it.length <= ((MAX_MASK_BYTES + 2L) / 3 * 4)) { "附件遮罩过大" }
                encodedMaskBytes += it.length
            }
            strokes.addAll(part.brush)
        }
        subjectMask?.let {
            require(it.length <= ((width.toLong() * height + 2) / 3 * 4)) { "主体遮罩过大" }
            val decoded = decodeMask(it)
            require(decoded.size == width * height && decoded.all { value -> value == 0.toByte() || value == 1.toByte() }) {
                "主体遮罩必须与图片同尺寸且仅含0/1"
            }
            encodedMaskBytes += it.length
        }
        require(encodedMaskBytes <= 30L * 1024 * 1024) { "遮罩总数据超过手机预算" }
        validateStrokes(strokes, width, height)
    }

    companion object {
        const val MAX_PARTS = 60
        const val MAX_SIDE = 1536
        const val MAX_TEXTURE_BYTES = 24_000_000L
        const val MAX_MASK_BYTES = 8 * 1024 * 1024
        val PART_ROLES: Set<String> = Collections.unmodifiableSet(setOf("face", "eye_left", "eye_right", "mouth", "hair", "body", "accessory"))
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")

        /** Conservative proportions only; these positions must be checked by the person editing. */
        fun suggest(width: Int, height: Int): PlanarDraft {
            require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "图片最长边不得超过1536像素" }
            val x = width * .25
            val y = height * .10
            val side = min(width * .5, height * .5)
            fun rect(cx: Double, cy: Double, rw: Double, rh: Double): PlanarRect {
                val w = max(1.0, rw)
                val h = max(1.0, rh)
                val left = max(0.0, min(width - w, cx - w / 2)).toInt().toDouble()
                val top = max(0.0, min(height - h, cy - h / 2)).toInt().toDouble()
                // Match the original proportional suggestion's truncation before the far edge.
                val rawLeft = max(0.0, min(width - w, cx - w / 2))
                val rawTop = max(0.0, min(height - h, cy - h / 2))
                return PlanarRect(left, top, min(width.toDouble(), ceil(rawLeft + w)), min(height.toDouble(), ceil(rawTop + h)))
            }
            return PlanarDraft(PlanarAnchors(
                PlanarPoint(min(width - 1.0, x + side / 2), min(height - 1.0, y + side / 2)),
                PlanarPoint(min(width - 1.0, x + side / 2), min(height - 1.0, y + side * 1.12)),
                rect(x + side * .30, y + side * .42, side * .22, side * .14),
                rect(x + side * .70, y + side * .42, side * .22, side * .14),
                rect(x + side * .50, y + side * .76, side * .28, side * .12),
            ))
        }

        fun validName(value: String): String {
            val result = value.trim()
            require(result.codePointCount(0, result.length) in 1..80 && value.none { it.code < 32 }) { "名称须为1至80个可显示字符" }
            return result
        }

        fun decodeMask(value: String): ByteArray = try { Base64.getDecoder().decode(value) }
        catch (error: IllegalArgumentException) { throw IllegalArgumentException("遮罩Base64无效", error) }

        private fun point(value: PlanarPoint, width: Int, height: Int) {
            require(value.x.isFinite() && value.y.isFinite() && value.x in 0.0..(width - 1.0) && value.y in 0.0..(height - 1.0)) {
                "标记点必须位于图片内"
            }
        }

        private fun rectangle(value: PlanarRect, width: Int, height: Int) {
            point(PlanarPoint(value.left, value.top), width, height)
            require(value.right.isFinite() && value.bottom.isFinite() && value.right <= width && value.bottom <= height &&
                value.right - value.left >= 1 && value.bottom - value.top >= 1) { "眼嘴框至少须为1×1像素且位于图片内" }
        }

        private fun validateStrokes(strokes: List<PlanarBrushStroke>, width: Int, height: Int) {
            require(strokes.size <= 512 && strokes.sumOf { it.points.size.toLong() } <= 16_384) { "笔迹超过手机处理预算" }
            strokes.forEach { stroke ->
                require(stroke.points.size in 1..4096 && stroke.radius.isFinite() && stroke.radius in .5..max(width, height).toDouble()) {
                    "画笔范围或点数无效"
                }
                stroke.points.forEach { point(it, width, height) }
            }
        }
    }
}

private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
