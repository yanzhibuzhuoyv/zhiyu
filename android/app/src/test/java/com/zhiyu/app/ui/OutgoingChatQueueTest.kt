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
class OutgoingChatQueueTest {
    @Test fun retryReusesTheDurableRequestIdWhileTheNextDraftHasItsOwnId() = model { vm, repo ->
        repo.beforeCommitFailure = ApiFailure(503, "temporary")
        vm.draft("第一条原话"); vm.send(); startTextTurn()
        vm.draft("第二条原话"); vm.send()
        val ids = vm.state.value.pendingOutgoing.map { it.id }
        repo.firstAnswer.complete(Unit); advanceUntilIdle()
        vm.retryOutgoing(ids.first()); advanceUntilIdle()
        assertEquals(listOf(ids[0], ids[0], ids[1]), repo.requestIds)
        assertEquals(2, ids.toSet().size)
        assertTrue(repo.requestIds.all { Regex("[a-f0-9]{32}").matches(it) })
    }

    @Test fun aLateSubmissionCallbackFromAFailedAttemptCannotContaminateTheRetry() = model { vm, repo ->
        repo.beforeCommitFailure = ApiFailure(503, "temporary")
        vm.draft("只有这一条原话"); vm.send(); startTextTurn()
        repo.firstAnswer.complete(Unit); advanceUntilIdle()
        val id = vm.state.value.pendingOutgoing.single().id
        val retryGate = CompletableDeferred<Unit>(); repo.secondBeforeCommit = retryGate
        try {
            vm.retryOutgoing(id); runCurrent()
            assertEquals(2, repo.contents.size)
            repo.submittedCallbacks.first()(Message("late-old-attempt", "user", "只有这一条原话", emptyList(), "now"))
            assertTrue("An abandoned attempt cannot claim the retry's submission", vm.state.value.messages.isEmpty())
            assertNull(vm.state.value.pendingOutgoing.single().submittedMessageId)
            retryGate.complete(Unit); advanceUntilIdle()
            assertEquals(listOf("u2", "a2"), vm.state.value.messages.map { it.id })
        } finally { retryGate.complete(Unit) }
    }

    @Test fun failedUnsubmittedSnapshotCanBeRetriedWithoutReplacingANewerComposer() = model { vm, repo ->
        repo.beforeCommitFailure = ApiFailure(503, "synthetic private provider body")
        vm.draft("失败的第一句"); vm.send(); startTextTurn()
        vm.draft("队列第二句"); vm.send()
        vm.draft("新的未发送草稿")
        repo.firstAnswer.complete(Unit); advanceUntilIdle()
        val failed = vm.state.value.pendingOutgoing.first()
        assertEquals(OutgoingPhase.FAILED, failed.phase)
        assertNull(failed.submittedMessageId)
        assertEquals("失败的第一句", failed.content)
        assertEquals("新的未发送草稿", vm.state.value.draft)
        assertEquals(listOf("失败的第一句"), repo.contents)
        assertFalse(vm.state.value.error.orEmpty().contains("private"))
        vm.retryOutgoing(failed.id); advanceUntilIdle()
        assertEquals(listOf("失败的第一句", "失败的第一句", "队列第二句"), repo.contents)
        assertEquals("新的未发送草稿", vm.state.value.draft)
        assertTrue(vm.state.value.pendingOutgoing.isEmpty())
    }

    @Test fun committedOriginalAppearsBeforeTheAnswerAndCannotBeRepostedAfterReplyFailure() = model { vm, repo ->
        repo.afterCommitFailure = ApiFailure(503, "private provider failure")
        vm.draft("这句话已经送出"); vm.send(); startTextTurn()
        assertEquals(listOf("这句话已经送出"), vm.state.value.messages.map { it.content })
        assertNotNull(vm.state.value.pendingOutgoing.single().submittedMessageId)
        vm.draft("下一句"); vm.send()
        repo.firstAnswer.complete(Unit); advanceUntilIdle()
        val failed = vm.state.value.pendingOutgoing.first()
        assertEquals(OutgoingPhase.SUBMITTED_NO_REPLY, failed.phase)
        vm.retryOutgoing(failed.id); advanceUntilIdle()
        assertEquals(listOf("这句话已经送出"), repo.contents)
        vm.discardOutgoing(failed.id); advanceUntilIdle()
        assertEquals(listOf("这句话已经送出", "下一句"), repo.contents)
        assertEquals(1, repo.rows.count { it.role == "user" && it.content == "这句话已经送出" })
    }

    @Test fun expiredKeyPausesQueuedMessagesUntilReconnectAndExplicitRetry() = model { vm, repo ->
        repo.beforeCommitFailure = ApiFailure(401, "secret body", "session_expired")
        vm.draft("第一句"); vm.send(); startTextTurn()
        vm.draft("第二句"); vm.send()
        repo.firstAnswer.complete(Unit); advanceUntilIdle()
        assertFalse(vm.state.value.connected)
        assertEquals(2, vm.state.value.pendingOutgoing.size)
        val failedId = vm.state.value.pendingOutgoing.first().id
        vm.retryOutgoing(failedId); advanceUntilIdle()
        assertEquals(1, repo.contents.size)
        vm.connect(); advanceUntilIdle()
        assertEquals("Reconnection must not silently retry an uncertain POST", 1, repo.contents.size)
        vm.retryOutgoing(failedId); advanceUntilIdle()
        assertEquals(listOf("第一句", "第一句", "第二句"), repo.contents)
    }

