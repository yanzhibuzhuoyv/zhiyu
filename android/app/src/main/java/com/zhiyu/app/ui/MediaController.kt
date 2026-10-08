package com.zhiyu.app.ui

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.zhiyu.app.BuildConfig
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    private val repository = VisualRepository(UsbDebugCredentialStore(SecureCredentialStore(context),
        BuildConfig.DEBUG && BuildConfig.USE_USB_BACKEND), BuildConfig.DEBUG)
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
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var suggestSetup by mutableStateOf(false); private set

    suspend fun connect(id: String?) {
        if (id == conversationId) return
        conversationId = id
        profile = null
        templates = emptyList()
        error = null
        cache.evictAll()
        pendingDescription = null
        suggestSetup = false
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
        result
    }
    fun save(mode: String, description: String, gender: String, hair: String) = operation {
        repository.update(it, mode, description, gender, hair)
    }
    fun importReference(uri: Uri) = operation { repository.import(it, reader.read(uri)) }
    fun useTemplate(templateId: String) = operation { repository.useTemplate(it, templateId) }
    fun generate(description: String) = operation { id ->
        val pending = withContext(Dispatchers.IO) { generationStore.begin(id, description) }
        pendingDescription = pending.description
        try {
            repository.generate(id, pending.id, description).also {
                withContext(Dispatchers.IO) { generationStore.complete(id, pending.id) }
                pendingDescription = null
            }
        } catch (failure: ApiFailure) {
            if (failure.errorCode in setOf("generation_failed", "generation_unconfigured")) {
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
                if (conversationId == id) { profile = result; acknowledgeSetup(); cache.evictAll() }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = when (failure) {
                    is ApiFailure, is IllegalArgumentException -> failure.message ?: "外观操作失败"
                    else -> "外观操作失败，请刷新外观后重试"
                }
            } finally { busy = false }
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
