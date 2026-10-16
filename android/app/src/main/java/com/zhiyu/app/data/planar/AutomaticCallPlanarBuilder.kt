package com.zhiyu.app.data.planar

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import com.zhiyu.app.domain.planar.AutomaticFaceRegionRules
import com.zhiyu.app.domain.planar.AutomaticFaceMotion
import com.zhiyu.app.domain.planar.DetectedFaceRegion
import org.json.JSONObject
import rig2d.planar.PlanarContract

/** A temporary preview; never publishes a character package or replaces the original OC image. */
class AutomaticCallPlanarBuilder(private val contract: PlanarContract,
    private val detect: (Bitmap) -> List<DetectedFaceRegion> = AndroidCallFaceDetector::detect) {
    fun build(source: Bitmap): PlanarDocument? {
        require(!source.isRecycled && source.width in 32..768 && source.height in 32..768) { "请使用缩小后的通话预览图" }
        val draft = AutomaticFaceRegionRules.suggest(source.width, source.height, detect(source)) ?: return null
        val proposed = PlanarPhotoBuilder().build(source, draft.copy(suggestionsConfirmed = true), "临时人物动作", contract)
        val layers = proposed.layers.map { layer ->
            if (layer.id != "auto_face") layer else layer.copy(mesh = AutomaticFaceMotion.create(layer.mesh))
        }
        val definition = JSONObject(proposed.definitionJson)
        definition.getJSONObject("metadata").put("automaticFaceSuggestion", true).put("temporaryCallPreview", true)
            .put("automaticSegmentation", false).put("reviewRequired", true).put("suggestionsConfirmed", false)
        return proposed.copy(layers = layers, definitionJson = definition.toString())
    }
}

/** Android's bundled detector works offline; drawings/side views may require the manual editor. */
@Suppress("DEPRECATION")
internal object AndroidCallFaceDetector {
    fun detect(source: Bitmap): List<DetectedFaceRegion> {
        val width = source.width - source.width % 2
        if (width < 32 || source.height < 32) return emptyList()
        val cropped = if (width == source.width) source else Bitmap.createBitmap(source, 0, 0, width, source.height)
        val rgb = try { checkNotNull(cropped.copy(Bitmap.Config.RGB_565, false)) }
            finally { if (cropped !== source) cropped.recycle() }
        return try {
            val faces = arrayOfNulls<FaceDetector.Face>(2)
            val count = FaceDetector(rgb.width, rgb.height, 2).findFaces(rgb, faces)
            (0 until count).mapNotNull { index -> faces[index]?.let { face ->
                val midpoint = PointF(); face.getMidPoint(midpoint)
                DetectedFaceRegion(midpoint.x.toDouble(), midpoint.y.toDouble(), face.eyesDistance().toDouble(),
                    face.confidence().toDouble(), face.pose(FaceDetector.Face.EULER_Y).toDouble(),
                    face.pose(FaceDetector.Face.EULER_Z).toDouble())
            } }
        } finally { rgb.recycle() }
    }
}
