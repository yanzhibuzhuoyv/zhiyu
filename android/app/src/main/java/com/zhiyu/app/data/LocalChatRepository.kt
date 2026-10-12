package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.notifications.ProactiveForeground
import com.zhiyu.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.Instant

/** One device, one bound relationship. Only the selected company's official API receives a Key. */
class LocalChatRepository(
    context: Context,
    private val store: CredentialStore,
    private val provider: DirectProviderClient = DirectProviderClient(),
    private val careContext: () -> String = { "" },
    private val database: LocalChatDatabase = LocalChatDatabase(context),
    private val semanticEncoder: LocalSemanticEncoder = BgeLocalSemanticEncoder(context),
    private val daily: LocalDailyRepository? = if (database.isMainArchive) LocalDailyRepository(context) else null,
    private val dailyNow: () -> Instant = Instant::now,
) : ChatRepository {
    companion object {
        const val LOCAL_URL = "https://local.zhiyu.invalid"
        private val serial = Mutex()
        private val requestPattern = Regex("[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    }
    private val appContext = context.applicationContext
    private val media = LocalReplyMedia(appContext)
    private val calendar = CompanionCalendarStore(appContext)
    private val photos = ChatPhotoStore(appContext)
    private val photoHistory = LocalPhotoPromptHistory(photos)
    private val photoAnalyzer = ChatPhotoAnalyzer(OcrChatPhotoReader(), AgnesVisionClient(credentials = { AgnesCredentialStore(appContext).read() }))
    var lastCalendarNotice: String? = null
        private set
    var lastDailyNotice: String? = null
        private set
    private val semantic = LocalSemanticService(database,semanticEncoder)
    internal var lastRetrievalRoute: LocalSemanticRetrieval? = null
        private set
    private fun scheduleSemantic() {
        if (database.isMainArchive) {
            LocalSemanticScheduler.enqueue(appContext)
            LocalMemoryRecognitionScheduler.schedule(appContext)
        }
    }

    override suspend fun providers(serverUrl: String) = DirectProviderCatalog.providers()
    override suspend fun savedConnection(): SavedConnection? = withContext(Dispatchers.IO) { localConnection() }
    override suspend fun savedBinding(): CompanionBinding? = withContext(Dispatchers.IO) {
        localConnection()
        store.readBinding()
    }
    override suspend fun connectionNotice(): String? = withContext(Dispatchers.IO) {
        listOfNotNull(if (database.externalHistoryPending()) "已切换为手机本地模式。旧聊天尚未导入，原数据仍保留在原设备。" else null,
            lastDailyNotice).joinToString("\n").takeIf { it.isNotEmpty() }
    }
    suspend fun needsLegacyHistoryImport(): Boolean = withContext(Dispatchers.IO) { database.externalHistoryPending() }
    private fun localConnection(): SavedConnection? {
        val saved = store.read() ?: return null
        val binding = store.readBinding()
        check(binding == null || binding.providerId == saved.providerId && binding.model == saved.model) { "保存的 Key 与已绑定 AI 不一致" }
        if (binding == null && saved.session != null) store.writeBinding(CompanionBinding(saved.providerId, saved.model, profileConfigured = false))
        if (saved.serverUrl == LOCAL_URL && saved.session == null) return saved
        // A URL/token migration does not prove that any old computer-side history was transferred.
        database.markExternalHistoryPending()
        val updated = saved.copy(serverUrl = LOCAL_URL, session = null)
        return if (store.compareAndWrite(saved, updated)) updated else store.read()
    }
    override suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String, profile: CompanionProfile?) = serial.withLock {
        val key = apiKey.trim()
        val chosenModel = model.trim()
        val snapshot = withContext(Dispatchers.IO) { store.read() }
        val originalBinding = withContext(Dispatchers.IO) { store.readBinding() }
        require(originalBinding == null || originalBinding.providerId == providerId && originalBinding.model == chosenModel) { "已绑定的 AI 不能更换公司或模型" }
        val chosenProfile = CompanionRules.normalize(profile ?: originalBinding?.profile)
        CompanionRules.checkChange(originalBinding, chosenProfile)
        provider.validate(providerId, key, chosenModel)
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) {
            check(store.read() == snapshot && store.readBinding() == originalBinding) { "连接信息已经改变，请重新验证" }
            val binding = CompanionBinding(providerId, chosenModel, chosenProfile)
            val owner = owner(binding)
            val existing = database.findConversation(owner)
            if (existing?.profile?.mode == "preset") check(existing.profile == chosenProfile) { "原来的恋人预设不能更换" }
            if (existing?.profile?.mode == "custom") check(chosenProfile?.mode == "custom") { "这段恋人设定需继续自行调教" }
            val conversation = database.ensureConversation(owner, chosenModel, chosenProfile)
            if (existing != null && existing.profile != chosenProfile) database.changeProfile(owner, existing.id, chosenProfile)
            store.writeBinding(binding)
            check(store.compareAndWrite(snapshot, SavedConnection(LOCAL_URL, providerId, key, chosenModel))) { "Key 已被清除或更新，请重新验证" }
            scheduleSemantic()
            initializeDailyPlan(conversation.id)
        }
    }
    override suspend fun logout() = serial.withLock { withContext(Dispatchers.IO) {
        store.clear()
        if (database.isMainArchive) LocalMemoryRecognitionScheduler.cancel(appContext)
    } }
    override suspend fun forgetLocalConnection() = logout()
    private fun binding(): CompanionBinding = store.readBinding() ?: throw ApiFailure(401, "请先验证 API Key 并选定恋人")
    private fun connection(): SavedConnection {
        val saved = localConnection() ?: throw ApiFailure(401, "请重新输入 API Key，原恋人和本机聊天仍保留")
        val binding = binding()
        check(saved.providerId == binding.providerId && saved.model == binding.model) { "保存的连接与原恋人不一致" }
        return saved
    }
    private fun owner(binding: CompanionBinding) = "companion-" + LocalMemoryCodec.sha((binding.providerId + "\u0000" + binding.model).toByteArray(Charsets.UTF_8))
    private fun currentOwner() = owner(binding())
    override suspend fun conversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val saved = connection()
        val binding = binding()
        scheduleSemantic()
        val conversation = database.ensureConversation(owner(binding), saved.model, binding.profile)
        initializeDailyPlan(conversation.id)
        listOf(conversation)
    }
    override suspend fun createConversation(model: String, useRag: Boolean, profile: CompanionProfile?): Conversation = withContext(Dispatchers.IO) {
        val binding = binding()
        require(model == binding.model) { "不能另选模型或新建其他恋人" }
        CompanionRules.checkChange(binding, CompanionRules.normalize(profile))
        val conversation = database.ensureConversation(owner(binding), model, binding.profile)
        initializeDailyPlan(conversation.id)
        conversation
    }
    override suspend fun changeProfile(id: String, profile: CompanionProfile?): Conversation = serial.withLock { withContext(Dispatchers.IO) {
        val binding = binding()
        val normalized = CompanionRules.normalize(profile)
        CompanionRules.checkChange(binding, normalized)
        database.conversation(owner(binding), id)
        store.writeBinding(binding.copy(profile = normalized, profileConfigured = true))
        database.changeProfile(owner(binding), id, normalized)
    } }
    override suspend fun changeRag(id: String, enabled: Boolean) = withContext(Dispatchers.IO) { database.changeRag(currentOwner(), id, enabled) }
    override suspend fun deleteConversation(id: String): Unit = throw IllegalStateException("唯一的恋人聊天与原文不能通过新建话题清除")
    override suspend fun messages(id: String): List<Message> = history(id).messages
    override suspend fun history(id: String, before: String?): MessagePage = withContext(Dispatchers.IO) { database.history(currentOwner(), id, before) }
    override suspend fun documents(): List<Document> = withContext(Dispatchers.IO) { database.documents(currentOwner()) }
    override suspend fun upload(file: UploadedFile): Document = serial.withLock { withContext(Dispatchers.IO) {
        val name = ConnectionRules.documentName(file.name, file.bytes.size)
        database.importDocument(currentOwner(), name, NativeDocumentParser(appContext).parse(file))
    } }
    override suspend fun deleteDocument(id: String) { withContext(Dispatchers.IO) { database.deleteDocument(currentOwner(), id) } }
    override suspend fun memory(id: String, before: String?) = withContext(Dispatchers.IO) { database.memory(currentOwner(), id, before) }
    override suspend fun messageMemory(id: String, messageId: String) = withContext(Dispatchers.IO) { database.memoryEntry(currentOwner(), id, messageId) }
    override suspend fun changeMemory(id: String, messageId: String, change: MemoryChange) = withContext(Dispatchers.IO) {
        database.changeMemory(currentOwner(), id, messageId, change)
    }

    override suspend fun savedPhotoDraft(): ChatImageDraft? = withContext(Dispatchers.IO) {
        val draft = photos.readDraft() ?: return@withContext null
        val currentBinding = store.readBinding() ?: return@withContext draft
        if (!database.hasUserMediaAttachment(owner(currentBinding), draft.attachment.id)) return@withContext draft
        try { photos.clearDraftIf(draft.attachment.id) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            android.util.Log.w("ZhiYuImages", "Sent photo draft cleanup incomplete; the committed attachment will not be restored")
        }
        null
    }
    override suspend fun discardPhotoDraft(id: String) = withContext(Dispatchers.IO) { photos.clearDraftIf(id) }
    override suspend fun sendMessage(id: String, content: String): Pair<Message, Message> = send(id, content, null)
    override suspend fun sendPhotoMessage(id: String, content: String, attachment: MediaAttachment): Pair<Message, Message> = send(id, content, attachment)
    override suspend fun sendPhotoMessage(id: String, content: String, attachment: MediaAttachment,
        onSubmitted: (Message) -> Unit): Pair<Message, Message> = send(id, content, attachment, onSubmitted)
    private suspend fun send(id: String, content: String, photo: MediaAttachment?,
        onSubmitted: (Message) -> Unit = {}): Pair<Message, Message> = serial.withLock {
        lastCalendarNotice = null
        if (photo == null) ConnectionRules.message(content) else LocalMemoryCodec.bytes(content)
        require(content.length <= 16000) { "单条原文不能超过16000个字符" }
        val text = content
        val saved = withContext(Dispatchers.IO) { connection() }
        val binding = withContext(Dispatchers.IO) { binding() }
        val payload = photo?.let { withContext(Dispatchers.IO) { photos.payload(it) } }
        val owner = owner(binding)
        val user = withContext(Dispatchers.IO) { submitUser(owner, id, text, photo) }
        onSubmitted(user)
        scheduleSemantic()
        val analysis = payload?.let { photoAnalyzer.prepare(it, saved.providerId, saved.model) }
        if (photo != null && analysis != null) withContext(Dispatchers.IO) { photos.saveObservations(photo, analysis.observations) }
        val recent = withContext(Dispatchers.IO) { database.mediaHistory(owner, id) }
        val promptContext = withContext(Dispatchers.IO) { database.promptContext(owner, id) }
        val boundedRecent = promptContext.messages
        val conversation = withContext(Dispatchers.IO) { database.conversation(owner, id) }
        val retrieval = if (conversation.useRag) semantic.retrieve(owner,id,text,promptContext.completeMessageIds) else LocalSemanticRetrieval(emptyList(),"disabled",null)
        lastRetrievalRoute = retrieval
        val recalled = retrieval.originals
        val sources = if (conversation.useRag) withContext(Dispatchers.IO) { database.documentSources(owner, text) } else emptyList()
        val plan = media.prepare(id, conversation.profile, recent)
        val calendarPlan = prepareCalendar(text)
        val pictureContext = withContext(Dispatchers.IO) { photoHistory.build(boundedRecent, saved.providerId, saved.model,
            user.id, payload, analysis) }
        val system = LocalCompanionPrompt.build(conversation.profile, recalled, sources, careContext(), retrieval.status, boundedRecent.size) + "\n" + plan.prompt +
            pictureContext.evidence.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty() +
            calendarPlan.prompt.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
        val context = listOf(DirectChatMessage("system", system)) + pictureContext.messages
        val raw = provider.complete(saved.providerId, saved.apiKey, saved.model, context, outputBudget())
        currentCoroutineContext().ensureActive()
        val calendarReply = CompanionCalendarProtocol.parse(raw, calendarPlan)
        val assistant = withContext(Dispatchers.IO) {
            media.commit(id, plan, calendarReply.content) { reply ->
                checkStillConnected(saved)
                database.append(owner, id, "assistant", reply.content, sources = sources, attachments = reply.attachments)
            }
        }
        commitCalendar(assistant, calendarReply)
        scheduleSemantic()
        user to assistant
    }
    private fun submitUser(owner: String, id: String, text: String, photo: MediaAttachment?): Message {
        val existing = photo?.let { database.submittedPhoto(owner, id, it.id) }
        if (existing != null) {
            require(existing.content == text && existing.mediaAttachments == listOf(photo)) { "图片已发送，新的文字请另发一条" }
            require(database.mediaHistory(owner, id).lastOrNull()?.id == existing.id) { "这张图片已经发送，请发新消息继续聊" }
            return existing
        }
        return database.append(owner, id, "user", text, attachments = listOfNotNull(photo))
    }
    suspend fun proactiveResult(id: String, requestId: String): Message? = withContext(Dispatchers.IO) {
        require(requestPattern.matches(requestId)) { "主动消息请求标识无效" }
        database.proactiveResult(currentOwner(), id, requestId)
    }
    suspend fun proactivePolicyHistory(id: String): List<Message> = withContext(Dispatchers.IO) { database.proactivePolicyHistory(currentOwner(), id) }
    suspend fun sendDailyShare(id: String, requestId: String, event: LocalDailyShareEvent,
        attachment: MediaAttachment): Message {
        throw UnsupportedOperationException("TDD pending: daily share required-image delivery")
    }
    suspend fun sendProactive(id: String, requestId: String, nightEvent: LocalNightEvent? = null): Message = withTimeout(180000) { coroutineScope {
        val scope = this
        val foregroundWatcher = launch {
            ProactiveForeground.changes.first { it }
            scope.cancel(CancellationException("用户回到前台，取消后台主动生成"))
        }
        try { serial.withLock { generateProactive(id, requestId, nightEvent) } }
        finally { foregroundWatcher.cancel() }
    } }
    private suspend fun generateProactive(id: String, requestId: String, nightEvent: LocalNightEvent?): Message {
        lastCalendarNotice = null
        require(requestPattern.matches(requestId)) { "主动消息请求标识无效" }
        val saved = withContext(Dispatchers.IO) { connection() }
        val owner = withContext(Dispatchers.IO) { currentOwner() }
        withContext(Dispatchers.IO) { database.proactiveResult(owner, id, requestId) }?.let { return it }
        val conversation = withContext(Dispatchers.IO) { database.conversation(owner, id) }
        val recent = withContext(Dispatchers.IO) { database.mediaHistory(owner, id) }
        val promptContext = withContext(Dispatchers.IO) { database.promptHistory(owner, id) }
        val plan = media.prepare(id, conversation.profile, recent)
        // Proactive generation has no new user agreement; it may only create a same-day personal diary.
        val calendarPlan = prepareCalendar("")
        val system = LocalCompanionPrompt.build(conversation.profile, emptyList(), emptyList(), careContext(),
            LocalRetrievalStatus.NOT_SEARCHED, promptContext.size) + "\n" + plan.prompt +
            "\n本次由应用发起主动联系，并非用户新原话。遵循恋人性格，只发一条自然消息；不指责未回复，不虚构用户活动、天气、共同约定或自己的真实线下生活。" +
            nightEvent?.prompt()?.let { "\n$it" }.orEmpty() +
            calendarPlan.prompt.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
        val context = listOf(DirectChatMessage("system", system)) + promptContext.filter { it.content.isNotBlank() }.map { DirectChatMessage(it.role, it.content) } +
            DirectChatMessage("user", "[应用的主动联系请求，不是真实用户消息] 请自然地同恋人说一句话，可以选择不索要回应。")
        val raw = provider.complete(saved.providerId, saved.apiKey, saved.model, context, outputBudget())
        currentCoroutineContext().ensureActive()
        val calendarReply = CompanionCalendarProtocol.parse(raw, calendarPlan)
        val assistant = withContext(Dispatchers.IO) {
            media.commit(id, plan, calendarReply.content) { reply ->
                checkStillConnected(saved)
                database.append(owner, id, "assistant", reply.content, "proactive", attachments = reply.attachments, requestId = requestId)
            }
        }
        commitCalendar(assistant, calendarReply)
        scheduleSemantic()
        return assistant
    }
    private suspend fun prepareCalendar(text: String): CompanionCalendarPlan = withContext(Dispatchers.IO) {
        try { calendar.promptPlan(text) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            lastCalendarNotice = "日历暂时无法使用，聊天仍可继续。"
            CompanionCalendarProtocol.prepare(false, java.time.LocalDate.now(), text)
        }
    }
    private suspend fun initializeDailyPlan(chatId: String) {
        val plans = daily ?: return
        try {
            val version = LocalVisualRepository(appContext).profile(chatId).visualVersion
            plans.scene(chatId, version, dailyNow())
            if (database.isMainArchive) LocalSceneStockScheduler.enqueue(appContext, chatId, version, LocalDailyRules.dayId(dailyNow()))
            lastDailyNotice = null
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            lastDailyNotice = "TA 今天的安排暂时没能准备好，原来的聊天与安排仍会保留。"
        }
        if (database.isMainArchive) try { LocalDailyScheduler.schedule(appContext, chatId, dailyNow()) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            lastDailyNotice = "TA 今天的安排仍会保留，之后的安排会在你打开应用时更新。"
        }
    }
    private suspend fun commitCalendar(message: Message, reply: CompanionCalendarReply) = withContext(Dispatchers.IO) {
        val outcome = calendar.commit(message.id, reply)
        outcome.notice?.let { lastCalendarNotice = it }
    }
    private fun outputBudget(): Int = appContext.getSharedPreferences("local_ai_settings", Context.MODE_PRIVATE)
        .getInt("max_output_tokens", 8192).coerceIn(8192, 65536)
    private fun checkStillConnected(expected: SavedConnection) {
        val current = store.read()
        check(current != null && current.providerId == expected.providerId && current.model == expected.model && current.apiKey == expected.apiKey) {
            "Key 已清除或更新，未把延迟响应写入这段聊天"
        }
    }
}
