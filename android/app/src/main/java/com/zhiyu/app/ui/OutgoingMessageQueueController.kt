package com.zhiyu.app.ui

import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import java.util.UUID

/** Main-thread enqueue/ownership; repository callbacks may arrive on IO and only update immutable state. */
internal class OutgoingMessageQueueController(
    private val scope: CoroutineScope, private val repository: ChatRepository,
    private val read: () -> AppState, private val update: ((AppState) -> AppState) -> Unit,
    private val begin: (String) -> Long?, private val finish: (Long) -> Unit,
    private val cleanupPhoto: suspend (String) -> Unit,
) {
    @Volatile private var epoch = 0L
    private var worker: Job? = null

    fun enqueue(voiceText: String? = null, voiceDraftId: String? = null): Boolean {
        val state = read()
        try {
            check(state.connected && !state.restoring) { "请先连接，再发送消息" }
            val chat = state.selected ?: error("对话还未准备好，请刷新后重试")
            check(state.pendingOutgoing.size < OutgoingQueueRules.CAPACITY) { "待发送的消息有点多，等前面的发出后再试试" }
            val image = if (voiceText == null) state.draftImage else null
            val raw = voiceText ?: state.draft
            val text = if (image == null) ConnectionRules.message(raw) else raw
            require(voiceDraftId == null || Regex("[a-f0-9]{32}").matches(voiceDraftId)) { "语音尚未准备好，请重新录制" }
            val item = OutgoingDraft(UUID.randomUUID().toString().replace("-", ""), chat.id, epoch,
                state.serverUrl, state.providerId, state.model, text, image, voiceDraftId)
            update { current -> current.copy(pendingOutgoing = current.pendingOutgoing + item,
                draft = if (voiceText == null && current.draft == state.draft) "" else current.draft,
                draftImage = if (voiceText == null && current.draftImage?.attachment?.id == image?.attachment?.id) null else current.draftImage,
                error = null, notice = null) }
            resume()
            return true
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            update { it.copy(error = if (failure is IllegalArgumentException || failure is IllegalStateException)
                failure.message else "消息暂时没能加入发送队列，请再试试") }
            return false
        }
    }

    fun retry(id: String) {
        val state = read(); val item = state.pendingOutgoing.firstOrNull { it.id == id } ?: return
        if (item.submittedMessageId != null) {
            update { it.copy(error = "这条消息已经发出，不用再发一遍；你可以接着说") }; return
        }
        if (item.phase != OutgoingPhase.FAILED || !state.connected || !matchesTarget(item, state)) {
            update { it.copy(error = "请先恢复原来的连接，再重试这条消息") }; return
        }
        update { it.copy(pendingOutgoing = it.pendingOutgoing.map { row ->
            if (row.id == id) row.copy(phase = OutgoingPhase.QUEUED, connectionEpoch = epoch,
                attempt = row.attempt + 1, problem = null) else row
        }, error = null) }
        resume()
    }

    fun discard(id: String) {
        val item = read().pendingOutgoing.firstOrNull { it.id == id && it.phase != OutgoingPhase.SENDING } ?: return
        update { it.copy(pendingOutgoing = it.pendingOutgoing.filterNot { row -> row.id == id }) }
        scope.launch { cleanup(item) }
        resume()
    }

    fun invalidate() {
        epoch++
        update { it.copy(pendingOutgoing = it.pendingOutgoing.map { row -> row.copy(
            phase = if (row.submittedMessageId == null) OutgoingPhase.FAILED else OutgoingPhase.SUBMITTED_NO_REPLY,
            problem = "连接已改变，消息仍保留在这里；恢复原连接后再处理") }) }
        worker?.cancel()
    }

    fun resume() {
        val state = read(); val first = state.pendingOutgoing.firstOrNull() ?: return
        if (worker?.isActive == true || state.isBusy || !state.connected || state.restoring ||
            first.phase != OutgoingPhase.QUEUED || !matches(first, state)) return
        val next = scope.launch(start = CoroutineStart.LAZY) { drain() }
        worker = next
        next.start()
    }

    private suspend fun drain() {
        val owner = currentCoroutineContext().job
        try {
            while (true) {
                val state = read(); val item = state.pendingOutgoing.firstOrNull() ?: return
                if (item.phase != OutgoingPhase.QUEUED || !matches(item, state)) return
                val token = begin("AI 正在输入") ?: return
                try { deliver(item) }
                finally { finish(token) }
                if (read().pendingOutgoing.any { it.id == item.id }) return
            }
        } finally {
            if (worker === owner) { worker = null; resume() }
        }
    }

    private suspend fun deliver(item: OutgoingDraft) {
        change(item) { it.copy(phase = OutgoingPhase.SENDING) }
        try {
            val submitted: (Message) -> Unit = { message -> submitted(item, message) }
            val pair = repository.sendQueuedMessage(item.chatId, item.content, item.id,
                item.image?.attachment, item.voiceDraftId, submitted)
            currentCoroutineContext().ensureActive()
            require(pair.second.role == "assistant") { "回复结果不一致，请刷新查看" }
            submitted(item, pair.first)
            publish(item, listOf(pair.first, pair.second))
            update { state -> if (!matches(item, state)) state else state.copy(
                pendingOutgoing = state.pendingOutgoing.filterNot { it.id == item.id },
                notice = (repository as? com.zhiyu.app.data.LocalChatRepository)?.lastCalendarNotice ?: state.notice) }
        } catch (failure: Exception) {
            failed(item, failure)
            if (failure is CancellationException) throw failure
        } finally {
            val submitted = read().pendingOutgoing.firstOrNull { it.id == item.id }?.submittedMessageId
            if (submitted != null || read().pendingOutgoing.none { it.id == item.id }) cleanup(item)
        }
    }

    private fun submitted(item: OutgoingDraft, message: Message) {
        val current = read()
        if (!matches(item, current) || current.pendingOutgoing.none { it.id == item.id && it.phase == OutgoingPhase.SENDING }) return
        require(message.role == "user" && message.content == item.content && message.id.isNotBlank()) { "消息提交结果不一致，请刷新查看" }
        if (item.image != null) require(message.mediaAttachments == listOf(item.image.attachment)) { "图片提交结果不一致，请刷新查看" }
        if (item.voiceDraftId != null) require(message.origin == "voice_user:${item.voiceDraftId}") { "语音提交结果不一致，请刷新查看" }
        change(item) { it.copy(submittedMessageId = message.id) }
        publish(item, listOf(message))
    }

    private fun publish(item: OutgoingDraft, messages: List<Message>) = update { state ->
        if (!matches(item, state)) state else {
            val combined = ((if (state.viewingOlder) emptyList() else state.messages) + messages).distinctBy { it.id }
            val recent = combined.takeLast(100)
            val older = state.hasOlder || state.viewingOlder || combined.size > recent.size
            state.copy(messages = recent, hasOlder = older, nextBefore = if (older) recent.firstOrNull()?.id else null, viewingOlder = false)
        }
    }

    private fun failed(item: OutgoingDraft, failure: Exception) {
        update { state ->
            if (!matches(item, state)) state else {
                val expired = failure is ApiFailure && failure.isSessionExpired
                val message = if (expired) "API Key 暂时无法使用，请恢复连接后再继续" else "这条消息暂时没发出去，可以点重试"
                state.copy(connected = if (expired) false else state.connected,
                    memory = if (expired) MemoryPanelState() else state.memory, error = message,
                    pendingOutgoing = state.pendingOutgoing.map { row ->
                        if (row.id != item.id || row.connectionEpoch != item.connectionEpoch) row else
                            row.copy(phase = if (row.submittedMessageId == null) OutgoingPhase.FAILED else OutgoingPhase.SUBMITTED_NO_REPLY,
                                problem = if (row.submittedMessageId == null) message else "消息已经发出，这次回复没等到；不用再发一遍")
                    })
            }
        }
    }

    private suspend fun cleanup(item: OutgoingDraft) {
        item.image?.let { cleanupPhoto(it.attachment.id) }
        item.voiceDraftId?.let { id ->
            try { repository.discardVoiceDraft(id) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                update { it.copy(notice = "语音已保留，临时文件暂时没能清理") }
            }
        }
    }

    private fun change(item: OutgoingDraft, transform: (OutgoingDraft) -> OutgoingDraft) = update { state ->
        if (!matches(item, state)) state else state.copy(pendingOutgoing = state.pendingOutgoing.map { row ->
            if (row.id == item.id && row.connectionEpoch == item.connectionEpoch && row.attempt == item.attempt) transform(row) else row
        })
    }
    private fun matchesTarget(item: OutgoingDraft, state: AppState) =
        OutgoingQueueRules.matches(item, state.selectedId, state.serverUrl, state.providerId, state.model)
    private fun matches(item: OutgoingDraft, state: AppState) = item.connectionEpoch == epoch && matchesTarget(item, state) &&
        state.pendingOutgoing.any { it.id == item.id && it.connectionEpoch == item.connectionEpoch && it.attempt == item.attempt }
}
