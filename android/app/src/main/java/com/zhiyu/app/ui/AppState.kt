package com.zhiyu.app.ui

import com.zhiyu.app.BuildConfig
import com.zhiyu.app.domain.*

data class AppState(
    val restoring: Boolean = true,
    val connected: Boolean = false,
    val canRestoreConnection: Boolean = false,
    val binding: CompanionBinding? = null,
    val profile: CompanionProfile? = null,
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val providers: List<Provider> = emptyList(),
    val providerId: String = "",
    val model: String = "",
    val apiKey: String = "",
    val conversations: List<Conversation> = emptyList(),
    val selectedId: String? = null,
    val messages: List<Message> = emptyList(),
    val hasOlder: Boolean = false,
    val nextBefore: String? = null,
    val viewingOlder: Boolean = false,
    val memory: MemoryPanelState = MemoryPanelState(),
    val documents: List<Document> = emptyList(),
    val draft: String = "",
    val draftImage: ChatImageDraft? = null,
    val defaultRag: Boolean = true,
    val busy: String? = null,
    val pendingMessage: String? = null,
    val pendingImage: MediaAttachment? = null,
    val pendingOutgoing: List<OutgoingDraft> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
) {
    val provider: Provider? get() = providers.find { it.id == providerId }
    val selected: Conversation? get() = conversations.find { it.id == selectedId }
    val isBusy get() = busy != null
    val isDemo get() = providerId == "demo"
}
