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
    private var openTextBatchId: String? = null
    private var quietWindow: Job? = null
    private var maximumWindow: Job? = null
    private var typingDeadline: Job? = null
    private var composingContinuation = false
    private data class DeferredReply(val item: OutgoingDraft, val result: QueuedTextBatchResult)
    private var deferredReplies: List<DeferredReply> = emptyList()
    fun hasPendingReplies() = deferredReplies.isNotEmpty()

    fun composerChanged(value: String) {
        if (read().pendingOutgoing.none(::plainText) && deferredReplies.isEmpty()) return
        composingContinuation = value.isNotBlank() && read().draftImage == null
        if (composingContinuation) {
            if (typingDeadline?.isActive != true) typingDeadline = scope.launch {
                delay(15_000)
                composingContinuation = false
                closeTextWindow()
                flushReplies()
            }
        } else {
            typingDeadline?.cancel(); typingDeadline = null
            openTextBatchId?.let(::openTextWindow)
            scope.launch { flushReplies() }
        }
    }

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
            val id = UUID.randomUUID().toString().replace("-", "")
            val plainText = voiceText == null && image == null && voiceDraftId == null
            composingContinuation = false; typingDeadline?.cancel(); typingDeadline = null
            val textBatchId = if (plainText) openTextBatchId?.takeIf { canJoin(it, state, text) } ?: id else null
            if (textBatchId != openTextBatchId) closeTextWindow()
            val item = OutgoingDraft(id, chat.id, epoch, state.serverUrl, state.providerId, state.model,
                text, image, voiceDraftId, textBatchId = textBatchId)
            update { current -> current.copy(pendingOutgoing = current.pendingOutgoing + item,
                draft = if (voiceText == null && current.draft == state.draft) "" else current.draft,
                draftImage = if (voiceText == null && current.draftImage?.attachment?.id == image?.attachment?.id) null else current.draftImage,
                error = null, notice = null) }
            if (textBatchId != null) openTextWindow(textBatchId)
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
        val group = if (item.textBatchId == null) listOf(item) else state.pendingOutgoing.filter { it.textBatchId == item.textBatchId }
        if (group.any { it.submittedMessageId != null || it.phase == OutgoingPhase.SUBMITTED_NO_REPLY }) {
            update { it.copy(error = "这条消息已经发出，不用再发一遍；你可以接着说") }; return
        }
        if (!state.connected || group.any { it.phase != OutgoingPhase.FAILED || !matchesTarget(it, state) }) {
            update { it.copy(error = "请先恢复原来的连接，再重试这条消息") }; return
        }
        val ids = group.map { it.id }.toSet()
        update { it.copy(pendingOutgoing = it.pendingOutgoing.map { row ->
            if (row.id in ids) row.copy(phase = OutgoingPhase.QUEUED, connectionEpoch = epoch,
                attempt = row.attempt + 1, problem = null) else row
        }, error = null) }
        resume()
    }

    fun discard(id: String) {
        val item = read().pendingOutgoing.firstOrNull { it.id == id && it.phase != OutgoingPhase.SENDING } ?: return
        update { it.copy(pendingOutgoing = it.pendingOutgoing.filterNot { row -> row.id == id }) }
        scope.launch { cleanup(item); flushReplies() }
        resume()
    }

    fun invalidate() {
        epoch++
        composingContinuation = false; typingDeadline?.cancel(); deferredReplies = emptyList()
        closeTextWindow(start = false)
        update { state ->
            val committedGroups = state.pendingOutgoing.filter { it.submittedMessageId != null }.mapNotNull { it.textBatchId }.toSet()
            state.copy(pendingOutgoing = state.pendingOutgoing.map { row ->
                val submitted = row.submittedMessageId != null || row.textBatchId?.let(committedGroups::contains) == true
                row.copy(phase = if (submitted) OutgoingPhase.SUBMITTED_NO_REPLY else OutgoingPhase.FAILED,
                    problem = "连接已改变，消息仍保留在这里；恢复原连接后再处理")
            })
        }
        worker?.cancel()
    }

    fun resume() {
        val state = read()
        if (!scope.isActive || worker?.isActive == true || state.isBusy || nextItems(state).isEmpty()) return
        val next = scope.launch(start = CoroutineStart.LAZY) { drain() }
        worker = next
        next.start()
    }

    private suspend fun drain() {
        val owner = currentCoroutineContext().job
        try {
            while (true) {
                val items = nextItems(read()).takeIf { it.isNotEmpty() } ?: return
                val token = begin("AI 正在输入") ?: return
                try { deliver(items) }
                finally { finish(token) }
                if (read().pendingOutgoing.any { row -> items.any { it.id == row.id } }) return
            }
        } finally {
            if (worker === owner) { worker = null; resume() }
        }
    }

    private suspend fun deliver(items: List<OutgoingDraft>) {
        items.forEach { item -> change(item) { it.copy(phase = OutgoingPhase.SENDING) } }
        try {
            val result = if (items.size == 1) {
                val item = items.single()
                val pair = repository.sendQueuedMessage(item.chatId, item.content, item.id,
                    item.image?.attachment, item.voiceDraftId) { submitted(item, it) }
                QueuedTextBatchResult(listOf(QueuedTextSubmission(item.id, pair.first)), pair.second)
            } else repository.sendQueuedTextBatch(items.first().chatId, items.map { QueuedTextInput(it.id, it.content) }) { id, message ->
                val item = items.firstOrNull { it.id == id }
                if (item != null) submitted(item, message)
                else check(items.none { matches(it, read()) }) { "连续消息提交来源不一致，请刷新查看" }
            }
            currentCoroutineContext().ensureActive()
            require(result.assistant.role == "assistant" && result.assistant.id.isNotBlank() &&
                result.submitted.map { it.requestId } == items.map { it.id } &&
                result.submitted.map { it.message.id }.distinct().size == items.size) { "连续消息回复结果不一致，请刷新查看" }
            items.zip(result.submitted).forEach { (item, row) -> submitted(item, row.message) }
            if (items.all(::plainText)) deferredReplies = deferredReplies + DeferredReply(items.first(), result)
            else publish(items.first(), result.submitted.map { it.message } + result.assistant)
            val ids = items.map { it.id }.toSet()
            update { state -> if (!items.all { matches(it, state) }) state else state.copy(
                pendingOutgoing = state.pendingOutgoing.filterNot { it.id in ids },
                notice = (repository as? com.zhiyu.app.data.LocalChatRepository)?.lastCalendarNotice ?: state.notice) }
            flushReplies()
        } catch (failure: Exception) {
            val committed = read().pendingOutgoing.any { row -> row.submittedMessageId != null && items.any { it.id == row.id } }
            items.forEach { failed(it, failure, committed) }
            if (failure is CancellationException) throw failure
            flushReplies()
        } finally {
            items.forEach { item ->
                val submitted = read().pendingOutgoing.firstOrNull { it.id == item.id }?.submittedMessageId
                if (submitted != null || read().pendingOutgoing.none { it.id == item.id }) cleanup(item)
            }
        }
    }

    private fun canJoin(id: String, state: AppState, text: String): Boolean {
        val tail = state.pendingOutgoing.lastOrNull() ?: return false
        val group = state.pendingOutgoing.filter { it.textBatchId == id }
        return tail.textBatchId == id && tail.phase == OutgoingPhase.QUEUED && tail.connectionEpoch == epoch &&
            matchesTarget(tail, state) && group.size < QueuedTextBatchRules.MAX_INPUTS &&
            group.sumOf { it.content.length.toLong() } + text.length <= QueuedTextBatchRules.MAX_CONTENT_CHARS
    }

    private fun openTextWindow(id: String) {
        if (openTextBatchId != id) {
            openTextBatchId = id
            maximumWindow = scope.launch {
                delay(OutgoingQueueRules.TEXT_MAX_WAIT_MILLIS)
                if (!composingContinuation) closeTextWindow(id)
            }
        }
        quietWindow?.cancel()
        quietWindow = scope.launch {
            delay(OutgoingQueueRules.TEXT_QUIET_MILLIS)
            if (!composingContinuation) closeTextWindow(id)
        }
    }

    private fun closeTextWindow(expected: String? = openTextBatchId, start: Boolean = true) {
        if (expected == null || openTextBatchId != expected) return
        openTextBatchId = null
        quietWindow?.cancel(); quietWindow = null
        maximumWindow?.cancel(); maximumWindow = null
        if (start) resume()
    }

    private fun nextItems(state: AppState): List<OutgoingDraft> {
        val first = state.pendingOutgoing.firstOrNull() ?: return emptyList()
        if (!state.connected || state.restoring || first.phase != OutgoingPhase.QUEUED || !matches(first, state) ||
            first.textBatchId != null && first.textBatchId == openTextBatchId) return emptyList()
        if (first.textBatchId == null) return listOf(first)
        if (deferredReplies.isNotEmpty()) {
            if (state.pendingOutgoing.any { it.textBatchId == openTextBatchId && openTextBatchId != null }) return emptyList()
            val remaining = (32 - deferredReplies.sumOf { it.result.submitted.size }).coerceAtLeast(1)
            return state.pendingOutgoing.takeWhile { plainText(it) && it.phase == OutgoingPhase.QUEUED && matches(it, state) }
                .runningFold(emptyList<OutgoingDraft>()) { group, item -> group + item }
                .last { group -> group.size <= minOf(remaining, QueuedTextBatchRules.MAX_INPUTS) &&
                    group.sumOf { it.content.length.toLong() } <= QueuedTextBatchRules.MAX_CONTENT_CHARS }
        }
        return state.pendingOutgoing.takeWhile { it.textBatchId == first.textBatchId && it.phase == OutgoingPhase.QUEUED && matches(it, state) }
    }

    private fun plainText(item: OutgoingDraft) = item.image == null && item.voiceDraftId == null && item.textBatchId != null
    private fun continuationPending() = read().pendingOutgoing
        .takeWhile { it.phase in setOf(OutgoingPhase.QUEUED, OutgoingPhase.SENDING) }.any(::plainText)
    private suspend fun flushReplies() {
        val atLimit = deferredReplies.sumOf { it.result.submitted.size } >= 32
        if (deferredReplies.isEmpty() || !atLimit && (composingContinuation || continuationPending())) return
        val replies = deferredReplies
        val first = replies.first().item
        val stamp = epoch
        val users = replies.flatMap { it.result.submitted }.map { it.message }
        val answers = replies.map { it.result.assistant }
        val order = users.map { it.id } + answers.map { it.id }
        try { repository.recordReplyTurn(first.chatId, users.map { it.id }, answers.map { it.id }) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            update { it.copy(notice = "消息已保存，连续消息的显示顺序暂时没能保存") }
        }
        if (stamp != epoch || deferredReplies != replies || !atLimit && (composingContinuation || continuationPending())) return
        deferredReplies = emptyList()
        update { state ->
            if (stamp != epoch || !matchesTarget(first, state)) state else {
                val rows = ((if (state.viewingOlder) emptyList() else state.messages) + users + answers).distinctBy { it.id }
                val ordered = ReplyTurnOrder.apply(rows, listOf(order))
                val recent = ordered.takeLast(100)
                val older = state.hasOlder || state.viewingOlder || ordered.size > recent.size
                state.copy(messages = recent, hasOlder = older, viewingOlder = false,
                    nextBefore = if (older) recent.firstOrNull()?.id else null)
            }
        }
    }

    private fun submitted(item: OutgoingDraft, message: Message) {
        val current = read()
        if (!matches(item, current) || current.pendingOutgoing.none { it.id == item.id && it.phase == OutgoingPhase.SENDING }) return
        require(message.role == "user" && message.content == item.content && message.id.isNotBlank()) { "消息提交结果不一致，请刷新查看" }
        if (item.textBatchId != null) require(message.mediaAttachments.isEmpty() && message.origin == "reply") { "连续文字提交来源不一致，请刷新查看" }
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

    private fun failed(item: OutgoingDraft, failure: Exception, batchCommitted: Boolean) {
        update { state ->
            if (!matches(item, state)) state else {
                val expired = failure is ApiFailure && failure.isSessionExpired
                val message = if (expired) "API Key 暂时无法使用，请恢复连接后再继续"
                    else if (batchCommitted) "消息已经发出，这次回复没等到；不用再发一遍" else "这条消息暂时没发出去，可以点重试"
                state.copy(connected = if (expired) false else state.connected,
                    memory = if (expired) MemoryPanelState() else state.memory, error = message,
                    pendingOutgoing = state.pendingOutgoing.map { row ->
                        if (row.id != item.id || row.connectionEpoch != item.connectionEpoch) row else
                            row.copy(phase = if (row.submittedMessageId == null && !batchCommitted) OutgoingPhase.FAILED else OutgoingPhase.SUBMITTED_NO_REPLY,
                                problem = if (row.submittedMessageId == null && !batchCommitted) message else "消息已经发出，这次回复没等到；不用再发一遍")
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
