package com.zhiyu.app.data

import java.time.Instant

/** Canonical original supplied by LocalChatDatabase after complete-body SHA verification. */
data class LocalMemoryOriginal(
    val owner: String, val conversationId: String, val messageId: String, val role: String,
    val createdAt: String, val sha256: String, val revision: Int, val text: String,
    val indexState: String = "active", val manualTier: String? = null, val origin: String = "reply",
) {
    init {
        require(listOf(owner, conversationId, messageId).all { it.isNotBlank() && it.length <= 256 })
        // A verified photo-only original can have no caption. Eligibility belongs to derived tasks.
        require(role in setOf("user", "assistant") && revision >= 0)
        require(Regex("[a-f0-9]{64}").matches(sha256))
        require(indexState in setOf("active", "cold") && manualTier in setOf(null, "mid", "long", "permanent"))
        Instant.parse(createdAt)
    }
    override fun toString() = "LocalMemoryOriginal(role=$role, revision=$revision, characters=${text.length})"
}

data class LocalMemoryRawClaim(
    val quote: String, val kind: String, val subject: String, val polarity: String,
    val topicKey: String, val confidence: Double, val explicitKeep: Boolean, val correction: Boolean,
    val promiseState: String?, val dateBasis: String?, val eventDate: String?,
    val start: Int? = null, val end: Int? = null,
)

/** Metadata points to one untouched source span. It never contains generated prose. */
data class LocalMemoryAnnotation(
    val id: String, val owner: String, val conversationId: String, val messageId: String,
    val role: String, val createdAt: String, val sourceSha: String, val sourceRevision: Int,
    val start: Int, val end: Int, val spanSha: String, val kind: String, val subject: String,
    val polarity: String, val topicKey: String, val confidence: Double, val explicitKeep: Boolean,
    val correction: Boolean, val promiseState: String?, val dateBasis: String?, val eventDate: String?,
    val validity: String = "current", val tier: String = "mid", val pinned: Boolean = false,
)

data class LocalMemoryRecognitionJob(
    val id: String, val owner: String, val conversationId: String, val messageId: String,
    val providerId: String, val model: String, val sourceSha: String, val sourceRevision: Int,
    val offset: Int, val lease: String, val attempts: Int, val role: String, val createdAt: String,
)

data class LocalMemoryRecognitionIdentity(val owner: String, val conversationId: String, val providerId: String, val model: String)
data class LocalMemoryTopic(val kind: String, val subject: String, val topicKey: String)
data class LocalMemoryWindow(val source: LocalMemoryOriginal, val start: Int, val end: Int) {
    val text: String get() = source.text.substring(start, end)
}

interface LocalMemoryRecognitionStore {
    fun enqueue(identity: LocalMemoryRecognitionIdentity, limit: Int = 32)
    fun recover(identity: LocalMemoryRecognitionIdentity)
    fun claim(identity: LocalMemoryRecognitionIdentity): LocalMemoryRecognitionJob?
    fun original(job: LocalMemoryRecognitionJob): LocalMemoryOriginal?
    fun skipNoText(job: LocalMemoryRecognitionJob, source: LocalMemoryOriginal, authorized: () -> Boolean): Boolean = false
    fun catalogue(job: LocalMemoryRecognitionJob): List<LocalMemoryTopic>
    fun finish(job: LocalMemoryRecognitionJob, window: LocalMemoryWindow, annotations: List<LocalMemoryAnnotation>, authorized: () -> Boolean): Boolean
    fun release(job: LocalMemoryRecognitionJob, retry: Boolean)
    fun pending(identity: LocalMemoryRecognitionIdentity): Boolean
}

fun interface LocalMemoryRecognizer {
    suspend fun recognize(window: LocalMemoryWindow, catalogue: List<LocalMemoryTopic>): List<LocalMemoryAnnotation>
}
