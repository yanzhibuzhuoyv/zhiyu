package com.zhiyu.app.data.remotescene

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

class ScenePhotoManagerNativeTest {
    private class Binding:CredentialStore {
        val binding=CompanionBinding("deepseek","deepseek-v4-flash")
        override fun read():SavedConnection?=error("Page/clear never read a private API Key")
        override fun readBinding()=binding
        override fun write(connection:SavedConnection)=error("No connection mutation")
        override fun writeBinding(binding:CompanionBinding)=error("No binding mutation")
        override fun clear()=error("No credentials mutation")
    }
    private class Fixture:AutoCloseable {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val context=NativeV4Context(base)
        val credentials=Binding()
        val db=LocalChatDatabase(context)
        val owner="companion-"+LocalMemoryCodec.sha("deepseek\u0000deepseek-v4-flash".toByteArray())
        val chat=db.ensureConversation(owner,credentials.binding.model,null).id
        val jobs=LocalImageJobStore(context)
        val candidates=RemoteSceneCandidateStore(context)
        val media=LocalMediaStore(context)
        val manager=ScenePhotoManager(context,credentials,schedule={_,_->},pictureReady={})
        val png=pixels()
        fun record(id:String="a".repeat(32),state:String="unknown",chatId:String=chat,kind:String="scene"):RemoteSceneCandidate {
            val day=LocalDailyRules.dayId(Instant.now())
            jobs.reserve(LocalGenerationJob(id,chatId,kind,0,day,"b".repeat(64),"reserved"));jobs.finish(id,state)
            return RemoteSceneCandidate(id,chatId,"c".repeat(64),ChatPhotoRules.sha(png),"image/png",
                "Same original OC at the window.",0,day,"12:00","d".repeat(64)).also {candidates.write(it,png)}
        }
        override fun close(){jobs.close();db.close();context.close();val root=context.filesDir.canonicalFile
            check(root.parentFile==base.filesDir.canonicalFile&&root.name.startsWith("v4_"));check(root.deleteRecursively())}
        companion object {fun pixels():ByteArray {val bitmap=Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888)
            return try {bitmap.eraseColor(0xffbc8579.toInt());ByteArrayOutputStream().also {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()}
            finally{bitmap.recycle()}}}
    }
    @Test fun pageOnlyShowsBoundChatSceneRowsWithoutReadingAKey()=runBlocking<Unit> {
        Fixture().use {f->val own=f.record();f.record("e".repeat(32),chatId="f".repeat(32));f.record("1".repeat(32),kind="oc")
            val page=f.manager.page(f.chat)
            assertEquals(listOf(own.requestId),page.items.map{it.requestId});assertNull(page.nextCursor)
            assertFalse(page.items.single().canRetry);assertTrue(page.items.single().hasPicture)
            assertThrows(IllegalArgumentException::class.java){runBlocking{f.manager.page("f".repeat(32))}}}
    }
    @Test fun clearingUnknownCandidateKeepsTheChargeRecordAndAllPublishedOriginals()=runBlocking<Unit> {
        Fixture().use {f->val value=f.record();val published=f.media.ingest("image",f.png,"synthetic original")
            val ledger=f.jobs.get(value.requestId)
            f.manager.clear(f.chat,value.requestId)
            assertTrue(requireNotNull(f.candidates.read(value.requestId)).pixelsRemoved)
            assertEquals(ledger,f.jobs.get(value.requestId))
            assertArrayEquals(f.png,f.media.download(published.id,published.sha256))
            val row=f.manager.page(f.chat).items.single();assertEquals("removed",row.status)
            assertFalse(row.canReview||row.canRetry||row.hasPicture)
            assertThrows(IllegalArgumentException::class.java){runBlocking{f.manager.preview(f.chat,value.requestId)}}}
    }
    @Test fun previewRequiresTheOwnedSceneAndReturnsOnlyItsUnchangedPixels()=runBlocking<Unit> {
        Fixture().use {f->val own=f.record();val other=f.record("e".repeat(32),chatId="f".repeat(32))
            assertArrayEquals(f.png,f.manager.preview(f.chat,own.requestId))
            assertThrows(IllegalArgumentException::class.java){runBlocking{f.manager.preview(f.chat,other.requestId)}}
            assertThrows(IllegalArgumentException::class.java){runBlocking{f.manager.clear(f.chat,other.requestId)}}
            assertFalse(requireNotNull(f.candidates.read(other.requestId)).pixelsRemoved)}
    }
    @Test fun unknownAndRunningRequestsCannotBecomeNewPaidRetries()=runBlocking<Unit> {
        Fixture().use {f->val unknown=f.record()
            assertThrows(IllegalArgumentException::class.java){runBlocking{f.manager.retryFailed(f.chat,unknown.requestId)}}
            assertEquals(1,f.jobs.list(f.chat).size);assertEquals("unknown",f.jobs.get(unknown.requestId)?.job?.state)}
    }
}
