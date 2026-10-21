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
