package com.zhiyu.app.data.ocexpansion

import android.content.Context
import com.zhiyu.app.data.*
import com.zhiyu.app.data.remotescene.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*

internal class PhoneOcExpansionEnvironment(context: Context,
    private val credentials: CredentialStore = appCredentialStore(context)): OcExpansionEnvironment {
    private val app=context.applicationContext
    private val visual=LocalVisualRepository(app)
    private val characters=OcCreatorStore(app)
    private val framing=OcFramingStore(app)
    private val keys=RemoteImageCredentialStore(app)
    private val media=LocalMediaStore(app)
    internal val candidates=RemoteSceneCandidateStore(app,"remote_scene_expansion_v1")
    private val prompts=OcExpansionPromptClient(credentials,stillCurrent=::unchanged,normalize=OcExpansionPixels::review)
    override fun hasCapacity()=candidates.hasCapacity()
    override suspend fun freeze(chat:String):OcExpansionBinding?=withContext(Dispatchers.IO) {
        val image=keys.configuration()?:return@withContext null
        val connection=credentials.read()?:return@withContext null
        val companion=credentials.readBinding()?:return@withContext null
        if(connection.providerId!=companion.providerId||connection.model!=companion.model)return@withContext null
        visual.withCurrentProfile(chat) {profile->
            val policy=framing.active(chat,profile)?:return@withCurrentProfile null
            OcExpansionBinding(chat,policy,connection,companion,image.key,image.model,
                media.download(policy.original.id,policy.original.sha256),characters.read(chat)?.packageSha,profile.description)
        }
    }
    override suspend fun unchanged(binding:OcExpansionBinding)=withContext(Dispatchers.IO) {
        visual.withCurrentProfile(binding.chat){profile->current(binding,profile,characters.read(binding.chat))}
    }
    private fun current(binding:OcExpansionBinding,profile:VisualProfile,snapshot:OcIdentitySnapshot?):Boolean =
        framing.active(binding.chat,profile)==binding.policy && profile.description==binding.description &&
            snapshot?.packageSha==binding.characterSha && keys.configuration()==RemoteImageConfiguration(binding.imageKey,binding.imageModel) &&
            credentials.read()==binding.connection && credentials.readBinding()==binding.companion
    override fun job(id:String)=LocalImageJobStore(app).use{it.get(id)}
    override fun reserve(job:LocalGenerationJob){LocalImageJobStore(app).use{it.reserve(job)}}
    override fun finish(id:String,status:String){LocalImageJobStore(app).use{it.finish(id,status)}}
    override fun candidate(id:String)=candidates.read(id)
    override fun save(candidate:RemoteSceneCandidate,bytes:ByteArray)=candidates.write(candidate,bytes)
    override fun bytes(candidate:RemoteSceneCandidate)=candidates.bytes(candidate)
    override suspend fun prompt(binding:OcExpansionBinding)=prompts.create(binding)
    override suspend fun review(binding:OcExpansionBinding,prompt:String,image:AgnesGeneratedImage)=prompts.review(binding,prompt,image)
    override suspend fun generate(binding:OcExpansionBinding,prompt:String):AgnesGeneratedImage {
        val original=withContext(Dispatchers.IO){OcExpansionPixels.png(binding.originalBytes)}
        currentCoroutineContext().ensureActive();require(unchanged(binding)) {"原参考或服务设置已更新"}
        return RemoteImageClient(model=binding.imageModel).generate(binding.imageKey,prompt,original)
    }
    override suspend fun accept(binding:OcExpansionBinding,candidate:RemoteSceneCandidate,bytes:ByteArray):Boolean=withContext(Dispatchers.IO) {
        require(ChatPhotoRules.sha(bytes)==candidate.imageSha)
        AndroidRemoteImageBounds.validate(bytes,candidate.mime)
        val coroutine=currentCoroutineContext()
        visual.withCurrentProfile(binding.chat){profile->characters.withCurrentSnapshot(binding.chat){snapshot->
            if(!current(binding,profile,snapshot))false else candidates.withCurrentCandidate(candidate){
                coroutine.ensureActive()
                require(OcExpansionRules.matches(binding,candidate)&&candidate.status==LifePhotoReviewStatus.APPROVED&&
                    (candidate.reviewBindingSha?:candidate.bindingSha)==OcExpansionRules.bindingSha(binding)) {"补全复核已经变化"}
                val job=requireNotNull(job(candidate.requestId)).job
                require(job.kind=="expansion"&&job.chatId==binding.chat&&job.expectedVersion==profile.visualVersion&&
                    job.promptHash==OcExpansionRules.metadataHash(candidate.requestId)&&job.state !in setOf("reserved","running"))
                val asset=media.ingest("image",bytes,"你确认的 OC 完整参考",profile.visualVersion,purpose="portrait")
                val accepted=OcAssetRef(asset.id,asset.sha256,asset.visualVersion)
                check(binding.policy.revision<Int.MAX_VALUE) {"取景修订数已满"}
                framing.compareAndSet(binding.chat,profile,binding.policy,binding.policy.copy(
                    revision=binding.policy.revision+1,accepted=accepted))
            }
        }}
    }
}