    @Test fun voiceQueueUsesTheRealClipIdAndDoesNotConsumeTheKeyboardDraftOrPhoto() = model { vm, repo ->
        val picture = image('a'); val voiceId = "f".repeat(32)
        vm.draft("键盘还没说完"); vm.draftImage(picture)
        vm.sendVoiceClip("这是语音的原话", voiceId); runCurrent()
        assertEquals("voice_user:$voiceId", vm.state.value.messages.single().origin)
        assertEquals("键盘还没说完", vm.state.value.draft)
        assertEquals(picture, vm.state.value.draftImage)
        repo.firstAnswer.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(voiceId), repo.voiceIds)
        assertEquals(listOf(voiceId), repo.cleanedVoices)
        assertTrue(repo.photos.isEmpty())
    }

    @Test fun changingConnectionLeavesOldFailedSnapshotsButCannotSendThemToTheNewTarget() = model { vm, repo ->
        repo.beforeCommitFailure = ApiFailure(503, "temporary")
        vm.draft("旧连接的话"); vm.send(); startTextTurn()
        repo.firstAnswer.complete(Unit); advanceUntilIdle()
        val failedId = vm.state.value.pendingOutgoing.single().id
        vm.serverUrl("https://different.synthetic.invalid"); advanceUntilIdle()
        assertEquals("旧连接的话", vm.state.value.pendingOutgoing.single().content)
        vm.retryOutgoing(failedId); advanceUntilIdle()
        assertEquals(listOf("旧连接的话"), repo.contents)
        assertEquals(OutgoingPhase.FAILED, vm.state.value.pendingOutgoing.single().phase)
    }

    @Test fun anotherMessageCanBeAcceptedWhileTheFirstAnswerIsPendingAndSendsInOrder() = model { vm, repo ->
        vm.draft("第一句"); vm.send(); startTextTurn()
        vm.draft("还有第二句"); vm.send(); runCurrent()
        assertEquals(listOf("第一句"), repo.contents)
        assertEquals("", vm.state.value.draft)
        repo.firstAnswer.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("第一句", "还有第二句"), repo.contents)
        assertEquals(1, repo.peakActive)
        assertEquals(listOf("第一句", "还有第二句"), vm.state.value.messages.filter { it.role == "user" }.map { it.content })
    }

    @Test fun twoSynchronousSendClicksKeepTwoImmutableDraftSnapshots() = model { vm, repo ->
        vm.draft("先说这个"); vm.send()
        vm.draft("再说那个"); vm.send()
        vm.draft("正在写的第三句")
        repo.firstAnswer.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("先说这个", "再说那个"), repo.contents)
        assertEquals("正在写的第三句", vm.state.value.draft)
        assertEquals(1, repo.peakActive)
        assertEquals(2, repo.rows.count { it.role == "user" })
        assertEquals(1, repo.rows.count { it.role == "assistant" })
    }

    @Test fun queuedPhotosUseTheirOwnAttachmentRatherThanTheNewPickerSelection() = model { vm, repo ->
        val first = image('a'); val second = image('c'); val third = image('e')
        vm.draft("第一张"); vm.draftImage(first); vm.send(); runCurrent()
        vm.draft("第二张"); vm.draftImage(second); vm.send(); runCurrent()
        vm.draft("第三张还没发"); vm.draftImage(third)
        repo.firstAnswer.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(first.attachment.id, second.attachment.id), repo.photos.map { it.id })
        assertEquals(third, vm.state.value.draftImage)
        assertEquals("第三张还没发", vm.state.value.draft)
    }

    @Test fun aFullQueueRejectsTheNextDraftWithoutErasingItOrStartingParallelRequests() = model { vm, repo ->
        repeat(32) { index -> vm.draft("消息$index"); vm.send(); runCurrent() }
        vm.draft("还未入队"); vm.send(); runCurrent()
        assertEquals("还未入队", vm.state.value.draft)
        assertNotNull(vm.state.value.error)
        repo.firstAnswer.complete(Unit); advanceUntilIdle()
        assertEquals((0 until 32).map { "消息$it" }, repo.contents)
        assertEquals(1, repo.peakActive)
        assertEquals(32, repo.rows.count { it.role == "user" })
        assertEquals(1, repo.rows.count { it.role == "assistant" })
    }

    private fun TestScope.startTextTurn() { advanceTimeBy(OutgoingQueueRules.TEXT_QUIET_MILLIS); runCurrent() }

    private fun image(char: Char): ChatImageDraft {
        val id = char.toString().repeat(32)
        return ChatImageDraft(MediaAttachment(id, "image", MediaRules.path(id), "image/png", "b".repeat(64), null, 0, "合成图"))
    }

    private fun model(test: suspend TestScope.(ChatViewModel, QueueRepository) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); val repo = QueueRepository()
        try {
            val vm = ChatViewModel(repo, DocumentReader(Application()))
            store.put("queue", vm); advanceUntilIdle()
            test(vm, repo)
        } finally { repo.firstAnswer.complete(Unit); repo.secondBeforeCommit?.complete(Unit); store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}

private class QueueRepository : ChatRepository {
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
