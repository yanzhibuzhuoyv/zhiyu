package com.zhiyu.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiyu.app.data.DocumentReader
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(private val repository: ChatRepository, private val reader: DocumentReader) : ViewModel() {
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private var connectionCleanup: Deferred<Unit>? = null
    private var profileEdited = false
    private var operationEpoch = 0L
    private var activeOperation: Long? = null
    private var activeCall: Any? = null
    private val outgoing by lazy {
        OutgoingMessageQueueController(viewModelScope, repository, { mutableState.value },
            { transform -> mutableState.update(transform) }, ::beginAction, ::finishAction, ::cleanupSentPhotoDraft)
    }

    init {
        viewModelScope.launch {
            try {
                restoreConnection()
                restorePhotoDraft()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(connected = false, error = friendly(error)) }
            } finally { mutableState.update { it.copy(restoring = false, busy = null) } }
        }
    }

    private suspend fun restorePhotoDraft() {
        try {
            val image = repository.savedPhotoDraft()
            if (image != null && mutableState.value.messages.any { message ->
                message.role == "user" && message.mediaAttachments.any { it.id == image.attachment.id }
            }) {
                mutableState.update { it.copy(draftImage = null) }
                cleanupSentPhotoDraft(image.attachment.id)
                return
            }
            mutableState.update { it.copy(draftImage = image) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(error = friendly(error)) }
        }
    }

    private suspend fun cleanupSentPhotoDraft(id: String) {
        try { repository.discardPhotoDraft(id) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(notice = listOfNotNull(it.notice,
                "图片已发送，草稿暂时没能清理；这条消息不用再发。").distinct().joinToString("\n")) }
        }
    }

    fun serverUrl(value: String) {
        if (mutableState.value.isBusy) return
        outgoing.invalidate()
        val previousCleanup = connectionCleanup
        connectionCleanup = viewModelScope.async {
            previousCleanup?.await()
            repository.forgetLocalConnection()
        }
        mutableState.update { it.copy(serverUrl = value, connected = false, providers = emptyList(),
            canRestoreConnection = false,
            providerId = it.binding?.providerId.orEmpty(), model = it.binding?.model.orEmpty(), apiKey = "",
            conversations = emptyList(), selectedId = null, messages = emptyList(), hasOlder = false,
            nextBefore = null, viewingOlder = false, memory = MemoryPanelState(), documents = emptyList(), draft = "", error = null) }
        loadProviders()
    }
    fun apiKey(value: String) { mutableState.update { it.copy(apiKey = value, error = null) } }
    fun model(value: String) { if (mutableState.value.binding == null) mutableState.update { it.copy(model = value, error = null) } }
    fun draft(value: String) { mutableState.update { it.copy(draft = value) }; outgoing.composerChanged(value) }
    // A completed picker import must survive an unrelated refresh or an older send.
    fun draftImage(value: ChatImageDraft?) { mutableState.update { it.copy(draftImage = value) } }
    fun dismissError() { mutableState.update { it.copy(error = null, notice = null) } }
    fun provider(id: String) {
        if (mutableState.value.binding != null) return
        val provider = mutableState.value.providers.find { it.id == id } ?: return
        mutableState.update { it.copy(providerId = id, model = provider.defaultModel, apiKey = "", error = null) }
    }
    fun loadProviders() = action("重新尝试连接") { restoreConnection() }
    private suspend fun restoreConnection() {
        val saved = repository.savedConnection()
        val binding = repository.savedBinding()
        check(saved == null || binding == null || (binding.providerId == saved.providerId && binding.model == saved.model)) {
            "保存的连接与已绑定 AI 不一致"
        }
        mutableState.update { it.copy(
            canRestoreConnection = saved != null, binding = binding,
            profile = if (profileEdited) it.profile else binding?.profile,
            serverUrl = saved?.serverUrl ?: it.serverUrl,
            providerId = saved?.providerId ?: binding?.providerId ?: it.providerId,
            model = saved?.model ?: binding?.model ?: it.model,
            apiKey = saved?.apiKey ?: it.apiKey,
        ) }
        loadProviderCatalog()
        if (saved != null) {
            val editedProfile = mutableState.value.profile.takeIf { profileEdited }
            loadConversations()
            applyEditedProfile(editedProfile)
            val documents = repository.documents()
            val notice = repository.connectionNotice()
            mutableState.update { it.copy(connected = true, documents = documents, notice = notice) }
        }
    }
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
        mutableState.update { it.copy(binding = binding, serverUrl = saved.serverUrl, model = saved.model,
            canRestoreConnection = true,
            memory = MemoryPanelState(), error = null, notice = null) }
        loadConversations()
        applyEditedProfile(editedProfile)
        val documents = repository.documents()
        val notice = repository.connectionNotice()
        mutableState.update { it.copy(connected = true, documents = documents, notice = notice) }
    }
    private suspend fun applyEditedProfile(editedProfile: CompanionProfile?) {
        if (profileEdited && editedProfile?.mode == "custom" && editedProfile != mutableState.value.selected?.profile) {
            try {
                val updated = repository.changeProfile(mutableState.value.selectedId!!, editedProfile)
                val updatedBinding = repository.savedBinding()
                mutableState.update { it.copy(conversations = listOf(updated), profile = updated.profile, binding = updatedBinding) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(profile = editedProfile) }
                throw error
            }
        }
        profileEdited = false
    }
    fun refresh() = if (outgoing.hasPendingReplies()) null else action("刷新") {
        loadConversations()
        mutableState.update { it.copy(documents = repository.documents()) }
    }
    fun loadOlder() = if (outgoing.hasPendingReplies()) null else action("读取更早的消息") {
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
        val binding = repository.savedBinding()
        mutableState.update { it.copy(conversations = listOf(selected), selectedId = selected.id,
            profile = if (profileEdited) it.profile else selected.profile,
            binding = binding,
            memory = if (it.selectedId == selected.id) it.memory else MemoryPanelState()) }
        loadHistory(selected.id)
    }
    private suspend fun loadHistory(id: String, before: String? = null) {
        val page = repository.history(id, before)
        check(page.messages.size <= 100 && (!page.hasMore || page.nextBefore != null)) { "会话历史响应不完整" }
        mutableState.update { it.copy(messages = page.messages, hasOlder = page.hasMore,
            nextBefore = page.nextBefore, viewingOlder = before != null) }
    }
    fun openMemory() {
        if (mutableState.value.isBusy || mutableState.value.restoring || !mutableState.value.connected) return
        mutableState.update { it.copy(memory = MemoryPanelState(open = true)) }
        loadMemoryPage()
    }
    fun closeMemory() { mutableState.update { it.copy(memory = MemoryPanelState()) } }
    fun olderMemory() {
        val before = mutableState.value.memory.nextBefore ?: return
        loadMemoryPage(before)
    }
    fun recentMemory() = loadMemoryPage()
    fun backToMemoryList() {
        if (mutableState.value.memory.entries.isEmpty()) openMemory()
        else mutableState.update { it.copy(memory = it.memory.copy(focusId = null, selected = null)) }
    }
    private fun loadMemoryPage(before: String? = null) = action("读取记忆") {
        val id = mutableState.value.selectedId ?: return@action
        val page = MemoryRules.page(repository.memory(id, before), before)
        mutableState.update {
            if (!it.memory.open || it.selectedId != id) it
            else it.copy(memory = it.memory.copy(entries = page.messages, hasMore = page.hasMore,
                nextBefore = page.nextBefore, viewingOlder = before != null,
                automaticAnnotations = page.automaticAnnotations, focusId = null, selected = null))
        }
    }
    fun openMessageMemory(messageId: String) {
        if (mutableState.value.isBusy || mutableState.value.restoring || !mutableState.value.connected) return
        mutableState.update { it.copy(memory = it.memory.copy(open = true, focusId = messageId, selected = null)) }
        action("读取这句原话的记忆") {
            val id = mutableState.value.selectedId ?: return@action
            val entry = MemoryRules.entry(repository.messageMemory(id, messageId))
            check(entry.messageId == messageId) { "记忆来源与所选消息不一致" }
            updateMemory(id, entry)
        }
    }
    fun changeMemoryTier(tier: String) = changeMemory(tier = tier)
    fun archiveMemory() = changeMemory(indexState = "cold")
    fun restoreMemory() = changeMemory(indexState = "active")
    fun confirmIndependentMemory() = changeMemory(confirmIndependent = true)
    private fun changeMemory(tier: String? = null, indexState: String? = null, confirmIndependent: Boolean = false) = action("保存记忆设置") {
        val current = mutableState.value
        val id = current.selectedId ?: return@action
        val entry = current.memory.selected ?: return@action
        val change = MemoryRules.change(entry, tier, indexState, confirmIndependent)
        try {
            val updated = MemoryRules.entry(repository.changeMemory(id, entry.messageId, change))
            check(updated.messageId == entry.messageId) { "记忆来源与所选消息不一致" }
            updateMemory(id, updated)
        } catch (failure: ApiFailure) {
            if (failure.status == 409 && mutableState.value.memory.open) {
                try {
                    val latest = MemoryRules.entry(repository.messageMemory(id, entry.messageId))
                    check(latest.messageId == entry.messageId) { "记忆来源与所选消息不一致" }
                    updateMemory(id, latest)
                } catch (refresh: Exception) {
                    if (refresh is CancellationException) throw refresh
                    failure.addSuppressed(refresh)
                }
            }
            throw failure
        }
    }
    private fun updateMemory(id: String, entry: MemoryEntry) {
        mutableState.update {
            if (!it.memory.open || it.selectedId != id || it.memory.focusId != entry.messageId) it
            else it.copy(memory = it.memory.updated(entry))
        }
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
    fun send() { outgoing.enqueue() }
    fun sendVoiceText(value: String) { outgoing.enqueue(value) }
    fun sendVoiceClip(transcript: String, draftId: String): Boolean = outgoing.enqueue(transcript, draftId)
    fun retryOutgoing(id: String) = outgoing.retry(id)
    fun discardOutgoing(id: String) = outgoing.discard(id)
    fun streamCallText(chatId: String, content: String): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.channelFlow { callText(chatId, content) { send(it) } }.buffer(0)
    /** The caller owns cancellation for one final recognized utterance. */
    suspend fun sendCallText(chatId: String, content: String): String = callText(chatId, content, null)
    private suspend fun callText(chatId: String, content: String, onDelta: (suspend (String) -> Unit)?): String = withContext(Dispatchers.Main.immediate) {
        val current = mutableState.value
        check(current.connected && current.selectedId == chatId && current.selected?.id == chatId) { "这段通话已不在当前聊天中" }
        val text = ConnectionRules.message(content)
        check(activeCall == null) { "请等这句话说完，或先打断它" }
        val token = Any()
        val cleanup = connectionCleanup
        activeCall = token
        fun stillCurrent() = activeCall === token && connectionCleanup === cleanup &&
            mutableState.value.let { it.connected && it.selectedId == chatId &&
                it.serverUrl == current.serverUrl && it.binding == current.binding }
        try {
            cleanup?.await()
            check(stillCurrent()) { "通话已结束" }
            val result = repository.sendCallMessage(chatId, text) { delta ->
                currentCoroutineContext().ensureActive()
                check(stillCurrent()) { "通话已结束" }
                onDelta?.invoke(delta)
            }
            currentCoroutineContext().ensureActive()
            check(stillCurrent()) { "通话已结束" }
            check(result.first.role == "user" && result.first.content == text && result.second.role == "assistant") {
                "这轮通话的回复不完整"
            }
            result.second.content
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update {
                if (!stillCurrent()) it else {
                    val expired = error is ApiFailure && error.isSessionExpired
                    if (expired) it.copy(connected = false) else it
                }
            }
            throw error
        } finally {
            if (activeCall === token) activeCall = null
        }
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
        val token = beginAction("退出连接") ?: return
        outgoing.invalidate()
        val old = mutableState.value
        viewModelScope.launch {
            var error: String? = null
            try { repository.logout() } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "退出未完成，请重试；恋人绑定和聊天会保留"
            } finally { finishAction(token) }
            if (operationEpoch == token) {
                mutableState.value = AppState(restoring = false, binding = old.binding, profile = old.profile, serverUrl = old.serverUrl,
                    providers = old.providers, providerId = old.providerId, model = old.model, error = error,
                    pendingOutgoing = mutableState.value.pendingOutgoing)
            }
        }
    }
    private fun beginAction(label: String): Long? {
        if (mutableState.value.isBusy || mutableState.value.restoring) return null
        val token = ++operationEpoch
        activeOperation = token
        mutableState.update { it.copy(busy = label, error = null, notice = null) }
        return token
    }
    private fun finishAction(token: Long) {
        if (activeOperation != token) return
        activeOperation = null
        mutableState.update { it.copy(busy = null, pendingMessage = null, pendingImage = null) }
        outgoing.resume()
    }
    private fun action(label: String, block: suspend () -> Unit) {
        val token = beginAction(label) ?: return
        viewModelScope.launch {
            try { connectionCleanup?.await(); block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update {
                    if (activeOperation != token) return@update it
                    val expired = error is ApiFailure && error.isSessionExpired
                    it.copy(error = friendly(error), connected = if (expired) false else it.connected,
                        memory = if (expired) MemoryPanelState() else it.memory)
                }
            } finally { finishAction(token) }
        }
    }
    private fun friendly(error: Exception): String {
        val key = mutableState.value.apiKey
        val text = when (error) {
            is ApiFailure, is IllegalArgumentException, is IllegalStateException -> error.message ?: "操作失败，请稍后重试"
            else -> "暂时无法处理回复，请稍后重试"
        }
        return if (key.length >= 4) text.replace(key, "[已隐藏]") else text
    }
}
