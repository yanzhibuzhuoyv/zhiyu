package com.zhiyu.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.NativeV4Context
import com.zhiyu.app.ui.screens.rememberPlanarEditorImage
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Rendering snapshots must survive the controller recycling its separately owned raw source. */
class PlanarDisplayOwnershipNativeTest {
    @get:Rule val compose=createComposeRule()
    private val base=InstrumentationRegistry.getInstrumentation().targetContext
    private val context=NativeV4Context(base)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val controller=PlanarWorkshopController(context,scope)
    @After fun cleanup() {
        compose.runOnUiThread {controller.dispose()};runBlocking{runCatching {controller.awaitCheckpoint()}}
        scope.cancel();context.close();val root=context.filesDir.canonicalFile
        check(root.parentFile==base.filesDir.canonicalFile&&root.name.startsWith("v4_"));root.deleteRecursively()
    }
    private fun photo(color:Int)=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).also {it.eraseColor(color)}
    @Test fun controllerDiscardDoesNotInvalidateCapturedDisplaySnapshot() {
        compose.runOnIdle{controller.connect("synthetic-display:"+"a".repeat(64))};compose.waitUntil(5000){!controller.busy}
        compose.runOnIdle{controller.open {photo(0xffc5b19a.toInt())}};compose.waitUntil(5000){!controller.busy&&controller.source!=null}
        val raw=controller.source!!;var captured:ImageBitmap?=null
        compose.setContent {val image=rememberPlanarEditorImage(raw);SideEffect {captured=image}}
        compose.waitForIdle();compose.runOnIdle{controller.discardDraft()};compose.waitUntil(5000){!controller.busy}
        assertTrue(raw.isRecycled)
        val displayed=checkNotNull(captured).asAndroidBitmap()
        assertFalse("Recorded display must never hold a controller-recycled bitmap",displayed.isRecycled)
        assertEquals(0xffc5b19a.toInt(),displayed.getPixel(0,0))
    }
    @Test fun replacingSourceKeepsPreviousDisplaySnapshotValidUntilItsViewReleasesIt() {
        val first=photo(0xffb7ac93.toInt());val second=photo(0xff8d9d89.toInt())
        var source by mutableStateOf(first);var captured:ImageBitmap?=null
        try {
            compose.setContent {val image=rememberPlanarEditorImage(source);SideEffect {captured=image}}
            compose.waitForIdle();val previous=checkNotNull(captured)
            compose.runOnIdle{source=second};compose.waitForIdle();compose.runOnIdle{first.recycle()}
            assertFalse("Previous RenderNode can still retain this snapshot",previous.asAndroidBitmap().isRecycled)
            assertEquals(0xffb7ac93.toInt(),previous.asAndroidBitmap().getPixel(0,0))
            assertEquals(0xff8d9d89.toInt(),checkNotNull(captured).asAndroidBitmap().getPixel(0,0))
        } finally {compose.runOnUiThread{if(!first.isRecycled)first.recycle();second.recycle()}}
    }
}
