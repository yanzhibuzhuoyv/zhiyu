package com.zhiyu.app.data.local.qwen

import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.MediaRules
import java.time.LocalDate

data class QwenSceneSnapshot(val chatId: String, val dayId: String, val visualVersion: Int,
    val referenceId: String?, val referenceSha256: String?, val characterPackageSha: String? = null)

/** No reference image or OC mutation is part of this background-only request. */
class QwenBackgroundRequest(val identity: QwenSceneSnapshot, val prompt: String) {
    val capability = "text_to_image_background"

    init {
        MediaRules.path(identity.chatId)
        require(identity.visualVersion >= 0 && Regex("\\d{4}-\\d{2}-\\d{2}").matches(identity.dayId)) { "生活场景身份无效" }
        try { require(LocalDate.parse(identity.dayId).toString() == identity.dayId) }
        catch (failure: java.time.DateTimeException) { throw IllegalArgumentException("生活场景日期无效", failure) }
        require((identity.referenceId == null) == (identity.referenceSha256 == null)) { "人物引用不完整" }
        identity.referenceId?.let { MediaRules.path(it) }
        identity.referenceSha256?.let { require(Regex("[a-f0-9]{64}").matches(it)) { "人物引用摘要无效" } }
        identity.characterPackageSha?.let {
            require(identity.referenceId != null && Regex("[a-f0-9]{64}").matches(it)) { "角色档案摘要无效" }
        }
        require(prompt.isNotBlank() && prompt.length <= 4000) { "背景描述为空或过长" }
        LocalMemoryCodec.bytes(prompt)
    }

    /** Run again inside the existing profile/character commit lock before publishing cached media. */
    fun requireCurrent(current: QwenSceneSnapshot) {
        require(current == identity) { "日期或恋人形象已变化，不能发布旧场景" }
    }
}

/** Disk bytes and measured working memory have separate units and separate gates. */
object QwenPackCapacity {
    fun requiredStorage(manifest: QwenModelPackManifest, copies: Int, reserveBytes: Long): Long {
        require(copies in 1..2 && reserveBytes >= 0) { "模型包空间预算无效" }
        return Math.addExact(Math.multiplyExact(manifest.totalBytes, copies.toLong()), reserveBytes)
    }

    fun requireStorage(manifest: QwenModelPackManifest, freeBytes: Long, copies: Int, reserveBytes: Long) {
        require(freeBytes >= 0) { "设备可用空间无效" }
        require(freeBytes >= requiredStorage(manifest, copies, reserveBytes)) { "设备空间不足，不能安装模型包" }
    }

    /** Null means no valid target-device measurement. The phone's advertised total RAM is not this value. */
    fun memoryFits(measuredPeakBytes: Long?, currentlyAvailableBytes: Long, reserveBytes: Long): Boolean {
        require(currentlyAvailableBytes >= 0 && reserveBytes >= 0 && (measuredPeakBytes == null || measuredPeakBytes > 0)) {
            "设备运行内存预算无效"
        }
        if (measuredPeakBytes == null) return false
        if (measuredPeakBytes > Long.MAX_VALUE - reserveBytes) return false
        return currentlyAvailableBytes >= measuredPeakBytes + reserveBytes
    }
}
