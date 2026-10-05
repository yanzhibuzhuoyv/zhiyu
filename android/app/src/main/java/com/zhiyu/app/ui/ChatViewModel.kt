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
    private var profileEdited = false

    init {
        viewModelScope.launch {
            try {
                val binding = repository.savedBinding()
                mutableState.update { it.copy(binding = binding, profile = binding?.profile, providerId = binding?.providerId.orEmpty(), model = binding?.model.orEmpty()) }
                val saved = repository.savedConnection()
                if (saved != null) {
                    val restoredBinding = repository.savedBinding()
                    check(restoredBinding == null || (restoredBinding.providerId == saved.providerId && restoredBinding.model == saved.model)) { "保存的连接与已绑定 AI 不一致" }
                    mutableState.update { it.copy(binding = restoredBinding, serverUrl = saved.serverUrl, providerId = saved.providerId, model = saved.model, apiKey = saved.apiKey, busy = "恢复连接") }
                    loadProviderCatalog()
                    mutableState.update { it.copy(connected = true) }
                    loadConversations()
                    mutableState.update { it.copy(documents = repository.documents()) }
                } else {
                    loadProviderCatalog()
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
        mutableState.update { it.copy(serverUrl = value, connected = false, providers = emptyList(),
            providerId = it.binding?.providerId.orEmpty(), model = it.binding?.model.orEmpty(), apiKey = "",
            conversations = emptyList(), selectedId = null, messages = emptyList(), hasOlder = false,
            nextBefore = null, viewingOlder = false, documents = emptyList(), draft = "", error = null) }
        loadProviders()
    }
    fun apiKey(value: String) { mutableState.update { it.copy(apiKey = value, error = null) } }
    fun model(value: String) { if (mutableState.value.binding == null) mutableState.update { it.copy(model = value, error = null) } }
    fun draft(value: String) { mutableState.update { it.copy(draft = value) } }
    fun dismissError() { mutableState.update { it.copy(error = null, notice = null) } }
    fun provider(id: String) {
        if (mutableState.value.binding != null) return
        val provider = mutableState.value.providers.find { it.id == id } ?: return
        mutableState.update { it.copy(providerId = id, model = provider.defaultModel, apiKey = "", error = null) }
    }
    fun loadProviders() = action("加载服务商") { loadProviderCatalog() }
    private suspend fun loadProviderCatalog() {
        val current = mutableState.value
        check(current.serverUrl.isNotBlank()) { "此安装包的服务配置尚未完成，请使用已配置的版本" }
        val providers = repository.providers(mutableState.value.serverUrl)
        check(current.binding == null || providers.any { it.id == current.binding.providerId }) { "已绑定的 AI 暂时不可用，请稍后重试" }
        val selected = providers.find { it.id == (current.binding?.providerId ?: current.providerId) } ?: providers.firstOrNull()
            ?: throw IllegalStateException("服务暂时没有可用的 AI 公司，请稍后重试")
        mutableState.update { it.copy(
            providers = providers, providerId = selected.id,
            model = current.binding?.model ?: if (selected.id == current.providerId && current.model.isNotBlank()) current.model else selected.defaultModel,
            apiKey = if (selected.id == current.providerId) current.apiKey else "",
        ) }
    }
    fun connect() = action("验证 API Key") {
        val current = mutableState.value
        val editedProfile = current.profile.takeIf { profileEdited }
        repository.login(current.serverUrl, current.providerId, current.apiKey, current.model, current.profile)
        val saved = repository.savedConnection() ?: throw IllegalStateException("连接信息保存失败")
        val binding = repository.savedBinding()
        mutableState.update { it.copy(connected = true, binding = binding, serverUrl = saved.serverUrl, model = saved.model, error = null, notice = null) }
        loadConversations()
        if (profileEdited && editedProfile?.mode == "custom" && editedProfile != mutableState.value.profile) {
            val updated = repository.changeProfile(mutableState.value.selectedId!!, editedProfile)
            val updatedBinding = repository.savedBinding()
            mutableState.update { it.copy(conversations = listOf(updated), profile = updated.profile, binding = updatedBinding) }
        }
        profileEdited = false
        mutableState.update { it.copy(documents = repository.documents()) }
    }
    fun refresh() = action("刷新") {
        loadConversations()
        mutableState.update { it.copy(documents = repository.documents()) }
    }
    fun loadOlder() = action("读取更早的消息") {
        val current = mutableState.value
        val id = current.selectedId ?: return@action
        val before = current.nextBefore ?: return@action
        loadHistory(id, before)
    }
    private suspend fun loadConversations() {
        val conversations = repository.conversations()
        val current = mutableState.value
        val selected = conversations.find { it.id == current.selectedId && it.model == current.model }
            ?: conversations.filter { it.model == current.model }.minWithOrNull(compareBy<Conversation> { it.createdAt }.thenBy { it.id })
            ?: repository.createConversation(current.model, current.defaultRag, current.profile)
        mutableState.update { it.copy(conversations = listOf(selected), selectedId = selected.id, profile = selected.profile) }
        loadHistory(selected.id)
    }
    private suspend fun loadHistory(id: String, before: String? = null) {
        val page = repository.history(id, before)
        check(page.messages.size <= 100 && (!page.hasMore || page.nextBefore != null)) { "会话历史响应不完整" }
        mutableState.update { it.copy(messages = page.messages, hasOlder = page.hasMore,
            nextBefore = page.nextBefore, viewingOlder = before != null) }
    }
    fun setProfile(value: CompanionProfile?) = action("保存恋人设定") {
        val profile = CompanionRules.normalize(value)
        val current = mutableState.value
        CompanionRules.checkChange(current.binding, profile)
        if (current.connected) {
            val id = current.selectedId ?: throw IllegalStateException("对话还未准备好")
            val updated = repository.changeProfile(id, profile)
            mutableState.update { it.copy(conversations = listOf(updated), profile = updated.profile, notice = "恋人设定已保存") }
            val binding = repository.savedBinding()
            mutableState.update { it.copy(binding = binding) }
        } else {
            profileEdited = true
            mutableState.update { it.copy(profile = profile) }
        }
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
        val conversation = mutableState.value.selected ?: throw IllegalStateException("对话还未准备好，请刷新后重试")
        mutableState.update { it.copy(pendingMessage = content) }
        try {
            val result = repository.sendMessage(conversation.id, content)
            val wasViewingOlder = mutableState.value.viewingOlder
            mutableState.update {
                val combined = (if (wasViewingOlder) emptyList() else it.messages) + result.first + result.second
                val messages = combined.takeLast(100)
                val hasOlder = wasViewingOlder || it.hasOlder || combined.size > messages.size
                it.copy(messages = messages, hasOlder = hasOlder, nextBefore = if (hasOlder) messages.first().id else null,
                    draft = "", pendingMessage = null, viewingOlder = false)
            }
            if (wasViewingOlder) loadHistory(conversation.id)
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
            mutableState.value = AppState(restoring = false, binding = old.binding, profile = old.profile, serverUrl = old.serverUrl, providers = old.providers, providerId = old.providerId, model = old.model, error = error)
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
