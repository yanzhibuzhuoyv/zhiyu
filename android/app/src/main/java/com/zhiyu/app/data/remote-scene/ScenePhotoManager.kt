package com.zhiyu.app.data.remotescene

import android.content.Context
import com.zhiyu.app.domain.CredentialStore
import com.zhiyu.app.data.appCredentialStore
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import java.time.Instant

/** Bound-chat management of private candidates; no charge or media ledger is ever erased. */
class ScenePhotoManager(context:Context,private val credentials:CredentialStore=appCredentialStore(context),
    private val schedule:(String,Int)->Unit={chat,version->LocalSceneStockScheduler.enqueue(context,chat,version)},
    private val pictureReady:(String)->Unit={chat->com.zhiyu.app.notifications.LocalDailyShareScheduler.pictureAvailable(context,chat)}):ScenePhotoManagement {
    private val app=context.applicationContext
    private val candidates=RemoteSceneCandidateStore(app)
    private val environment by lazy {PhoneRemoteSceneEnvironment(app)}
    private val photos by lazy {RemoteScenePhotos(environment)}

    override suspend fun page(chat:String,cursor:String?):ScenePhotoPage=withContext(Dispatchers.IO) {
        validateChat(chat)
        LocalImageJobStore(app).use {ledger->
            val page=ledger.scenePage(chat,30,cursor)
            ScenePhotoPage(page.items.map {row(ledger,it)},page.nextCursor)
        }
    }
    private fun row(ledger:LocalImageJobStore,stored:StoredImageJob):ScenePhotoRow {
        val job=stored.job
        val candidate=try {candidates.read(job.requestId)}
            catch(_:IllegalArgumentException){return invalid(job)}
            catch(_:IllegalStateException){return invalid(job)}
        if(candidate!=null && candidate.chat!=job.chatId)return invalid(job)
        val removed=candidate?.pixelsRemoved==true
        val inFlight=job.state in setOf("reserved","running")
        val status=when {removed->"removed";candidate!=null&&!inFlight->"review_${candidate.status.name}";else->job.state}
        return ScenePhotoRow(job.requestId,job.dayId,candidate?.slot,status,
            candidate!=null&&!removed&&!inFlight&&candidate.originalId!=null,
            !removed&&job.state=="failed"&&ledger.retryFor(job.requestId)==null&&ledger.retrySource(job.requestId)==null,
            candidate!=null&&!inFlight,candidate!=null&&!removed)
    }
    private fun invalid(job:LocalGenerationJob)=ScenePhotoRow(job.requestId,job.dayId,null,"invalid",false,false,false,false)

    override suspend fun preview(chat:String,requestId:String):ByteArray=withContext(Dispatchers.IO) {
        ownedJob(chat,requestId)
        val candidate=ownedCandidate(chat,requestId)
        require(!candidate.pixelsRemoved){"这张候选的临时图片已清理"}
        candidates.bytes(candidate)
    }
    override suspend fun clear(chat:String,requestId:String):Unit=withContext(Dispatchers.IO) {
        val job=ownedJob(chat,requestId)
        require(job.job.state !in setOf("reserved","running")){"这张照片正在准备，请稍后再整理"}
        val candidate=ownedCandidate(chat,requestId)
        candidates.clearPixels(chat,requestId,candidate.imageSha)
        refreshStock(chat,job.job.expectedVersion)
    }
    override suspend fun recheck(chat:String,requestId:String) {
        val input=withContext(Dispatchers.IO) {
            val job=ownedJob(chat,requestId)
            require(job.job.state !in setOf("reserved","running")){"这张照片正在准备，请稍后再审"}
            val candidate=ownedCandidate(chat,requestId)
            require(!candidate.pixelsRemoved){"临时图片已清理，不能重新审图"}
            target(chat,job.job)
        }
        finish(chat,input.profile.visualVersion,photos.recheck(chat,input.target,input.profile,input.snapshot,requestId))
    }
    override suspend fun retryFailed(chat:String,requestId:String) {
        val input=withContext(Dispatchers.IO) {
            val job=ownedJob(chat,requestId)
            require(job.job.state=="failed"){"结果尚未明确的请求不能重新生成"}
            LocalImageJobStore(app).use {require(it.retryFor(requestId)==null&&it.retrySource(requestId)==null){"这张照片已用过重试机会"}}
            target(chat,job.job)
        }
        finish(chat,input.profile.visualVersion,photos.retryFailed(chat,input.target,input.profile,input.snapshot,requestId))
    }
    private fun ownedJob(chat:String,id:String):StoredImageJob {
        validateChat(chat);MediaRules.path(id)
        return LocalImageJobStore(app).use {ledger->
            requireNotNull(ledger.get(id)){"这张照片的生成记录不存在"}.also {
                require(it.job.chatId==chat&&it.job.kind=="scene"){"这张候选不属于当前恋人"}
            }
        }
    }
    private fun ownedCandidate(chat:String,id:String)=requireNotNull(candidates.read(id)){"候选图片尚未保存"}.also {
        require(it.chat==chat){"这张候选不属于当前恋人"}
    }
    private fun validateChat(chat:String) {
        MediaRules.path(chat)
        val binding=requireNotNull(credentials.readBinding()){"请先进入当前恋人的聊天"}
        val owner="companion-"+LocalMemoryCodec.sha("${binding.providerId}\u0000${binding.model}".toByteArray())
        LocalChatDatabase(app).use {require(it.findConversation(owner)?.id==chat){"这段聊天不属于当前恋人"}}
    }
    private data class Input(val target:LocalScenePrefetchTarget,val profile:VisualProfile,val snapshot:OcIdentitySnapshot?)
    private suspend fun target(chat:String,job:LocalGenerationJob):Input {
        val profile=LocalVisualRepository(app).profile(chat)
        require(job.expectedVersion==profile.visualVersion){"恋人外观已更新，旧候选不会再使用"}
        val snapshot=OcCreatorStore(app).read(chat)?.takeIf {it.identity.reference?.id==profile.referenceId&&
            it.identity.reference?.visualVersion==profile.visualVersion}
        val targets=LocalDailyRepository(app).use {daily->LocalScenePrefetchRules.targets(daily.horizon(chat,profile.visualVersion,Instant.now()),Instant.now())}
            .filter {it.scene.dayId==job.dayId}
        require(targets.isNotEmpty()){"这段日常已过，不会重新发送"}
        val binding=environment.freeze(chat,targets.first(),profile,snapshot)
            ?:throw IllegalStateException("请先确认原图和照片服务设置；暂停联系期间不准备生活照")
        val parent=LocalImageJobStore(app).use {it.retrySource(job.requestId)}?:job.requestId
        val target=targets.singleOrNull {parent in listOf(RemoteSceneRules.requestId(binding,it,0),RemoteSceneRules.requestId(binding,it,1))}
        return Input(requireNotNull(target){"角色或轨迹已更新，旧候选不会再使用"},profile,snapshot)
    }
    private fun finish(chat:String,version:Int,result:LocalSceneStockResult) {
        if(result.prepared>0||!result.deferred) {
            if(result.prepared>0)pictureReady(chat)
            refreshStock(chat,version)
            return
        }
        if(result.reason in setOf("correction_pending","rejected","review_unavailable","review_unknown"))refreshStock(chat,version)
        throw IllegalStateException("这张照片还没有确认可用，候选与生成记录已保留")
    }
    private fun refreshStock(chat:String,version:Int) {
        try {schedule(chat,version)}
        catch(_:IllegalStateException){android.util.Log.w("ZhiYuPhotos","Photo stock refresh deferred")}
    }
}
