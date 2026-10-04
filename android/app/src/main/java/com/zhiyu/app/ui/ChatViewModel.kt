package com.zhiyu.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiyu.app.data.DocumentReader
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(private val repository: ChatRepository, private val reader: DocumentReader) : ViewModel() {
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private var connectionCleanup: Deferred<Unit>? = null

    init {
        viewModelScope.launch {
            try {
                val saved = repository.savedConnection()
                if (saved != null) {
                    mutableState.update { it.copy(serverUrl = saved.serverUrl, providerId = saved.providerId, model = saved.model, apiKey = saved.apiKey, busy = "恢复连接") }
                    val providers = repository.providers(saved.serverUrl)
                    mutableState.update { it.copy(providers = providers, connected = true) }
                    loadConversations()
                    mutableState.update { it.copy(documents = repository.documents()) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(connected = false, error = friendly(error)) }
            } finally { mutableState.update { it.copy(restoring = false, busy = null) } }
        }
    }

    fun serverUrl(value: String) {
        if (mutableState.value.isBusy) return
        val previousCleanup = connectionCleanup
        connectionCleanup = viewModelScope.async {
            previousCleanup?.await()
            repository.forgetLocalConnection()
        }
        mutableState.update { it.copy(serverUrl = value, providers = emptyList(), providerId = "", model = "", apiKey = "", error = null) }
    }
    fun apiKey(value: String) { mutableState.update { it.copy(apiKey = value, error = null) } }
    fun model(value: String) { mutableState.update { it.copy(model = value, error = null) } }
    fun draft(value: String) { mutableState.update { it.copy(draft = value) } }
    fun tab(value: AppTab) { mutableState.update { it.copy(tab = value) } }
    fun dismissError() { mutableState.update { it.copy(error = null, notice = null) } }
    fun provider(id: String) {
        val provider = mutableState.value.providers.find { it.id == id } ?: return
        mutableState.update { it.copy(providerId = id, model = provider.defaultModel, apiKey = "", error = null) }
    }
    fun loadProviders() = action("检测后端") {
        val providers = repository.providers(mutableState.value.serverUrl)
        val first = providers.firstOrNull() ?: throw IllegalStateException("后端没有配置可用服务商")
        mutableState.update { it.copy(providers = providers, providerId = first.id, model = first.defaultModel, notice = "后端已连接，请输入服务商 API Key") }
    }
    fun connect() = action("验证 API Key") {
        val current = mutableState.value
        repository.login(current.serverUrl, current.providerId, current.apiKey, current.model)
        val saved = repository.savedConnection() ?: throw IllegalStateException("连接信息保存失败")
        mutableState.update { it.copy(connected = true, serverUrl = saved.serverUrl, model = saved.model, tab = AppTab.CHAT, error = null, notice = null) }
        loadConversations()
        mutableState.update { it.copy(documents = repository.documents()) }
    }
    fun refresh() = action("刷新") {
        loadConversations()
        mutableState.update { it.copy(documents = repository.documents()) }
    }
    private suspend fun loadConversations() {
        val conversations = repository.conversations()
        val current = mutableState.value.selectedId
        val selected = conversations.find { it.id == current } ?: conversations.firstOrNull()
        val messages = selected?.let { repository.messages(it.id) }.orEmpty()
        mutableState.update { it.copy(conversations = conversations, selectedId = selected?.id, messages = messages) }
    }
    fun selectConversation(id: String) = action("加载对话") {
        val messages = repository.messages(id)
        mutableState.update { it.copy(selectedId = id, messages = messages, draft = "", tab = AppTab.CHAT) }
    }
    fun createConversation() = action("创建对话") {
        createConversationInternal()
        mutableState.update { it.copy(draft = "") }
    }
    private suspend fun createConversationInternal(): Conversation {
        val current = mutableState.value
        val conversation = repository.createConversation(current.model, current.defaultRag)
        mutableState.update { it.copy(conversations = listOf(conversation) + it.conversations, selectedId = conversation.id, messages = emptyList(), tab = AppTab.CHAT) }
        return conversation
    }
    fun deleteConversation(id: String) = action("删除对话") {
        repository.deleteConversation(id)
        mutableState.update { it.copy(conversations = it.conversations.filterNot { c -> c.id == id }) }
        loadConversations()
    }
    fun setRag(enabled: Boolean) = action("更新检索设置") {
        val selected = mutableState.value.selected
        if (selected == null) mutableState.update { it.copy(defaultRag = enabled) }
        else {
            val updated = repository.changeRag(selected.id, enabled)
            mutableState.update { it.copy(conversations = it.conversations.map { c -> if (c.id == updated.id) updated else c }) }
        }
    }
    fun send() = action("AI 正在思考") {
        val content = ConnectionRules.message(mutableState.value.draft)
        val conversation = mutableState.value.selected ?: createConversationInternal()
        mutableState.update { it.copy(pendingMessage = content) }
        try {
            val result = repository.sendMessage(conversation.id, content)
            mutableState.update { it.copy(messages = it.messages + result.first + result.second, draft = "", pendingMessage = null) }
            val conversations = repository.conversations()
            mutableState.update { it.copy(conversations = conversations) }
        } finally { mutableState.update { it.copy(pendingMessage = null) } }
    }
    fun importDocument(uri: Uri) = action("导入知识库") {
        val document = repository.upload(reader.read(uri))
        mutableState.update { it.copy(documents = listOf(document) + it.documents.filterNot { d -> d.id == document.id }, notice = "已导入 ${document.name}") }
    }
    fun deleteDocument(id: String) = action("删除文档") {
        repository.deleteDocument(id)
        mutableState.update { it.copy(documents = it.documents.filterNot { d -> d.id == id }) }
    }
    fun disconnect() {
        if (mutableState.value.isBusy) return
        viewModelScope.launch {
            val old = mutableState.value
            mutableState.update { it.copy(busy = "退出连接") }
            var error: String? = null
            try { repository.logout() } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "本机 Key 已清除；后端会话注销未确认，过期后会自动失效"
            }
            mutableState.value = AppState(restoring = false, serverUrl = old.serverUrl, providers = old.providers, providerId = old.providerId, model = old.model, error = error)
        }
    }
    private fun action(label: String, block: suspend () -> Unit) {
        if (mutableState.value.isBusy || mutableState.value.restoring) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = label, error = null, notice = null) }
            try { connectionCleanup?.await(); block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = friendly(error), connected = if (error is ApiFailure && error.status == 401) false else it.connected) }
            } finally { mutableState.update { it.copy(busy = null) } }
        }
    }
    private fun friendly(error: Exception): String {
        val key = mutableState.value.apiKey
        val text = when (error) {
            is ApiFailure, is IllegalArgumentException, is IllegalStateException -> error.message ?: "操作失败，请稍后重试"
            else -> "响应内容无法处理，请检查后端版本或稍后重试"
        }
        return if (key.length >= 4) text.replace(key, "[已隐藏]") else text
    }
}
