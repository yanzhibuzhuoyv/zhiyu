package com.zhiyu.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.*
import com.zhiyu.app.data.planar.*
import com.zhiyu.app.data.ownedOcBitmap
import com.zhiyu.app.domain.planar.*
import kotlinx.coroutines.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Owned local edits scoped to a fixed chat and its confirmed original photo. */
@Stable class PlanarWorkshopController(context:Context,private val scope:CoroutineScope) {
    private val app=context.applicationContext
    private val contractJson=app.assets.open("planar/contract.json").bufferedReader().use {it.readText()}
    private val reader=PlanarPackageReader(contractJson)
    private var store:PlanarPackageStore?=null
    private var draftStore:PlanarDraftStore?=null
    private var key:String?=null
    private var recoveryKey:String?=null
    private var parentSha:String?=null
    private var sourcePng:ByteArray?=null
    private var checkpointJob:Deferred<Unit>?=null
    private var journalRevision=0L
    private var disposed=false
    private var epoch=0L
    private var job:Job?=null
    private var history:PlanarEditHistory<PlanarDraft>?=null
    private var fixedBackground=false
    var active by mutableStateOf<PlanarDocument?>(null);private set
    var previewOverride by mutableStateOf<PlanarDocument?>(null);private set
    var source by mutableStateOf<Bitmap?>(null);private set
    var draft by mutableStateOf<PlanarDraft?>(null);private set
    var busy by mutableStateOf(false);private set
    var dirty by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var notice by mutableStateOf<String?>(null);private set
    val canUndo get()=history?.canUndo==true
    val canRedo get()=history?.canRedo==true
    val bindingRevision get()=epoch
    private val savedDocument:PlanarDocument? get()=if(fixedBackground)active else null
    val previewDocument:PlanarDocument? get()=previewOverride?:savedDocument
    fun checkpoint() {persist(immediate=true)}
    suspend fun awaitCheckpoint() {checkpointJob?.await()}
    fun discardDraft() {
        if(busy)return
        val target=draftStore?:return
        task {
            journalRevision=PlanarDraftPersistence.clear(target);checkpointJob=null
            source?.recycle();source=null;sourcePng=null;draft=null;history=null;dirty=false;previewOverride=null
            notice="未保存的修改已放弃，已保存形象仍保留。"
        }
    }
    fun forBinding(binding:String?):PlanarDocument? = if(binding!=null&&binding==key)savedDocument else null
    private fun adopt(document:PlanarDocument?) {fixedBackground=document?.let(PlanarCallReadiness::fixedBackground)==true;active=document;previewOverride=null}
    fun connect(binding:String?,recoveryBinding:String?=binding) {
        if(disposed||(binding==key&&recoveryBinding==recoveryKey))return
        checkpoint()
        epoch++;job?.cancel();key=binding;recoveryKey=recoveryBinding;store=null;draftStore=null;parentSha=null
        adopt(null);source?.recycle();source=null;sourcePng=null;draft=null;history=null;busy=false;dirty=false;error=null;notice=null
        if(binding==null)return
        require(!recoveryBinding.isNullOrBlank()){"形象恢复身份无效"}
        // Android may expose /data/user/0 through /data/data. Resolve only the trusted app root,
        // then append owned children so a symlink inside planar-v1 is still rejected by the draft store.
        val root=File(app.filesDir.canonicalFile,"planar-v1/${sha(binding.toByteArray())}")
        val target=PlanarPackageStore(root,contractJson);store=target;draftStore=PlanarDraftStore(root)
        journalRevision=PlanarDraftPersistence.revision(draftStore!!)
        task {val saved=withContext(Dispatchers.IO){target.loadActive() to target.activeIdentity()}
            adopt(saved.first);parentSha=saved.second
            if(active!=null&&!fixedBackground)notice="旧角色包仍可编辑，请确认后保存，通话会保留静止背景。"}
    }
    fun open(loadSource:suspend()->Bitmap?) {
        if(source!=null||busy||key==null)return
        task {
            val target=draftStore?:return@task
            var restoreFailed=false
            try {PlanarDraftPersistence.awaitPending(target)}
            catch(failure:Exception){if(failure is CancellationException)throw failure
                restoreFailed=true;error="最近一次暂存没有完成，正在尝试恢复上次保存的修改。"}
            val saved=try {PlanarDraftPersistence.load(target,sha(checkNotNull(recoveryKey).toByteArray()),parentSha)}
            catch(failure:Exception){if(failure is CancellationException)throw failure
                restoreFailed=true;error="暂存修改无法恢复，已保存形象仍保留。请重新调整后保存。";null}
            if(saved!=null&&restore(saved))return@task
            if(saved!=null){restoreFailed=true;error="暂存修改无法恢复，已保存形象仍保留。请重新调整后保存。"}
            val document=active
            var owned=if(document!=null)ownedOcBitmap {checkNotNull(BitmapFactory.decodeByteArray(document.sourcePng,0,document.sourcePng.size))}
                else checkNotNull(loadSource()){"请先导入或生成恋人的图片"}
            var adopted=false
            try {
                owned=fit(owned)
                val initial=if(document!=null)PlanarDraftCodec.decode(document.definitionJson,owned.width,owned.height)
                    else PlanarDraft.suggest(owned.width,owned.height)
                val edits=PlanarEditHistory(initial,PlanarDraftCodec::measure)
                sourcePng=withContext(Dispatchers.Default){png(owned)}
                source=owned;history=edits;draft=initial;dirty=document==null;adopted=true;previewOverride=null
                notice="建议位置可自行调整；确认后保存，用于通话。"
                if(dirty&&!restoreFailed)persist()
            } finally {if(!adopted)owned.recycle()}
        }
    }
    private fun fit(bitmap:Bitmap):Bitmap {
        val longest=maxOf(bitmap.width,bitmap.height)
        if(longest<=1536)return bitmap
        val scale=1536.0/longest
        return Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).roundToInt().coerceAtLeast(2),
            (bitmap.height*scale).roundToInt().coerceAtLeast(2),true).also {if(it!==bitmap)bitmap.recycle()}
    }
    fun edit(next:PlanarDraft) {
        if(busy)return
        try {if(history?.update(next)==true){draft=history!!.value;dirty=true;previewOverride=null;notice="修改会自动暂存，确认后保存用于通话";error=null;persist()}}
        catch(failure:IllegalArgumentException){error=failure.message?:"这次修改太大，请缩小范围"}
    }
    fun undo(){if(!busy&&history?.undo()==true){draft=history!!.value;dirty=true;previewOverride=null;error=null;persist()}}
    fun redo(){if(!busy&&history?.redo()==true){draft=history!!.value;dirty=true;previewOverride=null;error=null;persist()}}

    fun addHeadMotion() {
        if(busy)return
        val current=draft?:return
        val photo=source?:return
        try {
            val part=PlanarHeadMotionSuggestion.create(current,photo.width,photo.height)
            edit(current.copy(parts=current.parts+part))
            notice="已补充头部微动建议，可调整轮廓或撤销；预览满意后再保存。原有活动范围仍会限制动作。"
        } catch(failure:IllegalArgumentException) {error=failure.message?:"请先检查头部标记"}
    }

    /** Render the current, possibly unsaved annotations; the call page still uses only the saved package. */
    fun buildPreview() {
        if(busy)return
        val photo=source?:return
        val current=draft?:return
        task {
            val owned=checkNotNull(photo.copy(Bitmap.Config.ARGB_8888,false)){"原图暂时无法读取"}
            try {
                val document=withContext(Dispatchers.Default){
                    PlanarPhotoBuilder().build(owned,current.copy(suggestionsConfirmed=true),"当前标注预览",reader.contract)
                }
                previewOverride=document;error=null
            } catch(failure:Exception){
                if(failure is CancellationException)throw failure
                error=failure.message?:"当前标注还不能生成预览，请检查眼口和头颈"
            } finally {owned.recycle()}
        }
    }
    fun import(uri:Uri,expectedBinding:Long=epoch) {
        if(expectedBinding!=epoch){error="形象已更新，请重新选择角色包";return}
        if(busy||key==null)return
        if(store==null)return
        task {
            // Read into a draft first: importing does not replace the saved OC until the user saves.
            val document=withContext(Dispatchers.IO){val current=currentCoroutineContext();app.contentResolver.openInputStream(uri)?.use {
                reader.read(it,checkCancelled={current.ensureActive()}) }?:error("无法读取角色包")}
            val bitmap=ownedOcBitmap {checkNotNull(BitmapFactory.decodeByteArray(document.sourcePng,0,document.sourcePng.size))}
            var adopted=false
            try {
                val initial=PlanarDraftCodec.decode(document.definitionJson,bitmap.width,bitmap.height)
                val edits=PlanarEditHistory(initial,PlanarDraftCodec::measure)
                source?.recycle();source=bitmap;sourcePng=document.sourcePng;draft=initial;history=edits;adopted=true;previewOverride=null
                dirty=true;notice="已打开角色包，请确认后保存。";persist()
            } finally {if(!adopted)bitmap.recycle()}
        }
    }
    fun save(name:String) {
        if(busy)return
        val photo=source?:return;val current=draft?:return;val target=store?:return
        task {
            val owned=checkNotNull(photo.copy(Bitmap.Config.ARGB_8888,false)){"原图暂时无法读取"}
            try {
                val document=withContext(Dispatchers.Default){PlanarPhotoBuilder().build(owned,current.copy(suggestionsConfirmed=true),name,reader.contract)}
                val saved=withContext(Dispatchers.IO){val ctx=currentCoroutineContext();target.save(document){ctx.ensureActive()}}
                adopt(saved);parentSha=withContext(Dispatchers.IO){target.activeIdentity()};dirty=false
                draftStore?.let {journalRevision=PlanarDraftPersistence.clear(it)};checkpointJob=null
                notice="形象已保存，下次通话会使用这个角色。"
            } finally {owned.recycle()}
        }
    }
    fun export(uri:Uri,expectedBinding:Long=epoch) {
        if(expectedBinding!=epoch){error="形象已更新，请重新选择导出位置";return}
        if(busy||dirty)return
        val target=store?:return
        task {withContext(Dispatchers.IO){val bytes=target.export();currentCoroutineContext().ensureActive()
            app.contentResolver.openOutputStream(uri)?.use {it.write(bytes)}?:error("无法写入角色包")}
            notice="角色包已导出。"}
    }
    private fun task(action:suspend()->Unit) {
        val stamp=epoch
        busy=true;error=null
        job=scope.launch {
            if(epoch!=stamp)return@launch
            try {action();ensureActive()}
            catch(failure:Exception){if(failure is CancellationException)throw failure
                if(epoch==stamp)error=if(failure.message?.startsWith("PLANAR_")==true)"角色包不完整或超出手机处理范围，请检查后重试"
                    else if(failure is IllegalArgumentException||failure is IllegalStateException)failure.message?:"请检查角色标注"
                    else "操作暂时没有完成，修改已保留，请重试"}
            finally {if(epoch==stamp)busy=false}
        }
    }
    private fun persist(immediate:Boolean=false) {
        if(disposed||!dirty)return
        val target=draftStore?:return;val current=draft?:return;val photo=source?:return
        val bytes=sourcePng?:return;val binding=recoveryKey?:return;val stamp=epoch
        val pending=PlanarDraftPersistence.submit(target,journalRevision,sha(binding.toByteArray()),parentSha,photo.width,photo.height,bytes,current,immediate)
        checkpointJob=pending
        pending.invokeOnCompletion {failure->if(failure!=null&&failure !is CancellationException)scope.launch {
            if(epoch==stamp&&!disposed)error="修改还在当前页面，暂存没有完成。请释放空间后重试，关闭前请保存。"
        }}
    }
    private suspend fun restore(saved:PlanarDraftCheckpoint):Boolean {
        var bitmap:Bitmap?=null
        return try {
            val initial=withContext(Dispatchers.Default){PlanarDraftCodec.decodeCheckpoint(saved.draftJson,saved.width,saved.height)}
            val edits=PlanarEditHistory(initial,PlanarDraftCodec::measure)
            bitmap=ownedOcBitmap {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(saved.sourcePng,0,saved.sourcePng.size,bounds)
                require(bounds.outWidth==saved.width&&bounds.outHeight==saved.height){"草稿原图尺寸无效"}
                checkNotNull(BitmapFactory.decodeByteArray(saved.sourcePng,0,saved.sourcePng.size))
            }
            source=bitmap;bitmap=null;sourcePng=saved.sourcePng;draft=initial;history=edits;dirty=true;previewOverride=null
            notice="已恢复未保存的修改，请确认后保存；原图和背景保持原样。";true
        }catch(failure:Exception){if(failure is CancellationException)throw failure;false}
        finally {bitmap?.recycle()}
    }
    private fun png(bitmap:Bitmap):ByteArray=ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)){"原图暂存失败"};it.toByteArray()
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun closeEditor(){if(!busy){checkpoint();source?.recycle();source=null;sourcePng=null;draft=null;history=null;dirty=false;previewOverride=null}}
    fun dispose(){if(disposed)return;checkpoint();disposed=true;epoch++;job?.cancel();source?.recycle();source=null;sourcePng=null;draft=null;history=null;active=null}
}
