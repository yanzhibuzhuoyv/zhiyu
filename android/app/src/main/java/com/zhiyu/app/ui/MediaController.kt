package com.zhiyu.app.ui

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

val LocalMediaController = staticCompositionLocalOf<MediaController?> { null }

@Composable fun rememberMediaController(conversationId: String?, connected: Boolean): MediaController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { MediaController(context, scope) }
    LaunchedEffect(conversationId, connected) { controller.connect(if (connected) conversationId else null) }
    return controller
}

@Stable class MediaController(context: Context, private val scope: CoroutineScope) {
    private val contextForAssets = context.applicationContext
    private val repository = LocalVisualRepository(context)
    private val reader = VisualReferenceReader(context)
    private val generationStore = VisualGenerationStore(context)
    private val prefs = context.getSharedPreferences("zhiyu_visual_setup", Context.MODE_PRIVATE)
    private var conversationId: String? = null
    private val cache = object : LruCache<String, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val cacheLock = Mutex()
    var pendingDescription by mutableStateOf<String?>(null); private set
    var profile by mutableStateOf<VisualProfile?>(null); private set
    var templates by mutableStateOf<List<VisualTemplate>>(emptyList()); private set
    var generatedCandidates by mutableStateOf<List<MediaAttachment>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var suggestSetup by mutableStateOf(false); private set
    var subjectPreview by mutableStateOf<PortraitPreview?>(null); private set
    private var pendingOriginal: ByteArray? = null
    var canKeepOriginal by mutableStateOf(false); private set

    fun reportReferenceFailure() { error = "恋人的外观暂时没能打开，原图仍会保留。请重新打开外观设置。" }

