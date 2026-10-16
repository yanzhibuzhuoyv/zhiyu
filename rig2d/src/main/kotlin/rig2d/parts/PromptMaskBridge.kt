package rig2d.parts

import rig2d.domain.OcRigAssetRules

/**
 * One selected binary prompt mask. kind is the caller's declared layer kind, not a
 * model semantic classification. quality is model-predicted IoU, not identity confidence.
 */
data class PromptMaskRecord(
    val id: String,
    val kind: String,
    val pixels: ByteArray,
    val quality: Float,
    val accepted: Boolean,
)

/** Pure JVM boundary for prompt inference records; it performs no model or file I/O. */
object PromptMaskBridge {
    private val idPattern = Regex("[a-zA-Z0-9_-]{1,80}")
    private data class CheckedPrompt(val candidate: PartCandidate, val accepted: Boolean)

    fun assemble(
        source: PartRaster,
        expectedSourcePixelSha: String,
        records: List<PromptMaskRecord>,
        protected: PixelMask,
        minimumQuality: Float = .8f,
    ): PartLayerDraft {
        require(expectedSourcePixelSha == source.pixelSha256) { "提示遮罩来源ARGB像素校验值不匹配" }
        require(minimumQuality.isFinite() && minimumQuality in 0f..1f) { "提示遮罩质量阈值无效" }
        require(records.size <= PartLayerAssembler.MAX_PROPOSALS) { "提示遮罩数量超出${PartLayerAssembler.MAX_PROPOSALS}" }
        val pixelCount = PixelMask.checkedCount(source.width, source.height)
        val snapshot = boundedSnapshot(source, records, pixelCount, minimumQuality)
        require(snapshot.map { it.candidate.id }.distinct().size == snapshot.size) { "提示遮罩实例编号重复" }
        val accepted = snapshot.filter { it.accepted }.map { it.candidate }
        val subject = accepted.fold(PixelMask.empty(source.width, source.height)) { union, candidate ->
            union.union(candidate.mask)
        }
        // The assembler uses this numeric threshold without inferring semantic or identity certainty.
        return PartLayerAssembler.assemble(source, subject, protected, accepted, minimumQuality)
    }

    private fun boundedSnapshot(
        source: PartRaster, records: List<PromptMaskRecord>, pixelCount: Int, minimumQuality: Float,
    ): List<CheckedPrompt> {
        val snapshot = ArrayList<CheckedPrompt>()
        val iterator = records.iterator()
        while (iterator.hasNext()) {
            require(snapshot.size < PartLayerAssembler.MAX_PROPOSALS) { "提示遮罩快照超出${PartLayerAssembler.MAX_PROPOSALS}" }
            snapshot.add(checkRecord(source, iterator.next(), pixelCount, minimumQuality))
        }
        return snapshot.toList()
    }

    private fun checkRecord(
        source: PartRaster, record: PromptMaskRecord, pixelCount: Int, minimumQuality: Float,
    ): CheckedPrompt {
        require(idPattern.matches(record.id)) { "提示遮罩实例编号无效" }
        require(record.kind in OcRigAssetRules.layerKinds) { "未知提示声明部位类别" }
        require(record.quality.isFinite() && record.quality in 0f..1f) { "提示遮罩质量分数无效" }
        require(record.pixels.size == pixelCount) { "提示遮罩像素数量错误" }
        val pixels = record.pixels.copyOf()
        val selected = BooleanArray(pixelCount) { index ->
            val value = pixels[index].toInt()
            require(value == 0 || value == 1) { "提示遮罩必须是0/1二进制像素" }
            value == 1
        }
        val mask = PixelMask.fromBooleans(source.width, source.height, selected)
        require(!record.accepted || record.quality >= minimumQuality) { "已接受提示遮罩未达到质量阈值" }
        require(!record.accepted || mask.count > 0) { "已接受提示遮罩为空" }
        return CheckedPrompt(PartCandidate(record.id, record.kind, mask, record.quality), record.accepted)
    }
}
