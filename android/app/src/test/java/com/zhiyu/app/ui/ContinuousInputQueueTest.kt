package com.zhiyu.app.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.zhiyu.app.data.DocumentReader
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContinuousInputQueueTest {
    @Test fun failedContinuationKeepsItsQueuedTailButCannotHideAnEarlierSuccessfulAnswer() = model { vm, repo ->
        repo.replyGate = CompletableDeferred()
        send(vm, "成功的第一段"); advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        send(vm, "随后会失败的第二段")
        repo.replyGate!!.complete(Unit); runCurrent()
        repo.replyGate = CompletableDeferred()
        advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        assertEquals(2, repo.deliveries.size)
        send(vm, "仍须保留的第三段")
        repo.afterFailure = ApiFailure(503, "private failure")
        repo.replyGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, vm.state.value.messages.count { it.role == "assistant" })
        assertEquals(listOf(OutgoingPhase.SUBMITTED_NO_REPLY, OutgoingPhase.QUEUED), vm.state.value.pendingOutgoing.map { it.phase })
        assertEquals("仍须保留的第三段", vm.state.value.pendingOutgoing.last().content)
        val before = repo.historyCalls
        vm.refresh(); advanceUntilIdle()
        assertTrue(repo.historyCalls > before)
        assertEquals(2, repo.deliveries.size)
    }
    @Test fun failedContinuationReleasesTheEarlierSuccessfulReplyAndAllowsHistoryRefresh() = model { vm, repo ->
        repo.replyGate = CompletableDeferred()
        send(vm, "第一段已经发出"); advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        send(vm, "第二段补充")
        repo.replyGate!!.complete(Unit); runCurrent()
        assertEquals(0, vm.state.value.messages.count { it.role == "assistant" })
        repo.afterFailure = ApiFailure(503, "private failure")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.messages.count { it.role == "assistant" })
        assertEquals(OutgoingPhase.SUBMITTED_NO_REPLY, vm.state.value.pendingOutgoing.single().phase)
        val before = repo.historyCalls
        vm.refresh(); advanceUntilIdle()
        assertTrue(repo.historyCalls > before)
        assertEquals(2, repo.deliveries.size)
        assertEquals(1, vm.state.value.messages.count { it.role == "assistant" })
    }
    @Test fun aNearlyFullDeferredTurnCannotPullAnotherThirtyTwoOriginalsAcrossItsLimit() = model { vm, repo ->
        repo.replyGate = CompletableDeferred()
        repeat(31) { send(vm, "第一组$it") }
        advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        send(vm, "补充0"); repo.replyGate!!.complete(Unit); runCurrent()
        repeat(31) { send(vm, "补充${it + 1}") }
        advanceUntilIdle()
        assertEquals(listOf(31, 1, 31), repo.deliveries.map { it.inputs.size })
        assertEquals(listOf(32, 31), repo.turns.map { it.first.size })
        assertEquals(63, repo.deliveries.flatMap { it.inputs }.map { it.requestId }.distinct().size)
    }
    @Test fun continuedKeystrokesDoNotResetTheAbsoluteTypingDeadline() = model { vm, repo ->
        send(vm, "先发出的原话"); runCurrent()
        vm.draft("继续写")
        advanceTimeBy(10_000); vm.draft("还在慢慢继续写")
        advanceTimeBy(4_999); runCurrent(); assertTrue(repo.deliveries.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(1, repo.deliveries.size)
        assertEquals("还在慢慢继续写", vm.state.value.draft)
        assertEquals(1, vm.state.value.messages.count { it.role == "assistant" })
    }
    @Test fun refreshingWhileAContinuationIsPendingCannotRevealAnIntermediateAnswer() = model { vm, repo ->
        repo.replyGate = CompletableDeferred()
        send(vm, "先听我说"); advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        send(vm, "我还没说完")
        repo.replyGate!!.complete(Unit); runCurrent()
        assertEquals(0, vm.state.value.messages.count { it.role == "assistant" })
        vm.refresh(); runCurrent()
        assertEquals(0, vm.state.value.messages.count { it.role == "assistant" })
        advanceUntilIdle()
        assertEquals(listOf("user", "user", "assistant", "assistant"), vm.state.value.messages.map { it.role })
    }
    @Test fun actuallyTypingTheContinuationKeepsTheTurnOpenBeyondTheOldThreeSecondLimit() = model { vm, repo ->
        send(vm, "我想说两句"); runCurrent()
        advanceTimeBy(900); vm.draft("我还在写第二段")
        advanceTimeBy(5_000); runCurrent()
        assertEquals("Do not interrupt an actively composed continuation", 0, repo.deliveries.size)
        vm.send(); advanceUntilIdle()
        assertEquals(listOf(listOf("我想说两句", "我还在写第二段")), repo.deliveries.map { d -> d.inputs.map { it.content } })
    }
    @Test fun followupQueuedDuringGenerationKeepsEveryUserOriginalBeforeBothReplySegments() = model { vm, repo ->
        repo.replyGate = CompletableDeferred()
        send(vm, "第一段"); advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        send(vm, "补充第二段"); advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        repo.replyGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("user", "user", "assistant", "assistant"), vm.state.value.messages.map { it.role })
        assertEquals("No HTTP cancellation or repost", 2, repo.deliveries.size)
        assertFalse(repo.cancelled)
        assertEquals(listOf("第一段", "补充第二段"), vm.state.value.messages.filter { it.role == "user" }.map { it.content })
    }
    @Test fun twoNearbyOriginalsWaitForQuietThenReceiveOneSharedAnswer() = model { vm, repo ->
        send(vm, "下班啦"); runCurrent()
        assertTrue("The first text must leave time for a continuation", repo.deliveries.isEmpty())
        advanceTimeBy(400); send(vm, "其实先不聊工作，想吃面"); runCurrent()
        vm.draft("还没发的第三句")
        advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS - 1); runCurrent(); assertTrue(repo.deliveries.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertTrue("An active continuation is still being composed", repo.deliveries.isEmpty())
        advanceTimeBy(15_000 - OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        assertEquals(listOf(listOf("下班啦", "其实先不聊工作，想吃面")), repo.deliveries.map { it.inputs.map(QueuedTextInput::content) })
        assertEquals("batch", repo.deliveries.single().kind)
        assertEquals(listOf("下班啦", "其实先不聊工作，想吃面"), vm.state.value.messages.filter { it.role == "user" }.map { it.content })
        assertEquals(1, vm.state.value.messages.count { it.role == "assistant" })
        assertEquals("还没发的第三句", vm.state.value.draft)
    }

    @Test fun continuedTypingCannotPostponeTheFirstWindowBeyondThreeSeconds() = model { vm, repo ->
        repeat(4) { index ->
            if (index > 0) advanceTimeBy(600)
            send(vm, "接着说$index"); runCurrent()
            assertTrue(repo.deliveries.isEmpty())
        }
        advanceTimeBy(OutgoingQueueRules.TEXT_MAX_WAIT_MILLIS - 1_800 - 1); runCurrent(); assertTrue(repo.deliveries.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals((0..3).map { "接着说$it" }, repo.deliveries.single().inputs.map { it.content })
    }

    @Test fun aOneAndAHalfSecondGapStillBecomesOneTurn() = model { vm, repo ->
        send(vm, "第一段"); runCurrent()
        advanceTimeBy(1_500); send(vm, "第二段"); runCurrent()
        assertEquals("The first turn must still be open at 1.5 s", 0, repo.deliveries.size)
        advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        assertEquals(listOf(listOf("第一段", "第二段")), repo.deliveries.map { delivery -> delivery.inputs.map { it.content } })
        assertEquals("batch", repo.deliveries.single().kind)
    }

    @Test fun alreadyStartedHttpIsNeverCancelledOrRepostedWhenMoreTextArrives() = model { vm, repo ->
        repo.replyGate = CompletableDeferred()
        send(vm, "这一轮先发"); advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        val originalId = repo.deliveries.single().inputs.single().requestId
        send(vm, "再补一句"); advanceTimeBy(300); send(vm, "还有一句"); advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        assertEquals(1, repo.deliveries.size)
        assertFalse(repo.cancelled)
        repo.replyGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("single", "batch"), repo.deliveries.map { it.kind })
        assertEquals(listOf("再补一句", "还有一句"), repo.deliveries.last().inputs.map { it.content })
        assertEquals(1, repo.deliveries.flatMap { it.inputs }.count { it.requestId == originalId })
        assertEquals(1, repo.peakActive)
    }

    @Test fun photosAndRecordedVoiceCloseTheTextWindowWithoutJoiningIt() = model { vm, repo ->
        send(vm, "文字一"); send(vm, "文字二")
        val image = image('a'); vm.draftImage(image); send(vm, "图片说明")
        send(vm, "图片后的文字")
        vm.sendVoiceClip("独立语音", "f".repeat(32))
        advanceUntilIdle()
        assertEquals(listOf("batch", "photo", "single", "voice"), repo.deliveries.map { it.kind })
        assertEquals(listOf("文字一", "文字二"), repo.deliveries.first().inputs.map { it.content })
        assertEquals(image.attachment, repo.deliveries[1].image)
        assertEquals("f".repeat(32), repo.deliveries.last().voiceId)
        assertEquals(1, repo.peakActive)
    }

    @Test fun separateQuietWindowsStaySeparateWhileAnEarlierPhotoHttpIsPending() = model { vm, repo ->
        repo.replyGate = CompletableDeferred()
        vm.draftImage(image('a')); send(vm, "先发图片"); runCurrent()
        send(vm, "第一段文字"); advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        send(vm, "隔了一会儿的新话"); advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
        assertEquals(1, repo.deliveries.size)
        repo.replyGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("photo", "single", "single"), repo.deliveries.map { it.kind })
        assertEquals(3, repo.rows.count { it.role == "assistant" })
    }

    @Test fun anUnsubmittedBatchRetryKeepsTheSameMembersAndIdsAndTheNewComposer() = model { vm, repo ->
        repo.beforeFailure = ApiFailure(503, "private payload")
        send(vm, "原话一"); send(vm, "原话二"); advanceUntilIdle()
        val originals = vm.state.value.pendingOutgoing.toList()
        assertEquals(2, originals.size)
        assertTrue(originals.all { it.phase == OutgoingPhase.FAILED && it.submittedMessageId == null })
        send(vm, "失败后另发的一句"); vm.draft("正在写的新草稿")
        vm.retryOutgoing(originals.last().id); advanceUntilIdle()
        assertEquals(listOf("batch", "batch", "single"), repo.deliveries.map { it.kind })
        assertEquals(originals.map { it.id }, repo.deliveries[0].inputs.map { it.requestId })
        assertEquals(repo.deliveries[0].inputs, repo.deliveries[1].inputs)
        assertEquals("正在写的新草稿", vm.state.value.draft)
        assertTrue(vm.state.value.pendingOutgoing.isEmpty())
    }

    @Test fun submittedBatchFailurePausesEveryMemberAndCannotPostAnyOriginalAgain() = model { vm, repo ->
        repo.afterFailure = ApiFailure(503, "private provider payload")
        send(vm, "已经提交一"); send(vm, "已经提交二"); advanceUntilIdle()
        val failed = vm.state.value.pendingOutgoing.toList()
        assertEquals(2, failed.size)
        assertTrue(failed.all { it.phase == OutgoingPhase.SUBMITTED_NO_REPLY && it.submittedMessageId != null })
        failed.forEach { vm.retryOutgoing(it.id) }; advanceUntilIdle()
        assertEquals(1, repo.deliveries.size)
        assertEquals(2, repo.rows.count { it.role == "user" })
        send(vm, "接下来的新话"); advanceUntilIdle()
        assertEquals(1, repo.deliveries.size)
        failed.forEach { vm.discardOutgoing(it.id) }; advanceUntilIdle()
        assertEquals(2, repo.deliveries.size)
        assertFalse(vm.state.value.error.orEmpty().contains("private"))
    }

    @Test fun invalidatingAnOpenWindowRetainsOriginalsWithoutSendingThemToTheNewTarget() = model { vm, repo ->
        send(vm, "旧连接原话一"); send(vm, "旧连接原话二"); runCurrent()
        vm.serverUrl("https://other.synthetic.invalid"); advanceUntilIdle()
        assertTrue(repo.deliveries.isEmpty())
        assertEquals(listOf("旧连接原话一", "旧连接原话二"), vm.state.value.pendingOutgoing.map { it.content })
        assertTrue(vm.state.value.pendingOutgoing.all { it.phase == OutgoingPhase.FAILED })
    }

    @Test fun oldBatchSubmissionCallbacksCannotClaimANewRetryAttempt() = model { vm, repo ->
        repo.beforeFailure = ApiFailure(503, "temporary")
        send(vm, "第一原话"); send(vm, "第二原话"); advanceUntilIdle()
        val originals = vm.state.value.pendingOutgoing.toList()
        repo.commitGate = CompletableDeferred()
        vm.retryOutgoing(originals.first().id); runCurrent()
        assertEquals(2, repo.deliveries.size)
        val oldCallback = repo.callbacks.first()
        originals.forEach { item -> oldCallback(item.id, Message("late-${item.id}", "user", item.content, emptyList(), "now")) }
        assertTrue(vm.state.value.messages.isEmpty())
        assertTrue(vm.state.value.pendingOutgoing.all { it.submittedMessageId == null })
        repo.commitGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(2, vm.state.value.messages.count { it.role == "user" })
        assertEquals(1, vm.state.value.messages.count { it.role == "assistant" })
    }

    @Test fun queueCapacityStillKeepsThirtyTwoIndependentOriginalsAndRejectsOnlyTheNextDraft() = model { vm, repo ->
        repeat(32) { send(vm, "原话$it") }
        send(vm, "尚未接受的草稿")
        assertEquals("尚未接受的草稿", vm.state.value.draft)
        assertNotNull(vm.state.value.error)
        advanceUntilIdle()
        assertEquals("Thirty-two nearby originals must share one request", 1, repo.deliveries.size)
        assertEquals(32, repo.deliveries.single().inputs.size)
        assertEquals((0 until 32).map { "原话$it" }, repo.rows.filter { it.role == "user" }.map { it.content })
        assertEquals(1, repo.rows.count { it.role == "assistant" })
        assertEquals("尚未接受的草稿", vm.state.value.draft)
    }

    @Test fun longOriginalsCloseTheBurstBeforeTheBatchContextBudgetIsExceeded() = model { vm, repo ->
        send(vm, "甲".repeat(16_000)); send(vm, "乙".repeat(16_000)); send(vm, "另一个完整原文")
        advanceUntilIdle()
        assertEquals(listOf(2, 1), repo.deliveries.map { it.inputs.size })
        assertTrue(repo.deliveries.all { it.inputs.sumOf { input -> input.content.length } <= 32_000 })
        assertEquals(3, repo.rows.count { it.role == "user" })
    }

    @Test fun defaultBatchContractRejectsMultipleInputsBeforeAnyLegacySend() = model { _, repo ->
        repo.supportBatch = false
        val inputs = listOf(QueuedTextInput("a".repeat(32), "第一句"), QueuedTextInput("b".repeat(32), "第二句"))
        val result = runCatching { repo.sendQueuedTextBatch(repo.chat.id, inputs) { _, _ -> fail("No partial submission") } }
        assertTrue(result.isFailure)
        assertTrue(repo.deliveries.isEmpty())
    }

    private fun send(vm: ChatViewModel, text: String) { vm.draft(text); vm.send() }
    private fun image(char: Char): ChatImageDraft = ChatImageDraft(MediaAttachment(char.toString().repeat(32), "image",
        MediaRules.path(char.toString().repeat(32)), "image/png", "b".repeat(64), null, 0, "合成图"))
    private fun model(test: suspend TestScope.(ChatViewModel, BatchRepository) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore(); val repo = BatchRepository()
        try {
            val vm = ChatViewModel(repo, DocumentReader(Application()))
            models.put("continuous", vm); advanceUntilIdle(); test(vm, repo)
        } finally {
            repo.replyGate?.complete(Unit); repo.commitGate?.complete(Unit)
            models.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }
}

