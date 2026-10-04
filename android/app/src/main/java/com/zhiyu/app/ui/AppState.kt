package com.zhiyu.app.ui

import com.zhiyu.app.BuildConfig
import com.zhiyu.app.domain.*

enum class AppTab(val label: String) { CHAT("对话"), LIBRARY("知识库"), SETTINGS("设置") }
data class AppState(
    val restoring: Boolean = true,
    val connected: Boolean = false,
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val providers: List<Provider> = emptyList(),
    val providerId: String = "",
    val model: String = "",
    val apiKey: String = "",
    val tab: AppTab = AppTab.CHAT,
    val conversations: List<Conversation> = emptyList(),
    val selectedId: String? = null,
    val messages: List<Message> = emptyList(),
    val documents: List<Document> = emptyList(),
    val draft: String = "",
    val defaultRag: Boolean = true,
    val busy: String? = null,
    val pendingMessage: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val provider: Provider? get() = providers.find { it.id == providerId }
    val selected: Conversation? get() = conversations.find { it.id == selectedId }
    val isBusy get() = busy != null
    val isDemo get() = providerId == "demo"
}
