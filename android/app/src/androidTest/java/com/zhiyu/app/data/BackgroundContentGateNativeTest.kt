package com.zhiyu.app.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class BackgroundContentGateNativeTest {
    private fun image(color: Int = 0xffaabbcc.toInt()): AgnesGeneratedImage {
        val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes=ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG,100,out); bitmap.recycle(); out.toByteArray() }
        return AgnesGeneratedImage(bytes,"image/png")
    }
    private val clear="{\"schema\":\"background-content-v1\",\"subject_free\":true,\"person\":false,\"body\":false,\"face\":false,\"character\":false,\"uncertain\":false}"
    private fun response(content: String)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop")
        .put("message",JSONObject().put("content",content)))).toString()

    @Test fun clearDecisionUsesIndependentInstructionsAndReceiptsBindExactBytes()=runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            var calls=0; val source=image()
            val gate=BackgroundContentGate({ "synthetic-image-key" }, AgnesVisionTransport { _, payload ->
                calls++; assertEquals(BackgroundContentGate.INSTRUCTION,JSONObject(payload).getJSONArray("messages").getJSONObject(0).getString("content"))
                response(clear)
            })
            assertTrue(gate.allows(source)); assertEquals(1,calls)
            val store=BackgroundApprovalStore(context); assertFalse(store.approved(source))
            store.approve(source); assertTrue(BackgroundApprovalStore(context).approved(source))
            assertFalse(store.approved(image(0xffccddee.toInt())))
        }
    }
    @Test fun foundCharactersUnknownAndMissingKeyCannotApproveABackground()=runBlocking {
        for (content in listOf(clear.replace("\"person\":false","\"person\":true"),"not-json")) {
            assertFalse(BackgroundContentGate({ "synthetic-image-key" },AgnesVisionTransport { _,_ -> response(content) }).allows(image()))
        }
        assertFalse(BackgroundContentGate({ null },AgnesVisionTransport { _,_ -> error("No transmission without Key") }).allows(image()))
    }
    @Test fun callerCancellationIsPreservedAndServiceFailureReturnsNoPermission()=runBlocking {
        assertFalse(BackgroundContentGate({ "synthetic-image-key" },AgnesVisionTransport { _,_ -> throw ApiFailure(503,"synthetic failure") }).allows(image()))
        try {
            BackgroundContentGate({ "synthetic-image-key" },AgnesVisionTransport { _,_ -> throw CancellationException("synthetic cancellation") }).allows(image())
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
