package com.zhiyu.app.domain

enum class OutgoingPhase { QUEUED, SENDING, FAILED, SUBMITTED_NO_REPLY }

/** Only a user-approved send snapshot; never rewritten by a newer composer/picker selection. */
data class OutgoingDraft(
    val id: String, val chatId: String, val connectionEpoch: Long,
    val serverUrl: String, val providerId: String, val model: String,
    val content: String, val image: ChatImageDraft? = null, val voiceDraftId: String? = null,
    val phase: OutgoingPhase = OutgoingPhase.QUEUED,
    val submittedMessageId: String? = null, val problem: String? = null,
    val attempt: Long = 0,
)

object OutgoingQueueRules {
    const val CAPACITY = 32
    fun matches(draft: OutgoingDraft, chatId: String?, serverUrl: String, providerId: String, model: String) =
        draft.chatId == chatId && draft.serverUrl == serverUrl && draft.providerId == providerId && draft.model == model
}
