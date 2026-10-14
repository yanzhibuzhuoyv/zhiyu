package com.zhiyu.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.NativeV4Context
import com.zhiyu.app.domain.planar.*
import com.zhiyu.app.ui.screens.PlanarWorkshopDialog
import com.zhiyu.app.ui.screens.PlanarDocumentPicker
import com.zhiyu.app.ui.theme.ZhiYuTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Synthetic isolated app files only; no API/provider/microphone/user database. */
class PlanarWorkshopNativeTest {
    @get:Rule val compose=createComposeRule()
    private val base=InstrumentationRegistry.getInstrumentation().targetContext
    private val context=NativeV4Context(base)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val controller=PlanarWorkshopController(context,scope)
    private val binding="synthetic-chat:"+"a".repeat(64)
    private fun photo()=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).also {it.eraseColor(0xfff0dede.toInt())}
    @After fun cleanup(){compose.runOnUiThread{controller.dispose();scope.cancel()};context.close()
        val root=context.filesDir.canonicalFile;check(root.parentFile==base.filesDir.canonicalFile&&root.name.startsWith("v4_"));root.deleteRecursively()}
    private fun prepare(){
        compose.runOnIdle{controller.connect(binding)};compose.waitUntil(5000){!controller.busy}
        compose.runOnIdle{controller.open {photo()}};compose.waitUntil(5000){controller.draft!=null&&!controller.busy}
        compose.setContent{ZhiYuTheme{PlanarWorkshopDialog(controller,"合成角色") {}}}
    }
    @Test fun saveReopenAndUndoRedoKeepDraftAndOriginalSource(){
        prepare();val original=controller.draft!!.anchors
        compose.runOnIdle{controller.edit(controller.draft!!.copy(anchors=original.copy(head=PlanarPoint(31.0,17.0))))}
        compose.onNodeWithTag("planar_undo").performClick();assertEquals(original,controller.draft!!.anchors)
        compose.onNodeWithTag("planar_redo").performClick();assertEquals(PlanarPoint(31.0,17.0),controller.draft!!.anchors.head)
        compose.onNodeWithTag("planar_save").performClick();compose.waitUntil(10000){controller.active!=null&&!controller.busy}
        assertNull(controller.error);assertFalse(controller.dirty);assertTrue(controller.canUndo)
        assertEquals(0xfff0dede.toInt(),controller.source!!.getPixel(0,0))
        val reopened=PlanarWorkshopController(context,scope)
        try{compose.runOnIdle{reopened.connect(binding)};compose.waitUntil(5000){reopened.active!=null&&!reopened.busy}
            compose.runOnIdle{reopened.open {throw AssertionError("reopen must use persisted source")}}
            compose.waitUntil(5000){reopened.draft!=null&&!reopened.busy}
            assertEquals(controller.draft!!.anchors,reopened.draft!!.anchors);assertFalse(reopened.canUndo)
        }finally{compose.runOnUiThread{reopened.dispose()}}
    }
    @Test fun busyIsPublishedSynchronouslyToPreventDoubleSaveAndLostEditorOpen(){
        compose.runOnIdle {controller.connect(binding);assertTrue(controller.busy)}
        compose.waitUntil(5000){!controller.busy}
        val held=CompletableDeferred<Bitmap?>()
        compose.runOnIdle {controller.open {held.await()};assertTrue(controller.busy);controller.open {throw AssertionError("double open")}}
        held.complete(photo());compose.waitUntil(5000){controller.source!=null&&!controller.busy}
    }
    @Test fun invalidAccessorySaveKeepsPreviousActiveAndDraftForCorrection(){
        prepare();compose.onNodeWithTag("planar_save").performClick();compose.waitUntil(10000){controller.active!=null&&!controller.busy}
        val saved=controller.active
        compose.onNodeWithTag("planar_add_part").performClick();compose.onNodeWithTag("planar_save").performClick()
        compose.waitUntil(5000){controller.error!=null&&!controller.busy}
        assertSame(saved,controller.active);assertEquals(1,controller.draft!!.parts.size);assertTrue(controller.dirty)
        compose.onNodeWithTag("planar_undo").performClick();assertTrue(controller.draft!!.parts.isEmpty())
    }
    @Test fun cancelledLoadingCannotAttachAnOldPhotoToNewBinding(){
        compose.runOnIdle{controller.connect(binding)};compose.waitUntil(5000){!controller.busy}
        val held=CompletableDeferred<Bitmap?>()
        compose.runOnIdle{controller.open {held.await()}}
        compose.waitUntil(5000){controller.busy}
        compose.runOnIdle{controller.connect("synthetic-chat:"+"b".repeat(64))}
        val incoming=photo();held.complete(incoming);compose.waitUntil(5000){!controller.busy}
        assertNull(controller.source);assertNull(controller.draft);assertNull(controller.active);incoming.recycle()
    }
    @Test fun stalePickerAndCallBindingCannotOperateOnNewOc(){
        prepare();compose.onNodeWithTag("planar_save").performClick();compose.waitUntil(10000){controller.active!=null&&!controller.busy}
        val token=controller.bindingRevision
        assertNotNull(controller.forBinding(binding));assertNull(controller.forBinding("other"))
        compose.runOnIdle{controller.connect("synthetic-chat:"+"b".repeat(64))};compose.waitUntil(5000){!controller.busy}
        compose.runOnIdle{controller.import(Uri.parse("content://unused/stale"),token)}
        assertEquals("形象已更新，请重新选择角色包",controller.error);assertNull(controller.source)
        compose.runOnIdle{controller.export(Uri.parse("content://unused/stale"),token)}
        assertEquals("形象已更新，请重新选择导出位置",controller.error);assertNull(controller.forBinding(binding))
    }
    @Test fun packagePickerRequestsAnOpenableDocument(){
        val types=arrayOf("application/zip","application/octet-stream")
        val intent=PlanarDocumentPicker().createIntent(context,types)
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT,intent.action)
        assertTrue(intent.hasCategory(android.content.Intent.CATEGORY_OPENABLE));assertEquals("*/*",intent.type)
        assertArrayEquals(types,intent.getStringArrayExtra(android.content.Intent.EXTRA_MIME_TYPES))
    }
}
