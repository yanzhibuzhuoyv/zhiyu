package com.zhiyu.app.data.ocexpansion

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.*
import com.zhiyu.app.data.remotescene.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class OcFramingPhoneNativeTest {
    private class Binding: CredentialStore {
        val binding = CompanionBinding("deepseek","deepseek-v4-flash")
        var synthetic:SavedConnection?=null
        override fun read():SavedConnection? = synthetic?:error("Framing settings cannot read a private chat key")
        override fun readBinding() = binding
        override fun write(connection:SavedConnection) = error("No connection mutation")
        override fun writeBinding(binding:CompanionBinding) = error("No companion mutation")
        override fun clear() = error("No credentials mutation")
    }
    private class Fixture: AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = NativeV4Context(base)
        val credentials = Binding()
        val db = LocalChatDatabase(context)
        val owner = "companion-"+LocalMemoryCodec.sha("deepseek\u0000deepseek-v4-flash".toByteArray())
        val chat = db.ensureConversation(owner,credentials.binding.model,null).id
        val visual = LocalVisualRepository(context)
        val manager = PhoneOcExpansionManager(context,credentials,schedule={_,_->})
        val store = OcFramingStore(context)
        val png:ByteArray = Bitmap.createBitmap(48,64,Bitmap.Config.ARGB_8888).let { bitmap ->
            try {bitmap.eraseColor(0xffbd9480.toInt());ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()} finally {bitmap.recycle()}}
        override fun close() {db.close();context.close();val root=context.filesDir.canonicalFile
            check(root.parentFile==base.filesDir.canonicalFile&&root.name.startsWith("v4_"));check(root.deleteRecursively())}
    }
    @Test fun choosingFramingPersistsAcrossRestartWithoutChangingVideoReferenceOrReadingChatKey() = runBlocking<Unit> {
        Fixture().use { f ->
            val profile=f.visual.import(f.chat,f.png)
            val state=f.manager.chooseFraming(f.chat,OcFraming.BUST,true)
            assertEquals(OcFraming.BUST,state.policy?.framing)
            assertEquals(profile,f.visual.profile(f.chat))
            assertEquals(state.policy,OcFramingStore(f.context).active(f.chat,profile))
            assertArrayEquals(f.png,LocalMediaStore(f.context).download(profile.referenceId!!))
            assertFalse(state.configured);assertFalse(state.canGenerate)
        }
    }
    @Test fun optOutOnlyAdvancesFramingRevisionAndNeverRevokesTheVideoImage() = runBlocking<Unit> {
        Fixture().use { f ->
            val profile=f.visual.import(f.chat,f.png)
            val before=checkNotNull(f.manager.chooseFraming(f.chat,OcFraming.HEAD,true).policy)
            val after=checkNotNull(f.manager.chooseFraming(f.chat,OcFraming.HEAD,false).policy)
            assertEquals(before.revision+1,after.revision);assertFalse(after.allowExpansion)
            assertEquals(profile,f.visual.profile(f.chat));assertEquals(before.original,after.original)
        }
    }
    @Test fun reimportingAnOriginalInvalidatesOldFramingInsteadOfApplyingItToNewOc() = runBlocking<Unit> {
        Fixture().use { f ->
            f.visual.import(f.chat,f.png);f.manager.chooseFraming(f.chat,OcFraming.BUST,false)
            f.visual.import(f.chat,f.png)
            assertNull(f.manager.state(f.chat).policy)
            assertNull(f.store.active(f.chat,f.visual.profile(f.chat)))
        }
    }
    @Test fun wrongChatCannotChangeAnotherPersonsFraming() = runBlocking<Unit> {
        Fixture().use { f ->
            f.visual.import(f.chat,f.png)
            assertThrows(IllegalArgumentException::class.java) {runBlocking {f.manager.chooseFraming("f".repeat(32),OcFraming.BUST,true)}}
            assertNull(f.manager.state(f.chat).policy)
        }
    }
    @Test fun staleCompareAndSetDoesNotOverwriteCurrentOriginalOrAcceptedReference() = runBlocking<Unit> {
        Fixture().use { f ->
            val profile=f.visual.import(f.chat,f.png)
            val first=checkNotNull(f.manager.chooseFraming(f.chat,OcFraming.BUST,true).policy)
            val second=checkNotNull(f.manager.chooseFraming(f.chat,OcFraming.BUST,false).policy)
            assertFalse(f.store.compareAndSet(f.chat,profile,first,first.copy(revision=first.revision+1)))
            assertEquals(second,f.store.active(f.chat,profile))
        }
    }
    @Test fun ledgerSeparatesExpansionFromTheTwoDescriptionOpportunities() = runBlocking<Unit> {
        Fixture().use { f ->
            LocalImageJobStore(f.context).use { jobs ->
                repeat(2) { index ->val id=(index+1).toString().repeat(32)
                    jobs.reserve(LocalGenerationJob(id,f.chat,"oc",0,"2026-10-15","b".repeat(64),"reserved"));jobs.finish(id,"success")}
                jobs.reserve(LocalGenerationJob("c".repeat(32),f.chat,"expansion",0,"2026-10-15","d".repeat(64),"reserved"))
                jobs.finish("c".repeat(32),"unknown")
                val quota=LocalGenerationRules.quota(jobs.list(f.chat),true)
                assertEquals(2,quota.used);assertEquals(0,quota.pending);assertEquals(0,quota.remaining)
                assertEquals("unknown",jobs.get("c".repeat(32))?.job?.state)
            }
        }
    }
    @Test fun replyPreparationAndFinalCommitRejectOldPeopleAfterOnlyFramingChanges() = runBlocking<Unit> {
        Fixture().use { f ->
            val profile=f.visual.import(f.chat,f.png)
            val now=Instant.parse("2026-10-15T06:00:00Z")
            val scene=LocalDailyRepository(f.context).use {it.scene(f.chat,profile.visualVersion,now)}
            val image=LocalMediaStore(f.context).ingest("image",f.png,"Synthetic previously reviewed picture",profile.visualVersion,
                scene.dayId,tags=scene.current.tags+"contains_person")
            val reply=LocalReplyMedia(f.context){now}
            val plan=LocalMediaPlan("",listOf(image),"synthetic_nonce")
            val text="在这里。 [[ZHIYU_MEDIA_synthetic_nonce:${image.id}]]"
            assertEquals(listOf(image),reply.commit(f.chat,plan,text){it}.attachments)
            f.manager.chooseFraming(f.chat,OcFraming.BUST,false)
            assertEquals(profile,f.visual.profile(f.chat))
            assertTrue(reply.commit(f.chat,plan,text){it}.attachments.isEmpty())
            assertFalse(reply.prepare(f.chat,null,emptyList()).candidates.any {it.id==image.id})
        }
    }
    @Test fun dailyShareFinalCommitRejectsOldCropProofEvenWhenVisualVersionAndTrajectoryStayIdentical() = runBlocking<Unit> {
        Fixture().use { f ->
            val profile=f.visual.import(f.chat,f.png)
            val scene=LocalDailyRepository(f.context).use {it.scene(f.chat,profile.visualVersion,Instant.parse("2026-10-15T06:00:00Z"))}
            val slot=scene.slots[1]
            val due=LocalDate.parse(scene.dayId).atTime(LocalTime.parse(slot.time)).atZone(ZoneId.of(LocalDailyRules.TIMEZONE)).toInstant()
            val event=LocalDailyShareEvent(UUID.randomUUID().toString(),scene.dayId,scene.sceneId,LocalDailyRules.revision(scene),
                scene.visualVersion,slot,due,due.plusSeconds(1800))
            val tags=slot.tags+listOf("daily_stock","decoded_final","composite_reviewed","qwen_generated","contains_person",
                "slot_${slot.time.replace(":","")}","trajectory_${event.revision.take(24)}")
            val image=LocalMediaStore(f.context).ingest("image",f.png,"Synthetic previously reviewed full picture",profile.visualVersion,scene.dayId,tags=tags)
            val selector=LocalDailyShareMedia(f.context){due.plusSeconds(5)}
            assertEquals(image,selector.prepare(f.chat,event,emptySet()))
            f.manager.chooseFraming(f.chat,OcFraming.HEAD,false)
            assertEquals(profile,f.visual.profile(f.chat))
            assertNull(selector.prepare(f.chat,event,emptySet()))
            var saved=false
            assertThrows(IllegalArgumentException::class.java){selector.validateAndCommit(f.chat,event,image){saved=true}}
            assertFalse(saved)
        }
    }
    private data class Prepared(val profile:VisualProfile,val snapshot:OcIdentitySnapshot,val candidate:RemoteSceneCandidate,val pixels:ByteArray)
    /** A synthetic approved receipt tests local CAS only; it is not a vision-quality or cloud-generation result. */
    private suspend fun prepared(f:Fixture):Prepared {
        val imported=f.visual.import(f.chat,f.png)
        val source=LocalMediaStore(f.context).details(imported.referenceId!!).attachment.let{OcAssetRef(it.id,it.sha256,it.visualVersion)}
        val identity=OcIdentityRules.fresh("photo_subject").copy(revision=1,confirmed=true,reference=source,photoSource=source)
        val snapshot=OcCreatorStore(f.context).commit(f.chat,0,identity)
        f.manager.chooseFraming(f.chat,OcFraming.BUST,true)
        f.credentials.synthetic=SavedConnection("local://phone","deepseek","synthetic-chat-key","deepseek-v4-flash")
        RemoteImageCredentialStore(f.context).write("synthetic-image-key")
        val environment=PhoneOcExpansionEnvironment(f.context,f.credentials)
        val binding=checkNotNull(environment.freeze(f.chat))
        val pixels=Bitmap.createBitmap(48,96,Bitmap.Config.ARGB_8888).let{bitmap->
            try {bitmap.eraseColor(0xff9389b1.toInt());ByteArrayOutputStream().also{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()}
            finally{bitmap.recycle()}}
        val id=OcExpansionRules.requestId(binding);val day=LocalDailyRules.dayId(Instant.now())
        LocalImageJobStore(f.context).use{jobs->jobs.reserve(LocalGenerationJob(id,f.chat,"expansion",imported.visualVersion,day,
            OcExpansionRules.metadataHash(id),"reserved"));jobs.finish(id,"success")}
        val candidate=RemoteSceneCandidate(id,f.chat,OcExpansionRules.bindingSha(binding),ChatPhotoRules.sha(pixels),"image/png",
            "Synthetic prepared extension; local CAS fixture only.",0,day,"00:00",OcFramingRules.hash(binding.policy),
            status=LifePhotoReviewStatus.APPROVED,reason="synthetic_fixture_not_quality",originalId=source.id,originalSha=source.sha256,
            visualVersion=imported.visualVersion,packageSha=snapshot.packageSha)
        environment.save(candidate,pixels)
        return Prepared(f.visual.profile(f.chat),snapshot,candidate,pixels)
    }
    @Test fun explicitConfirmationAddsOnlyAPortraitReferenceAndKeepsOriginalVideoAndCharacterBytes()=runBlocking<Unit> {
        Fixture().use{f->val ready=prepared(f)
            assertTrue(f.manager.state(f.chat).candidate?.canConfirm==true)
            assertArrayEquals(ready.pixels,f.manager.preview(f.chat,ready.candidate.requestId))
            val chosen=f.manager.confirm(f.chat,ready.candidate.requestId,ready.candidate.imageSha)
            val accepted=checkNotNull(chosen.policy?.accepted)
            assertEquals(ready.profile,f.visual.profile(f.chat));assertEquals(ready.snapshot,OcCreatorStore(f.context).read(f.chat))
            assertArrayEquals(f.png,LocalMediaStore(f.context).download(ready.profile.referenceId!!))
            assertArrayEquals(ready.pixels,LocalMediaStore(f.context).download(accepted.id,accepted.sha256))
            assertEquals("portrait",LocalMediaStore(f.context).details(accepted.id).purpose)
            assertFalse(LocalMediaStore(f.context).list("image").any{it.id==accepted.id})
            assertFalse(chosen.canGenerate)
        }
    }
    @Test fun changedImageKeyRejectsPreviouslyApprovedPixelsWithoutChangingOriginalOrCharging()=runBlocking<Unit> {
        Fixture().use{f->val ready=prepared(f)
            RemoteImageCredentialStore(f.context).write("different-synthetic-image-key")
            assertFalse(f.manager.state(f.chat).candidate?.canConfirm==true)
            assertThrows(IllegalArgumentException::class.java){runBlocking{f.manager.confirm(f.chat,ready.candidate.requestId,ready.candidate.imageSha)}}
            assertNull(f.manager.state(f.chat).policy?.accepted)
            assertEquals(ready.profile,f.visual.profile(f.chat));assertEquals(ready.snapshot,OcCreatorStore(f.context).read(f.chat))
            LocalImageJobStore(f.context).use{assertEquals(1,it.list(f.chat).size)}
        }
    }
    @Test fun replacingOriginalAfterPreviewRejectsOldCandidateInsteadOfAdoptingIt()=runBlocking<Unit> {
        Fixture().use{f->val ready=prepared(f)
            f.manager.preview(f.chat,ready.candidate.requestId)
            f.visual.import(f.chat,f.png)
            assertThrows(IllegalArgumentException::class.java){runBlocking{f.manager.confirm(f.chat,ready.candidate.requestId,ready.candidate.imageSha)}}
            assertNull(f.manager.state(f.chat).policy)
            assertEquals(ready.profile.visualVersion+1,f.visual.profile(f.chat).visualVersion)
            assertEquals(ready.snapshot,OcCreatorStore(f.context).read(f.chat))
            LocalImageJobStore(f.context).use{assertEquals(1,it.list(f.chat).size)}
        }
    }
}
