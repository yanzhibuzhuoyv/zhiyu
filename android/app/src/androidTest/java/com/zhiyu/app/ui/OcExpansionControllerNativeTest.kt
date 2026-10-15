package com.zhiyu.app.ui

import android.graphics.Bitmap
import com.zhiyu.app.data.ocexpansion.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OcExpansionControllerNativeTest {
    private val chat="a".repeat(32)
    private val other="b".repeat(32)
    private class Manager:OcExpansionManagement {
        var value=OcExpansionView(null,null,true,true)
        var generated=0;var reviews=0;var confirmations=0
        var chosen:Pair<OcFraming,Boolean>?=null
        var delayed:CompletableDeferred<OcExpansionView>?=null
        var bytes=byteArrayOf(137.toByte(),80,78,71,13,10,26,10,1)
        override suspend fun state(chat:String):OcExpansionView=delayed?.let {withContext(NonCancellable){it.await()}}?:value
        override suspend fun chooseFraming(chat:String,framing:OcFraming,allowExpansion:Boolean):OcExpansionView {
            chosen=framing to allowExpansion;return value
        }
        override suspend fun generate(chat:String):OcExpansionView {generated++;return value}
        override suspend fun recheck(chat:String,requestId:String):OcExpansionView {reviews++;return value}
        override suspend fun preview(chat:String,requestId:String)=bytes.copyOf()
        override suspend fun confirm(chat:String,requestId:String,expectedSha:String):OcExpansionView {
            assertEquals(ChatPhotoRules.sha(bytes),expectedSha);confirmations++;return value
        }
    }
    private class Fixture:AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val manager=Manager()
        val decoded=mutableListOf<Bitmap>()
        val controller=OcExpansionController(manager,scope,ScenePhotoPreviewDecoder {
            Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).also {decoded.add(it)}
        })
        override fun close(){controller.dispose();scope.cancel()}
    }
    private suspend fun ready(controller:OcExpansionController){withTimeout(5000){while(controller.busy)delay(10)}}
    @Test fun openingAndChoosingNeverSilentlyGenerateAPaidImage()=runBlocking<Unit> {
        Fixture().use {f->withContext(Dispatchers.Main){f.controller.connect(chat)};ready(f.controller)
            withContext(Dispatchers.Main){f.controller.choose(OcFraming.BUST,false)};ready(f.controller)
            assertEquals(OcFraming.BUST to false,f.manager.chosen);assertEquals(0,f.manager.generated)}
    }
    @Test fun paidGenerationRequiresConfirmationAndConsumesItOnce()=runBlocking<Unit> {
        Fixture().use {f->withContext(Dispatchers.Main){f.controller.connect(chat)};ready(f.controller)
            withContext(Dispatchers.Main){f.controller.requestGeneration()}
            assertTrue(f.controller.generationConfirmation);assertEquals(0,f.manager.generated)
            withContext(Dispatchers.Main){f.controller.confirmGeneration();f.controller.confirmGeneration()};ready(f.controller)
            assertEquals(1,f.manager.generated);assertFalse(f.controller.generationConfirmation)}
    }
    @Test fun unknownResultNeverOffersAnotherPaidGeneration()=runBlocking<Unit> {
        Fixture().use {f->f.manager.value=f.manager.value.copy(candidate=OcExpansionRow("d".repeat(32),null,"unknown",false,false))
            withContext(Dispatchers.Main){f.controller.connect(chat)};ready(f.controller)
            withContext(Dispatchers.Main){f.controller.requestGeneration();f.controller.confirmGeneration()}
            assertEquals(0,f.manager.generated);assertFalse(f.controller.generationConfirmation)}
    }
    @Test fun lateOldChatReadCannotReplaceCurrentViewOrKeepItsConfirmation()=runBlocking<Unit> {
        Fixture().use {f->val old=CompletableDeferred<OcExpansionView>();f.manager.delayed=old
            withContext(Dispatchers.Main){f.controller.connect(chat)}
            f.manager.delayed=null;f.manager.value=f.manager.value.copy(configured=false)
            withContext(Dispatchers.Main){f.controller.connect(other)};ready(f.controller)
            old.complete(OcExpansionView(null,null,true,true));delay(100)
            assertFalse(requireNotNull(f.controller.view).configured);assertFalse(f.controller.generationConfirmation)}
    }
    @Test fun acceptingRequiresAnExplicitPreviewAndKeepsOnlyOneOwnedBitmap()=runBlocking<Unit> {
        Fixture().use {f->f.manager.value=f.manager.value.copy(candidate=OcExpansionRow("d".repeat(32),ChatPhotoRules.sha(f.manager.bytes),"approved",true,true))
            withContext(Dispatchers.Main){f.controller.connect(chat)};ready(f.controller)
            withContext(Dispatchers.Main){f.controller.accept()};assertEquals(0,f.manager.confirmations)
            withContext(Dispatchers.Main){f.controller.showPreview()};ready(f.controller);val first=requireNotNull(f.controller.preview)
            withContext(Dispatchers.Main){f.controller.showPreview()};ready(f.controller);assertTrue(first.isRecycled)
            withContext(Dispatchers.Main){f.controller.accept()};ready(f.controller)
            assertEquals(1,f.manager.confirmations)
            withContext(Dispatchers.Main){f.controller.connect(null)};assertTrue(f.decoded.all {it.isRecycled})}
    }
}
