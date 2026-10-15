package com.zhiyu.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import com.zhiyu.app.data.ocexpansion.*
import com.zhiyu.app.data.ownedOcBitmap
import com.zhiyu.app.domain.ChatPhotoRules
import com.zhiyu.app.domain.Image2Protocol
import com.zhiyu.app.domain.MediaRules
import com.zhiyu.app.domain.OcFraming
import kotlinx.coroutines.*

@Stable class OcExpansionController(private val manager:OcExpansionManagement,private val scope:CoroutineScope,
    private val decoder:ScenePhotoPreviewDecoder=AndroidScenePhotoPreviewDecoder) {
    var view by mutableStateOf<OcExpansionView?>(null);private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var preview by mutableStateOf<Bitmap?>(null);private set
    var generationConfirmation by mutableStateOf(false);private set
    private var chat:String?=null
    private var epoch=0L
    private var operation=0L
    private var job:Job?=null
    private var previewId:String?=null
    private var previewSha:String?=null
    val canAccept get()=view?.candidate?.let {it.canConfirm&&it.requestId==previewId&&it.imageSha==previewSha&&preview!=null}==true
    fun connect(chat:String?) {
        chat?.let(MediaRules::path)
        if(this.chat==chat)return
        dispose();this.chat=chat
        if(chat!=null)reload()
    }
    fun reload()=task("外观补全资料暂时没有读到，请刷新后重试") {stamp->
        val loaded=withContext(Dispatchers.IO){manager.state(stamp.chat)}
        current(stamp);replace(loaded)
    }
    fun choose(framing:OcFraming,allow:Boolean)=task("取景选择未能保存，请刷新后重试") {stamp->
        val loaded=withContext(Dispatchers.IO){manager.chooseFraming(stamp.chat,framing,allow)}
        current(stamp);replace(loaded)
    }
    fun requestGeneration() {
        if(!busy&&chat!=null&&mayGenerate())generationConfirmation=true
    }
    fun cancelGeneration(){generationConfirmation=false}
    fun confirmGeneration() {
        if(!generationConfirmation||busy||!mayGenerate())return
        generationConfirmation=false
        // A lost response must not leave an old enabled paid action in this view.
        view=view?.copy(canGenerate=false)
        task("补全暂未完成，请刷新查看；结果未确认时不要重复提交") {stamp->
            val loaded=withContext(Dispatchers.IO){manager.generate(stamp.chat)}
            current(stamp);replace(loaded)
        }
    }
    private fun mayGenerate()=view?.let {it.configured&&it.canGenerate&&it.candidate==null}==true
    fun showPreview() {
        val row=view?.candidate?:return
        val sha=row.imageSha?:return
        task("补全图片暂时无法预览，请刷新后重试") {stamp->
            releasePreview()
            val raw=withContext(Dispatchers.IO){manager.preview(stamp.chat,row.requestId).also {
                Image2Protocol.imageMime(it);require(ChatPhotoRules.sha(it)==sha)
            }}
            current(stamp)
            val bitmap=ownedOcBitmap{decoder.decode(raw)}
            var adopted=false
            try {
                current(stamp)
                require(!bitmap.isRecycled&&bitmap.width in 1..512&&bitmap.height in 1..512)
                check(view?.candidate==row)
                preview=bitmap;previewId=row.requestId;previewSha=sha;adopted=true
            } finally {if(!adopted)bitmap.recycle()}
        }
    }
    fun recheck() {
        val row=view?.candidate?.takeIf{it.canReview&&it.imageSha!=null}?:return
        task("这次复核暂未完成，候选图片仍会保留") {stamp->
            val loaded=withContext(Dispatchers.IO){manager.recheck(stamp.chat,row.requestId)}
            current(stamp);replace(loaded)
        }
    }
    fun accept() {
        if(!canAccept)return
        val row=view?.candidate?:return
        val sha=row.imageSha?:return
        task("这张补全暂未采用，请刷新后核对当前外观") {stamp->
            val loaded=withContext(Dispatchers.IO){manager.confirm(stamp.chat,row.requestId,sha)}
            current(stamp);replace(loaded)
        }
    }
    private fun replace(value:OcExpansionView) {
        value.candidate?.let {MediaRules.path(it.requestId);it.imageSha?.let(ChatPhotoRules::digest)}
        releasePreview();view=value
    }
    private data class Stamp(val chat:String,val epoch:Long,val operation:Long)
    private fun task(message:String,action:suspend(Stamp)->Unit) {
        val id=chat?:return
        if(busy||generationConfirmation)return
        val stamp=Stamp(id,epoch,++operation)
        busy=true;error=null
        job=scope.launch {
            try {current(stamp);action(stamp);current(stamp)}
            catch(failure:Exception) {
                if(failure is CancellationException)throw failure
                if(matches(stamp))error=message
            } finally {if(matches(stamp))busy=false}
        }
    }
    private fun matches(stamp:Stamp)=chat==stamp.chat&&epoch==stamp.epoch&&operation==stamp.operation
    private suspend fun current(stamp:Stamp) {
        currentCoroutineContext().ensureActive()
        if(!matches(stamp))throw CancellationException("OC reference changed")
    }
    private fun releasePreview(){preview?.recycle();preview=null;previewId=null;previewSha=null}
    fun dispose(){epoch++;operation++;job?.cancel();job=null;chat=null;releasePreview();view=null;busy=false;error=null;generationConfirmation=false}
}
