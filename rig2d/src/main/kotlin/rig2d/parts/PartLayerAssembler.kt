package rig2d.parts

import rig2d.domain.OcRigAssetRules
import java.util.Collections

data class PartCandidate(val id: String, val kind: String, val mask: PixelMask, val confidence: Float)
data class PartPlane(val id: String, val kind: String, val mask: PixelMask)

class PartLayerDraft internal constructor(
    val source: PartRaster, val foreground: PixelMask,
    planes: List<PartPlane>, val retained: PixelMask, val ambiguous: PixelMask,
) {
    val planes: List<PartPlane> = Collections.unmodifiableList(planes.toList())
    val reviewRequired: Boolean get() = retained.count > 0 || ambiguous.count > 0
    val identityVerified: Boolean get() = false
    /** At-rest reconstruction only. It supplies no deformation or inferred hidden pixels. */
    fun recomposeArgb(): IntArray {
        var covered = retained
        planes.forEach { plane ->
            require(covered.intersect(plane.mask).count == 0) { "分层像素重叠" }
            covered = covered.union(plane.mask)
        }
        require(covered == foreground) { "分层与保留层未完整覆盖原前景" }
        return source.selectedArgb(covered)
    }
}

object PartLayerAssembler {
    const val MAX_PROPOSALS = 63 // Reserve the 64th v2 layer for static retained foreground.
    private val idPattern = Regex("[a-zA-Z0-9_-]{1,80}")

    fun assemble(
        source: PartRaster, subject: PixelMask, protected: PixelMask,
        proposals: List<PartCandidate>, minimumConfidence: Float = .8f,
    ): PartLayerDraft {
        require(proposals.size <= MAX_PROPOSALS) { "部位候选数量超出$MAX_PROPOSALS" }
        require(minimumConfidence.isFinite() && minimumConfidence in 0f..1f) { "部位置信度阈值无效" }
        requireSize(source, subject)
        requireSize(source, protected)
        val candidates = boundedSnapshot(proposals)
        require(candidates.map { it.id }.distinct().size == candidates.size) { "部位实例编号重复" }
        candidates.forEach { candidate ->
            require(idPattern.matches(candidate.id)) { "部位实例编号无效" }
            require(candidate.kind in OcRigAssetRules.layerKinds) { "未知部位类别" }
            requireSize(source, candidate.mask)
            require(candidate.confidence.isFinite() && candidate.confidence in 0f..1f) { "部位置信度无效" }
        }
        val foreground = subject.union(protected).intersect(source.alphaMask())
        require(foreground.count > 0) { "有效原前景为空，需要重新选择人物区域" }
        val eligible = candidates.filter { it.confidence >= minimumConfidence }.sortedBy { it.id }
            .map { PartPlane(it.id, it.kind, it.mask.intersect(foreground)) }
        var claimed = PixelMask.empty(source.width, source.height)
        var ambiguous = claimed
        eligible.forEach { plane ->
            ambiguous = ambiguous.union(claimed.intersect(plane.mask))
            claimed = claimed.union(plane.mask)
        }
        val planes = eligible.map { it.copy(mask = it.mask.subtract(ambiguous)) }.filter { it.mask.count > 0 }
        val covered = planes.fold(PixelMask.empty(source.width, source.height)) { union, plane -> union.union(plane.mask) }
        val retained = foreground.subtract(covered)
        return PartLayerDraft(source, foreground, planes, retained, ambiguous)
    }
    private fun requireSize(source: PartRaster, mask: PixelMask) {
        require(source.width == mask.width && source.height == mask.height) { "遮罩与原图尺寸不一致" }
    }
    private fun boundedSnapshot(proposals: List<PartCandidate>): List<PartCandidate> {
        val snapshot = ArrayList<PartCandidate>()
        val iterator = proposals.iterator()
        while (iterator.hasNext()) {
            require(snapshot.size < MAX_PROPOSALS) { "部位候选快照超出$MAX_PROPOSALS" }
            snapshot.add(iterator.next())
        }
        return snapshot.toList()
    }
}