private data class Delivery(val kind: String, val inputs: List<QueuedTextInput>, val image: MediaAttachment? = null, val voiceId: String? = null)
private class BatchRepository : ChatRepository {
    var historyCalls = 0
    val turns = mutableListOf<Pair<List<String>, List<String>>>()
    override suspend fun recordReplyTurn(id: String, userIds: List<String>, assistantIds: List<String>) {
        turns += userIds.toList() to assistantIds.toList()
    }
    val binding = CompanionBinding("deepseek", "synthetic-model")
    var connection: SavedConnection? = SavedConnection("https://synthetic.invalid", binding.providerId, "synthetic-key", binding.model)
    val chat = Conversation("synthetic-chat", "与你", binding.model, true, "2026-10-16", "2026-10-16")
    val deliveries = mutableListOf<Delivery>(); val rows = mutableListOf<Message>()
    val callbacks = mutableListOf<(String, Message) -> Unit>()
    var beforeFailure: Exception? = null; var afterFailure: Exception? = null
    var replyGate: CompletableDeferred<Unit>? = null; var commitGate: CompletableDeferred<Unit>? = null
    var supportBatch = true; var cancelled = false
    private var active = 0; var peakActive = 0
    override suspend fun sendQueuedMessage(id: String, content: String, requestId: String,
        image: MediaAttachment?, voiceDraftId: String?, onSubmitted: (Message) -> Unit): Pair<Message, Message> {
        val kind = when { image != null -> "photo"; voiceDraftId != null -> "voice"; else -> "single" }
        val result = answer(Delivery(kind, listOf(QueuedTextInput(requestId, content)), image, voiceDraftId)) { _, message -> onSubmitted(message) }
        return result.submitted.single().message to result.assistant
    }
    override suspend fun sendQueuedTextBatch(id: String, inputs: List<QueuedTextInput>,
        onSubmitted: (String, Message) -> Unit): QueuedTextBatchResult {
        if (!supportBatch) return super<ChatRepository>.sendQueuedTextBatch(id, inputs, onSubmitted)
        return answer(Delivery("batch", QueuedTextBatchRules.check(inputs)), onSubmitted)
    }
    private suspend fun answer(delivery: Delivery, submitted: (String, Message) -> Unit): QueuedTextBatchResult {
        active++; peakActive = maxOf(peakActive, active); deliveries += delivery; callbacks += submitted
        try {
            commitGate?.await()
            beforeFailure?.let { beforeFailure = null; throw it }
            val users = delivery.inputs.map { input -> QueuedTextSubmission(input.requestId,
                Message("user-${input.requestId}", "user", input.content, emptyList(), "2026-10-16T12:34:56+08:00", listOfNotNull(delivery.image),
                    delivery.voiceId?.let { "voice_user:$it" } ?: "reply")) }
            rows += users.map { it.message }; users.forEach { submitted(it.requestId, it.message) }
            replyGate?.await()
            afterFailure?.let { afterFailure = null; throw it }
            val answer = Message("assistant-${deliveries.size}", "assistant", "一起回应。\n也可以分段。", emptyList(), "2026-10-16T12:34:57+08:00")
            rows += answer
            return QueuedTextBatchResult(users, answer)
        } catch (failure: CancellationException) { cancelled = true; throw failure }
        finally { active-- }
    }
    override suspend fun savedBinding() = binding
    override suspend fun savedConnection() = connection
    override suspend fun providers(serverUrl: String) = listOf(Provider(binding.providerId, "合成", binding.model, listOf(binding.model), "", ""))
    override suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String, profile: CompanionProfile?) { connection = SavedConnection(serverUrl, providerId, apiKey, model) }
    override suspend fun logout() { connection = null }
    override suspend fun forgetLocalConnection() { connection = null }
    override suspend fun conversations() = listOf(chat)
    override suspend fun createConversation(model: String, useRag: Boolean, profile: CompanionProfile?) = error("Reuse chat")
    override suspend fun changeProfile(id: String, profile: CompanionProfile?) = chat.copy(profile = profile)
    override suspend fun changeRag(id: String, enabled: Boolean) = chat.copy(useRag = enabled)
    override suspend fun deleteConversation(id: String) = error("Not used")
    override suspend fun messages(id: String) = rows.toList()
    override suspend fun history(id: String, before: String?): MessagePage {
        historyCalls++
        return MessagePage(rows.toList().takeLast(100), false, null)
    }
    override suspend fun memory(id: String, before: String?): MemoryPage = error("Not used")
    override suspend fun messageMemory(id: String, messageId: String): MemoryEntry = error("Not used")
    override suspend fun changeMemory(id: String, messageId: String, change: MemoryChange): MemoryEntry = error("Not used")
    override suspend fun sendMessage(id: String, content: String): Pair<Message, Message> = error("Stable queue ID required")
    override suspend fun discardVoiceDraft(id: String) = Unit
    override suspend fun documents() = emptyList<Document>()
    override suspend fun upload(file: UploadedFile): Document = error("Not used")
    override suspend fun deleteDocument(id: String) = Unit
}
