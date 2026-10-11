package com.zhiyu.app.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class LocalGenerationNativeTest {
    private val base=InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated():Context = NativeV4Context(base)
    private fun png():ByteArray {
        val image=Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff88aa77.toInt()) }
        return ByteArrayOutputStream().use { out -> image.compress(Bitmap.CompressFormat.PNG,100,out); image.recycle(); out.toByteArray() }
    }
    private fun client(count:()->Unit, result:suspend()->String):AgnesImageClient = AgnesImageClient(AgnesImageTransport { _,_-> count(); result() })
    private fun response()="{\"data\":[{\"b64_json\":\"${android.util.Base64.encodeToString(png(),android.util.Base64.NO_WRAP)}\"}]}"
    @Test fun paidCandidateReplaysAfterProfileCommitFailureWithoutAnotherRequest()=runBlocking {
        val context=isolated(); val chat="a".repeat(32); val request="b".repeat(32)
        var count=0; var commitFails=true
        val repo=LocalVisualRepository(context,client({count++},{response()}),{"test-key"},LocalImageJobStore(context,"test_${UUID.randomUUID().toString().replace("-", "")}.sqlite3"),beforeProfileCommit={ if(commitFails) throw IllegalStateException("synthetic disk failure") })
        try { repo.generate(chat,request,"单翼成年角色"); fail("应模拟写入失败") } catch(_:IllegalStateException) { }
        assertEquals(1,count); assertEquals(1,repo.profile(chat).generation.used)
        commitFails=false
        val adopted=repo.generate(chat,request,"单翼成年角色")
        assertNotNull(adopted.referenceId); assertEquals(1,count)
        assertEquals(adopted.referenceId,repo.generate(chat,request,"单翼成年角色").referenceId)
        assertEquals(1,count)
    }
    @Test fun lostResponseReservesQuotaAndCannotChargeOnReplay()=runBlocking {
        val context=isolated(); var count=0
        val repo=LocalVisualRepository(context,client({count++},{throw ApiFailure(504,"synthetic timeout","image_request_unknown")}),{"test-key"},LocalImageJobStore(context,"test_${UUID.randomUUID().toString().replace("-", "")}.sqlite3"))
        repeat(2) { try { repo.generate("a".repeat(32),"b".repeat(32),"成年角色"); fail("应失败") } catch(_:ApiFailure) {} }
        assertEquals(1,count); assertEquals(1,repo.profile("a".repeat(32)).generation.pending)
    }
    @Test fun rejectedCredentialDoesNotConsumeOpportunityAndNewRequestCanSucceed()=runBlocking {
        val context=isolated(); var reject=true; var count=0
        val repo=LocalVisualRepository(context,client({count++},{if(reject) throw ApiFailure(401,"synthetic unauthorized","image_auth_failed") else response()}),{"test-key"},LocalImageJobStore(context,"test_${UUID.randomUUID().toString().replace("-", "")}.sqlite3"))
        try { repo.generate("a".repeat(32),"b".repeat(32),"成年角色"); fail("应拒绝") } catch(_:ApiFailure) {}
        assertEquals(2,repo.profile("a".repeat(32)).generation.remaining)
        reject=false; repo.generate("a".repeat(32),"c".repeat(32),"成年角色")
        assertEquals(1,repo.profile("a".repeat(32)).generation.used); assertEquals(2,count)
    }
    @Test fun laterVisualEditSurvivesDelayedPaidResultAndCandidateRemainsAvailable()=runBlocking {
        val context=isolated(); lateinit var repo:LocalVisualRepository
        repo=LocalVisualRepository(context,client({}, { repo.update("a".repeat(32),"import","","other","long"); response() }),{"test-key"},LocalImageJobStore(context,"test_${UUID.randomUUID().toString().replace("-", "")}.sqlite3"))
        try { repo.generate("a".repeat(32),"b".repeat(32),"成年角色"); fail("应拒绝旧外观") } catch(_:ApiFailure) {}
        assertEquals("long",repo.profile("a".repeat(32)).hair)
        assertEquals(1,repo.generatedCandidates("a".repeat(32)).size)
        assertEquals(1,repo.profile("a".repeat(32)).generation.used)
    }
}
