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
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Owned local edits scoped to a fixed chat and its confirmed original photo. */
@Stable class PlanarWorkshopController(context:Context,private val scope:CoroutineScope) {
    private val app=context.applicationContext
    private val contractJson=app.assets.open("planar/contract.json").bufferedReader().use {it.readText()}
    private val reader=PlanarPackageReader(contractJson)
    private var store:PlanarPackageStore?=null
    private var key:String?=null
    private var epoch=0L
    private var job:Job?=null
    private var history:PlanarEditHistory<PlanarDraft>?=null
    var active by mutableStateOf<PlanarDocument?>(null);private set
    var source by mutableStateOf<Bitmap?>(null);private set
    var draft by mutableStateOf<PlanarDraft?>(null);private set
    var busy by mutableStateOf(false);private set
    var dirty by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var notice by mutableStateOf<String?>(null);private set
    val canUndo get()=history?.canUndo==true
    val canRedo get()=history?.canRedo==true
    val bindingRevision get()=epoch
    fun forBinding(binding:String?):PlanarDocument? = if(binding!=null&&binding==key)active else null
    fun connect(binding:String?) {
        if(binding==key)return
        epoch++;job?.cancel();key=binding;store=null;active=null;source?.recycle();source=null;draft=null;history=null;busy=false;dirty=false;error=null
        if(binding==null)return
        val namespace=MessageDigest.getInstance("SHA-256").digest(binding.toByteArray()).joinToString(""){"%02x".format(it)}
        val target=PlanarPackageStore(File(app.filesDir,"planar-v1/$namespace"),contractJson);store=target
        task {active=withContext(Dispatchers.IO){target.loadActive()}}
    }
    fun open(loadSource:suspend()->Bitmap?) {
        if(source!=null||busy||key==null)return
        task {
            val document=active
            var owned=if(document!=null)ownedOcBitmap {checkNotNull(BitmapFactory.decodeByteArray(document.sourcePng,0,document.sourcePng.size))}
                else checkNotNull(loadSource()){"请先导入或生成恋人的图片"}
            var adopted=false
            try {
                owned=fit(owned)
                val initial=if(document!=null)PlanarDraftCodec.decode(document.definitionJson,owned.width,owned.height)
                    else PlanarDraft.suggest(owned.width,owned.height)
                val edits=PlanarEditHistory(initial,PlanarDraftCodec::measure)
                source=owned;history=edits;draft=initial;dirty=document==null;adopted=true
                notice="建议位置可自行调整；确认后保存，用于通话。"
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
        try {if(history?.update(next)==true){draft=history!!.value;dirty=true;notice="修改未保存";error=null}}
        catch(failure:IllegalArgumentException){error=failure.message?:"这次修改太大，请缩小范围"}
    }
    fun undo(){if(!busy&&history?.undo()==true){draft=history!!.value;dirty=true;error=null}}
    fun redo(){if(!busy&&history?.redo()==true){draft=history!!.value;dirty=true;error=null}}
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
                source?.recycle();source=bitmap;draft=initial;history=edits;adopted=true
                dirty=true;notice="已打开角色包，请确认后保存。"
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
                active=saved;dirty=false;notice="形象已保存，下次通话会使用这个角色。"
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
    fun closeEditor(){if(!busy){source?.recycle();source=null;draft=null;history=null;dirty=false}}
    fun dispose(){epoch++;job?.cancel();source?.recycle();source=null;active=null}
}
