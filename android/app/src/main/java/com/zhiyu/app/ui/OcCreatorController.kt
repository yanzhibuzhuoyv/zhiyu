package com.zhiyu.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.UUID

/** publish commits immutable rendered bytes to the existing media/profile boundary. */
@Stable class OcCreatorController(context: Context, private val scope: CoroutineScope,
    private val publish: suspend (chatId: String, identity: OcIdentity, png: ByteArray, expectedVisualVersion: Int) -> OcAssetRef,
    private val loadImage: suspend (OcAssetRef) -> ByteArray,
    private val refreshReference: (suspend () -> OcAssetRef?)? = null,
    private val onCommitted: suspend (chatId: String, snapshot: OcIdentitySnapshot) -> Unit = { _, _ -> }) {
    private val store = OcCreatorStore(context)
    private var chatId: String? = null
    private var currentReference by mutableStateOf<OcAssetRef?>(null)
    private var currentVisualVersion = 0
    var identity by mutableStateOf<OcIdentity?>(null); private set
    val callIdentity: OcIdentity? get() = OcCallCharacterRules.resolve(identity, currentReference)
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    suspend fun connect(id: String?, reference: OcAssetRef?, visualVersion: Int = reference?.visualVersion ?: 0) {
        require(visualVersion >= 0)
        currentReference = reference
        currentVisualVersion = visualVersion
        val changed = id != chatId
        if (changed) { chatId = id; identity = null; error = null }
        if (id == null) return
        if (busy) return
        try {
            val saved = withContext(Dispatchers.IO) { store.recoverPublication(id, reference, visualVersion) }
            if (chatId == id) {
                identity = saved?.identity ?: identity ?: OcIdentityRules.fresh("unknown")
                if (saved != null) notifyCommitted(id, saved)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (chatId == id) error = "角色资料暂时无法读取，请重新打开"
        }
    }
    fun draft(route: String): OcIdentity {
        require(route == "photo_subject") { "请通过导入或生成图片设置恋人外观" }
        val existing = identity ?: throw IllegalArgumentException("请等待角色资料读取")
        val compatible = existing.route == "photo_subject"
        val source = if (compatible && currentReference == existing.reference) existing.photoSource ?: currentReference else currentReference
        return OcIdentityRules.check(existing.copy(route = route, photoSource = source,
            body = OcBody(), parts = emptyList(), rig = if (compatible) existing.rig else OcRigRules.defaultRig()))
    }
    suspend fun source(draft: OcIdentity): Bitmap? {
        if (draft.route != "photo_subject") return null
        val source = draft.photoSource ?: throw IllegalArgumentException("请先导入并确认角色图片")
        val bytes = loadImage(source)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(sha == source.sha256) { "角色原图校验失败" }
        return ownedOcBitmap { LocalPortraitProcessor.decode(bytes) }
    }
    fun save(draft: OcIdentity, complete: () -> Unit) {
        if (busy) return
        val id = chatId ?: return
        val before = identity ?: return
        scope.launch {
            busy = true; error = null
            var photo: Bitmap? = null
            val attempt = UUID.randomUUID().toString()
            var committed = false
            try {
                require(draft.route == "photo_subject") { "请通过导入或生成图片设置恋人外观" }
                val freshReference = if (refreshReference != null) refreshReference.invoke() else currentReference
                check(chatId == id) { "聊天已切换，请重新打开角色编辑器" }
                val expectedVisualVersion = freshReference?.visualVersion ?: currentVisualVersion
                val recovered = withContext(Dispatchers.IO) { store.recoverPublication(id, freshReference, expectedVisualVersion) }
                if (recovered != null && recovered.identity.revision != before.revision) {
                    if (chatId == id) { identity = recovered.identity; currentReference = freshReference; notifyCommitted(id, recovered); complete() }
                    return@launch
                }
                val proposed = OcIdentityRules.next(before, draft)
                photo = source(proposed)
                val rendered = withContext(Dispatchers.IO) { OcFlatRenderer.png(proposed, source = photo) }
                check(chatId == id && identity?.revision == before.revision) { "角色已更新，请重新打开" }
                val pngSha = MessageDigest.getInstance("SHA-256").digest(rendered).joinToString("") { "%02x".format(it) }
                withContext(Dispatchers.IO) { store.beginPublication(id, before.revision, expectedVisualVersion, proposed, pngSha, attempt) }
                val reference = publish(id, proposed, rendered, expectedVisualVersion)
                require(reference.sha256 == pngSha && reference.visualVersion == expectedVisualVersion + 1) { "角色图片发布校验失败" }
                val saved = withContext(Dispatchers.IO) { store.commit(id, before.revision, proposed.copy(reference = reference)) }
                committed = true
                if (chatId == id) { identity = saved.identity; currentReference = reference; notifyCommitted(id, saved); complete() }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (chatId == id) error = when (failure) {
                    is IllegalArgumentException, is IllegalStateException -> failure.message ?: "角色暂时无法保存"
                    else -> "角色暂时无法保存，请重试"
                }
            } finally {
                try { withContext(NonCancellable + Dispatchers.IO) { store.finishPublication(id, attempt, committed) } }
                catch (_: Exception) { if (chatId == id) error = "形象保存状态会在下次打开时重新核对" }
                finally { photo?.recycle(); busy = false }
            }
        }
    }
    private suspend fun notifyCommitted(id: String, snapshot: OcIdentitySnapshot) {
        try { onCommitted(id, snapshot) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = "形象已经保存，今天的图片会稍后再准备"
        }
    }
}
