package com.zhiyu.app.data.ocexpansion

import android.content.Context
import com.zhiyu.app.data.*
import com.zhiyu.app.data.remotescene.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*

class PhoneOcExpansionManager(context: Context, private val credentials: CredentialStore = appCredentialStore(context),
    private val schedule: (String,Int)->Unit = { chat, version -> LocalSceneStockScheduler.enqueue(context,chat,version) }): OcExpansionManagement {
    private val app=context.applicationContext
    private val visual=LocalVisualRepository(app)
    private val framing=OcFramingStore(app)
    private val media=LocalMediaStore(app)
    private val characters=OcCreatorStore(app)
    private val keys=RemoteImageCredentialStore(app)
    private val environment by lazy {PhoneOcExpansionEnvironment(app,credentials)}
    private val service by lazy {OcExpansionService(environment)}
    override suspend fun state(chat:String):OcExpansionView=withContext(Dispatchers.IO) {
        validateChat(chat)
        val profile=visual.profile(chat)
        val policy=framing.active(chat,profile)
        val configured=keys.configuration()!=null
        if(policy==null)return@withContext OcExpansionView(null,null,false,configured)
        val id=OcExpansionRules.requestId(chat,policy)
        val job=LocalImageJobStore(app).use{it.get(id)}
        val candidate=environment.candidate(id)
        val row=if(job==null) {
            if(candidate==null)null else OcExpansionRow(id,null,"invalid",false,false)
        } else {
            require(job.job.chatId==chat&&job.job.kind=="expansion") {"补全任务归属无效"}
            val terminal=job.job.state !in setOf("reserved","running")
            val frozen=if(configured&&candidate!=null)environment.freeze(chat)else null
            val valid=candidate!=null&&!candidate.pixelsRemoved&&candidate.chat==chat&&candidate.trajectory==OcFramingRules.hash(policy)&&
                candidate.originalId==policy.original.id&&candidate.originalSha==policy.original.sha256&&candidate.visualVersion==profile.visualVersion
            val canConfirm=valid&&terminal&&frozen!=null&&OcExpansionRules.matches(frozen,checkNotNull(candidate))&&
                candidate.status==LifePhotoReviewStatus.APPROVED&&(candidate.reviewBindingSha?:candidate.bindingSha)==OcExpansionRules.bindingSha(frozen)
            OcExpansionRow(id,if(valid)candidate.imageSha else null,if(valid&&terminal)"review_${candidate.status.name}" else job.job.state,
                valid&&terminal&&configured,canConfirm)
        }
        OcExpansionView(policy,row,configured&&OcFramingRules.canExpand(policy)&&job==null&&candidate==null&&environment.hasCapacity(),configured)
    }
    override suspend fun chooseFraming(chat:String,framing:OcFraming,allowExpansion:Boolean):OcExpansionView {
        val version=withContext(Dispatchers.IO) {
            validateChat(chat)
            visual.withCurrentProfile(chat){profile->
                require(profile.mode!="none"&&profile.referenceId!=null) {"请先导入或生成恋人原图"}
                val old=this@PhoneOcExpansionManager.framing.active(chat,profile)
                if(old?.framing==framing&&old.allowExpansion==allowExpansion)return@withCurrentProfile profile.visualVersion
                require(old==null||old.revision<Int.MAX_VALUE) {"取景修订数已满"}
                val original=old?.original?:source(chat,profile)
                val next=OcFramingPolicy(original,framing,allowExpansion,(old?.revision?.plus(1)?:0),profile.visualVersion,
                    profileReferenceId=profile.referenceId)
                check(this@PhoneOcExpansionManager.framing.compareAndSet(chat,profile,old,next)) {"原参考或取景已更新，请重新打开"}
                profile.visualVersion
            }
        }
        refresh(chat,version)
        return state(chat)
    }
    private fun source(chat:String,profile:VisualProfile):OcAssetRef {
        val snapshot=characters.read(chat)?.takeIf {it.identity.reference?.id==profile.referenceId&&it.identity.reference?.visualVersion==profile.visualVersion}
        val original=snapshot?.identity?.photoSource?:media.details(checkNotNull(profile.referenceId)).attachment.let {
            OcAssetRef(it.id,it.sha256,it.visualVersion)}
        media.download(original.id,original.sha256)
        return original
    }
    override suspend fun generate(chat:String):OcExpansionView {
        withContext(Dispatchers.IO){validateChat(chat)}
        service.generate(chat)
        return state(chat)
    }
    override suspend fun recheck(chat:String,requestId:String):OcExpansionView {
        withContext(Dispatchers.IO){owned(chat,requestId)}
        service.recheck(chat,requestId)
        return state(chat)
    }
    override suspend fun preview(chat:String,requestId:String):ByteArray=withContext(Dispatchers.IO) {
        owned(chat,requestId)
        val candidate=requireNotNull(environment.candidate(requestId)) {"补全图片尚未保存"}
        require(candidate.chat==chat) {"补全图片不属于当前恋人"}
        environment.bytes(candidate)
    }
    override suspend fun confirm(chat:String,requestId:String,expectedSha:String):OcExpansionView {
        withContext(Dispatchers.IO){owned(chat,requestId)}
        check(service.confirm(chat,requestId,expectedSha)) {"原参考或取景已经更新，当前形象已保留"}
        refresh(chat,visual.profile(chat).visualVersion)
        return state(chat)
    }
    private fun owned(chat:String,id:String) {
        validateChat(chat);MediaRules.path(id)
        val profile=visual.withCurrentProfile(chat){it}
        val policy=requireNotNull(framing.active(chat,profile)) {"请先确认原取景"}
        require(OcExpansionRules.requestId(chat,policy)==id) {"原参考或取景已经更新"}
        val job=LocalImageJobStore(app).use{it.get(id)}
        require(job?.job?.chatId==chat&&job.job.kind=="expansion"&&job.job.expectedVersion==profile.visualVersion) {"补全任务归属无效"}
    }
    private fun validateChat(chat:String) {
        MediaRules.path(chat)
        val binding=requireNotNull(credentials.readBinding()) {"请先进入当前恋人的聊天"}
        val owner="companion-"+LocalMemoryCodec.sha("${binding.providerId}\u0000${binding.model}".toByteArray())
        LocalChatDatabase(app).use {require(it.findConversation(owner)?.id==chat) {"这段聊天不属于当前恋人"}}
    }
    private fun refresh(chat:String,version:Int) {
        try{schedule(chat,version)}catch(_:IllegalStateException){android.util.Log.w("ZhiYuOc","Daily photo preparation deferred")}
    }
}