    suspend fun connect(id: String?) {
        if (id == conversationId) return
        conversationId = id
        profile = null
        templates = emptyList()
        generatedCandidates = emptyList()
        error = null
        cache.evictAll()
        pendingDescription = null
        suggestSetup = false
        subjectPreview = null
        pendingOriginal = null
        canKeepOriginal = false
        if (id == null) return
        try {
            val pending = withContext(Dispatchers.IO) { generationStore.read(id) }
            if (id == conversationId) pendingDescription = pending?.description
            val loaded = repository.profile(id)
            if (id == conversationId) {
                profile = loaded
                suggestSetup = loaded.visualVersion == 0 && !prefs.getBoolean(id, false)
            }
            val samples = repository.templates()
            if (id == conversationId) templates = samples
            val candidates=repository.generatedCandidates(id)
            if(id==conversationId) generatedCandidates=candidates
            val pendingPreview = repository.activePreview(id)
            if (id == conversationId && pendingPreview != null) {
                subjectPreview = pendingPreview
                canKeepOriginal = true
                if (pendingPreview.method == "manual_selection") error = "自动分离未完成，请打开修整，用保留笔刷选中人物。"
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = "恋人外观暂时无法读取，请稍后打开设置重试"
        }
    }
    fun acknowledgeSetup() {
        conversationId?.let { prefs.edit().putBoolean(it, true).apply() }
        suggestSetup = false
    }
    fun dismissError() { error = null }
    fun reload() = operation { id ->
        val result = repository.profile(id)
        val samples = repository.templates()
        if (conversationId == id) templates = samples
        val candidates=repository.generatedCandidates(id)
        if(id==conversationId) generatedCandidates=candidates
        result
    }
    fun save(mode: String, description: String, gender: String, hair: String) = operation {
        repository.update(it, mode, description, gender, hair)
    }
    fun importReference(uri: Uri, separateSubject: Boolean = false) {
        if (!separateSubject) { operation { repository.import(it, reader.read(uri)) }; return }
        if (busy) return
        val id = conversationId ?: return
        scope.launch {
            busy = true
            error = null
            subjectPreview = null
            pendingOriginal = null
            canKeepOriginal = false
            try {
                val bytes = reader.read(uri)
                if (conversationId != id) return@launch
                pendingOriginal = bytes
                canKeepOriginal = true
                val result = repository.previewSubject(id, bytes)
                if (conversationId == id) {
                    subjectPreview = result
                    if (result.method == "manual_selection") error = "自动分离未完成，请打开修整，用保留笔刷选出人物、翅膀和道具。"
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (conversationId == id) error = failure.message ?: "人物分离未完成，可以保留原图或换一张"
            } finally { busy = false }
        }
    }
    fun confirmSubject(choice: String) = operation { id ->
        val preview = subjectPreview ?: throw IllegalArgumentException("请重新导入人物图片")
        repository.confirmSubject(id, preview.id, choice, preview.subjectSha256).also { discardPreview() }
    }
    fun refineSubject(strokes: List<PortraitStroke>, complete: () -> Unit) {
        if (busy) return
        val id = conversationId ?: return
        val preview = subjectPreview ?: return
        scope.launch {
            busy = true
            error = null
            try {
                val result = repository.refineSubject(id, preview.id, preview.subjectSha256, strokes)
                if (conversationId == id && subjectPreview?.subjectSha256 == preview.subjectSha256) {
                    subjectPreview = result
                    cache.evictAll()
                    complete()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (conversationId == id) error = failure.message ?: "人物修整未完成，请重试"
            } finally { busy = false }
        }
    }
    fun keepOriginal() = operation { id ->
        val bytes = pendingOriginal ?: throw IllegalArgumentException("请重新选择原图")
        repository.import(id, bytes).also { discardPreview() }
    }
    fun discardPreview() {
        val id = conversationId
        val previewId = subjectPreview?.id
        subjectPreview = null
        pendingOriginal = null
        canKeepOriginal = false
        if (id != null && previewId != null) scope.launch {
            try { repository.discardPreview(id, previewId) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (conversationId == id) error = failure.message ?: "人物预览状态未保存"
            }
        }
    }
    fun useTemplate(templateId: String) = operation { repository.useTemplate(it, templateId) }
    fun useGeneratedCandidate(assetId: String) = operation { repository.useGeneratedCandidate(it,assetId) }
    suspend fun publishCharacter(id: String, identity: OcIdentity, png: ByteArray, expectedVersion: Int): OcAssetRef {
        require(id == conversationId && !busy) { "外观正在更新，请稍后重试" }
        val result = repository.publishCharacter(id, identity, png, expectedVersion)
        profile = repository.profile(id); acknowledgeSetup(); cache.evictAll()
        return result
    }
    suspend fun currentReference(): OcAssetRef? = withContext(Dispatchers.IO) {
        val chat=conversationId ?:return@withContext null
        val current=repository.profile(chat)
        val chosen = current.referenceId ?: return@withContext null
        val asset = LocalMediaStore(contextForAssets).details(chosen).attachment
        require(conversationId==chat && repository.profile(chat).let { it.referenceId==chosen && it.visualVersion==current.visualVersion }) { "外观已更新，请重试" }
        OcAssetRef(asset.id, asset.sha256, current.visualVersion)
    }
    fun generate(description: String) = operation { id ->
        val pending = withContext(Dispatchers.IO) { generationStore.begin(id, description) }
        pendingDescription = pending.description
        try {
            repository.generate(id, pending.id, description).also {
                withContext(Dispatchers.IO) { generationStore.complete(id, pending.id) }
                pendingDescription = null
            }
        } catch (failure: ApiFailure) {
            if (failure.errorCode in setOf("generation_failed", "generation_unconfigured", "generation_stale", "image_auth_failed",
                    "image_balance_insufficient", "image_rate_limited", "image_request_invalid", "image_redirect_rejected")) {
                withContext(Dispatchers.IO) { generationStore.complete(id, pending.id) }
                pendingDescription = null
            }
            throw failure
        }
    }
    private fun operation(block: suspend (String) -> VisualProfile) {
        if (busy) return
        val id = conversationId ?: return
        scope.launch {
            busy = true
            error = null
            try {
                val result = block(id)
                if (conversationId == id) { profile = result; acknowledgeSetup(); cache.evictAll()
                    LocalSceneStockScheduler.enqueue(contextForAssets, id, result.visualVersion) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = when (failure) {
                    is ApiFailure, is IllegalArgumentException -> failure.message ?: "外观操作失败"
                    else -> "外观操作失败，请刷新外观后重试"
                }
            } finally {
                if(currentCoroutineContext().isActive && conversationId==id) try {
                    val latest=repository.profile(id); val candidates=repository.generatedCandidates(id)
                    if(conversationId==id) { profile=latest; generatedCandidates=candidates }
                } catch(refresh:Exception) {
                    if(refresh is CancellationException) throw refresh
                    if(error==null) error="外观操作结果暂时无法读取，请刷新外观"
                }
                busy = false
            }
        }
    }
    suspend fun image(id: String, sha256: String? = null, template: Boolean = false): ByteArray = cacheLock.withLock {
        val ownerChat = conversationId ?: throw ApiFailure(401, "请先连接")
        val key = "$ownerChat:$template:$id:${sha256.orEmpty()}"
        cache.get(key)?.let { return@withLock it }
        val bytes = repository.download(id, sha256, template)
        check(conversationId == ownerChat) { "聊天已切换，请重新打开图片" }
        cache.put(key, bytes)
        bytes
    }
}
