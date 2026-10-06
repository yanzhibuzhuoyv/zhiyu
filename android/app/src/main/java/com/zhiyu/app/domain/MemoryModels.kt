package com.zhiyu.app.domain

data class MemoryEntry(
    val messageId: String, val role: String, val text: String, val createdAt: String,
    val charLength: Int, val tier: String, val indexState: String, val revision: Int,
    val pinned: Boolean, val reason: String, val manualTier: String?, val canConfirmIndependent: Boolean,
    val recognitionMode: String = "none", val recognitionStatus: String = "unavailable",
)

data class MemoryPage(
    val messages: List<MemoryEntry>, val hasMore: Boolean, val nextBefore: String?,
    val automaticAnnotations: Boolean,
)

data class MemoryChange(
    val expectedRevision: Int, val tier: String? = null, val indexState: String? = null,
    val confirmIndependent: Boolean = false,
)

/** Metadata controls indexing only; no operation replaces or deletes original text. */
object MemoryRules {
    private val tiers = setOf("mid", "long", "permanent")
    private val states = setOf("active", "cold")
    fun entry(value: MemoryEntry): MemoryEntry {
        require(value.messageId.isNotBlank() && value.messageId.length <= 128) { "记忆来源标识不正确" }
        require(value.role in setOf("user", "assistant") && value.tier in tiers && value.indexState in states) { "记忆状态无法识别，请更新应用" }
        require(value.revision >= 0 && value.charLength >= value.text.codePointCount(0, value.text.length)) { "记忆信息不完整" }
        require(value.text.codePointCount(0, value.text.length) <= 768) { "记忆节选超过范围" }
        require(value.createdAt.isNotBlank() && value.createdAt.length <= 128 && value.reason.length <= 512) { "记忆信息不完整" }
        require(value.manualTier == null || value.manualTier in tiers) { "手动记忆状态无法识别" }
        require(value.recognitionMode in setOf("semantic", "literal", "none") &&
            value.recognitionStatus in setOf("ok", "partial", "failed", "unavailable", "skipped_large", "disabled", "literal")) {
            "记忆识别状态无法识别，请更新应用"
        }
        return value
    }
    fun page(value: MemoryPage, before: String? = null): MemoryPage {
        require(value.messages.size <= 50 && value.messages.map { it.messageId }.distinct().size == value.messages.size) { "记忆列表超过范围或重复" }
        value.messages.forEach(::entry)
        require(!value.hasMore || (!value.nextBefore.isNullOrBlank() && value.nextBefore != before)) { "记忆列表无法继续读取，请刷新" }
        require(value.nextBefore == null || value.nextBefore.length <= 128) { "记忆列表游标不正确" }
        return value
    }
    fun change(value: MemoryEntry, tier: String? = null, indexState: String? = null, confirmIndependent: Boolean = false): MemoryChange {
        entry(value)
        require(tier == null || tier in tiers || tier == "auto") { "记忆层级不正确" }
        require(indexState == null || indexState in states) { "记忆归档状态不正确" }
        require(tier != null || indexState != null || confirmIndependent) { "请选择一个记忆操作" }
        require(!confirmIndependent || (value.role == "user" && value.canConfirmIndependent)) { "这条原话暂不支持确认独立表达" }
        return MemoryChange(value.revision, tier, indexState, confirmIndependent)
    }
}
