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
class RepoReviewQueueTest {
    @Test fun discardInProgressCannotBeRetriedIntoANewProviderRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val holders = ViewModelStore()
        val repo = ReviewQueueRepository().apply {
            firstAnswer.complete(Unit); beforeCommitFailure = IllegalStateException("synthetic offline")
        }
        try {
            val model = ChatViewModel(repo, DocumentReader(Application()))
            holders.put("model", model); advanceUntilIdle()
            model.draft("准备放弃的待发原话"); model.send(); advanceUntilIdle()
            val pending = model.state.value.pendingOutgoing.single()
            assertEquals(OutgoingPhase.FAILED, pending.phase)
            val gate = CompletableDeferred<Unit>(); repo.deleteGate = gate
            model.discardOutgoing(pending.id); runCurrent()
            model.retryOutgoing(pending.id); runCurrent()
            assertEquals("Discard owns this item until its durable removal completes", 1, repo.contents.size)
            gate.complete(Unit); advanceUntilIdle()
            assertTrue(model.state.value.pendingOutgoing.isEmpty())
            assertTrue(repo.loadOutgoingDrafts().isEmpty())
        } finally { holders.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun storageFailureKeepsComposerAndMakesNoProviderRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val holders = ViewModelStore()
        val repo = ReviewQueueRepository().apply { saveFailure = IllegalStateException("synthetic disk failure") }
        try {
            val model = ChatViewModel(repo, DocumentReader(Application()))
            holders.put("model", model); advanceUntilIdle()
            model.draft("尚未持久保存的原话"); model.send(); advanceUntilIdle()
            assertEquals("尚未持久保存的原话", model.state.value.draft)
            assertTrue(model.state.value.pendingOutgoing.isEmpty())
            assertTrue(repo.contents.isEmpty())
            assertNotNull(model.state.value.error)
        } finally { holders.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun slowFollowingSaveCannotSplitAnAcceptedContinuousBatch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val holders = ViewModelStore()
        val repo = ReviewQueueRepository()
        try {
            val model = ChatViewModel(repo, DocumentReader(Application()))
            holders.put("model", model); advanceUntilIdle()
            model.draft("第一句"); model.send(); runCurrent()
            val gate = CompletableDeferred<Unit>(); repo.saveGate = gate
            model.draft("连着的第二句"); model.send(); runCurrent()
            advanceTimeBy(OutgoingQueueRules.TEXT_MAX_WAIT_MILLIS + 1); runCurrent()
            assertTrue("The provider must not start while a following accepted input is still being persisted", repo.contents.isEmpty())
            model.draft("还在编辑的第三句")
            gate.complete(Unit); runCurrent()
            assertEquals("还在编辑的第三句", model.state.value.draft)
            assertEquals(listOf("第一句", "连着的第二句"), model.state.value.pendingOutgoing.map { it.content })
            model.draft(""); advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS + 1); runCurrent()
            assertEquals(listOf("第一句", "连着的第二句"), repo.contents)
            assertEquals(1, repo.batchCalls)
        } finally { repo.firstAnswer.complete(Unit); holders.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun disconnectDuringSaveKeepsOriginalForRecreationWithoutSending() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val holders = ViewModelStore()
        val repo = ReviewQueueRepository()
        try {
            val model = ChatViewModel(repo, DocumentReader(Application()))
            holders.put("model", model); advanceUntilIdle()
            val gate = CompletableDeferred<Unit>(); repo.saveGate = gate
            model.draft("切换连接时不能丢的原话"); model.send(); runCurrent()
            model.disconnect(); runCurrent()
            gate.complete(Unit); advanceUntilIdle()
            assertEquals("切换连接时不能丢的原话", repo.loadOutgoingDrafts().single().content)
            assertTrue(repo.contents.isEmpty())
        } finally { holders.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun acceptedUnsubmittedMessagesSurviveViewModelRecreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val holders = ViewModelStore()
        val repo = ReviewQueueRepository()
        try {
            val first = ChatViewModel(repo, DocumentReader(Application()))
            holders.put("first", first)
            advanceUntilIdle()
            first.draft("first message"); first.send()
            advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent()
            first.draft("accepted queued message"); first.send(); runCurrent()
            assertTrue(first.state.value.pendingOutgoing.any { it.content == "accepted queued message" })
            assertEquals("", first.state.value.draft)
            assertFalse(repo.rows.any { it.content == "accepted queued message" })
            holders.clear(); runCurrent()
            val restored = ChatViewModel(repo, DocumentReader(Application()))
            holders.put("restored", restored); advanceUntilIdle()
            assertTrue("The accepted second message disappeared from both the durable history and the restored queue",
                restored.state.value.pendingOutgoing.any { it.content == "accepted queued message" } ||
                    restored.state.value.messages.any { it.content == "accepted queued message" })
        } finally {
            repo.firstAnswer.complete(Unit); holders.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }
}
private class ReviewQueueRepository : ChatRepository {
    private var outbox = emptyList<OutgoingDraft>()
    var saveFailure: Exception? = null
    var saveGate: CompletableDeferred<Unit>? = null
    var deleteGate: CompletableDeferred<Unit>? = null
    var batchCalls = 0
    override suspend fun saveOutgoingDraft(draft: OutgoingDraft) {
        saveFailure?.let { throw it }; saveGate?.await()
        outbox = outbox + draft
    }
    override suspend fun loadOutgoingDrafts() = outbox.map { it.copy(phase = OutgoingPhase.FAILED) }
    override suspend fun deleteOutgoingDraft(requestId: String) {
        deleteGate?.await(); outbox = outbox.filterNot { it.id == requestId }
    }
    val binding = CompanionBinding("deepseek", "synthetic-model")
    var connection: SavedConnection? = SavedConnection("https://synthetic.invalid", binding.providerId, "synthetic-key", binding.model)
    val chat = Conversation("synthetic-chat", "与你", binding.model, true, "2026-10-16", "2026-10-16")
    val firstAnswer = CompletableDeferred<Unit>()
    val contents = mutableListOf<String>(); val photos = mutableListOf<MediaAttachment>(); val rows = mutableListOf<Message>()
    val voiceIds = mutableListOf<String>(); val cleanedVoices = mutableListOf<String>()
    var beforeCommitFailure: Exception? = null; var afterCommitFailure: Exception? = null
    var secondBeforeCommit: CompletableDeferred<Unit>? = null
    val submittedCallbacks = mutableListOf<(Message) -> Unit>()
    val requestIds = mutableListOf<String>()
    private var active = 0; var peakActive = 0
    override suspend fun savedBinding() = binding
    override suspend fun savedConnection() = connection
    override suspend fun providers(serverUrl: String) = listOf(Provider(binding.providerId, "合成", binding.model, listOf(binding.model), "", ""))
    override suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String, profile: CompanionProfile?) { connection = SavedConnection(serverUrl, providerId, apiKey, model) }
    override suspend fun logout() { connection = null }
    override suspend fun forgetLocalConnection() { connection = null }
    override suspend fun conversations() = listOf(chat)
    override suspend fun createConversation(model: String, useRag: Boolean, profile: CompanionProfile?) = error("must reuse chat")
    override suspend fun changeProfile(id: String, profile: CompanionProfile?) = chat.copy(profile = profile)
    override suspend fun changeRag(id: String, enabled: Boolean) = chat.copy(useRag = enabled)
    override suspend fun deleteConversation(id: String) = error("not supported")
    override suspend fun messages(id: String) = rows.toList()
    override suspend fun history(id: String, before: String?) = MessagePage(rows.toList().takeLast(100), false, null)
    override suspend fun memory(id: String, before: String?): MemoryPage = error("not used")
    override suspend fun messageMemory(id: String, messageId: String): MemoryEntry = error("not used")
    override suspend fun changeMemory(id: String, messageId: String, change: MemoryChange): MemoryEntry = error("not used")
    override suspend fun sendMessage(id: String, content: String): Pair<Message, Message> = answer(content, null)
    override suspend fun sendMessage(id: String, content: String, onSubmitted: (Message) -> Unit) = answer(content, null, submitted = onSubmitted)
    override suspend fun sendPhotoMessage(id: String, content: String, attachment: MediaAttachment): Pair<Message, Message> = answer(content, attachment)
    override suspend fun sendPhotoMessage(id: String, content: String, attachment: MediaAttachment, onSubmitted: (Message) -> Unit) = answer(content, attachment, submitted = onSubmitted)
    override suspend fun sendVoiceMessage(id: String, content: String, voiceDraftId: String, onSubmitted: (Message) -> Unit) =
        answer(content, null, voiceDraftId, onSubmitted)
    override suspend fun sendQueuedMessage(id: String, content: String, requestId: String,
        image: MediaAttachment?, voiceDraftId: String?, onSubmitted: (Message) -> Unit): Pair<Message, Message> {
        requestIds += requestId
        return super<ChatRepository>.sendQueuedMessage(id, content, requestId, image, voiceDraftId, onSubmitted)
    }
    override suspend fun sendQueuedTextBatch(id: String, inputs: List<QueuedTextInput>,
        onSubmitted: (String, Message) -> Unit): QueuedTextBatchResult {
        batchCalls++
        val checked = QueuedTextBatchRules.check(inputs)
        active++; peakActive = maxOf(peakActive, active)
        val firstNumber = contents.size + 1
        requestIds += checked.map { it.requestId }; contents += checked.map { it.content }
        try {
            beforeCommitFailure?.let { failure ->
                if (firstNumber == 1) firstAnswer.await()
                beforeCommitFailure = null; throw failure
            }
            val users = checked.mapIndexed { index, input -> QueuedTextSubmission(input.requestId,
                Message("u${firstNumber + index}", "user", input.content, emptyList(), "2026-10-16T12:34:56+08:00")) }
            rows += users.map { it.message }
            users.forEach { row ->
                submittedCallbacks += { message -> onSubmitted(row.requestId, message) }
                onSubmitted(row.requestId, row.message)
            }
            if (firstNumber == 1) firstAnswer.await()
            afterCommitFailure?.let { failure -> afterCommitFailure = null; throw failure }
            val assistant = Message("a${contents.size}", "assistant", "好啊。", emptyList(), "2026-10-16T12:34:57+08:00")
            rows += assistant
            return QueuedTextBatchResult(users, assistant)
        } finally { active-- }
    }
    override suspend fun discardVoiceDraft(id: String) { cleanedVoices += id }
    private suspend fun answer(content: String, image: MediaAttachment?, voiceId: String? = null, submitted: (Message) -> Unit = {}): Pair<Message, Message> {
        active++; peakActive = maxOf(peakActive, active)
        try {
            contents += content; if (image != null) photos += image
            val n = contents.size
            submittedCallbacks += submitted
            if (n == 2) secondBeforeCommit?.await()
            beforeCommitFailure?.let { failure ->
                if (n == 1) firstAnswer.await()
                beforeCommitFailure = null; throw failure
            }
            if (voiceId != null) voiceIds += voiceId
            val user = Message("u$n", "user", content, emptyList(), "2026-10-16T12:34:56+08:00", listOfNotNull(image),
                if (voiceId == null) "reply" else "voice_user:$voiceId")
            rows += user; submitted(user)
            if (n == 1) firstAnswer.await()
            afterCommitFailure?.let { failure -> afterCommitFailure = null; throw failure }
            val assistant = Message("a$n", "assistant", "好啊。", emptyList(), "2026-10-16T12:34:57+08:00")
            rows += assistant
            return user to assistant
        } finally { active-- }
    }
    override suspend fun documents() = emptyList<Document>()
    override suspend fun upload(file: UploadedFile): Document = error("not used")
    override suspend fun deleteDocument(id: String) = Unit
}
