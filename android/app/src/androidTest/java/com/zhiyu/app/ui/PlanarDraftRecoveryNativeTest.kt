package com.zhiyu.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.NativeV4Context
import com.zhiyu.app.domain.planar.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Private synthetic files. Controller recreation exercises durable reads; no real process kill is claimed. */
class PlanarDraftRecoveryNativeTest {
    @get:Rule val compose=createComposeRule()
    private val base=InstrumentationRegistry.getInstrumentation().targetContext
    private val context=NativeV4Context(base)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val controllers=mutableListOf<PlanarWorkshopController>()
    private val binding="draft-chat:"+"a".repeat(64)+":reference-7:visual-2"
    private fun controller()=PlanarWorkshopController(context,scope).also {controllers.add(it)}
    private fun photo()=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).also {it.eraseColor(0xffe7cccc.toInt())}
    private fun open(value:PlanarWorkshopController,key:String=binding,recoveryKey:String=key) {
        compose.runOnIdle{value.connect(key,recoveryKey)};compose.waitUntil(5000){!value.busy}
        compose.runOnIdle{value.open {photo()}};compose.waitUntil(5000){value.draft!=null&&!value.busy}
    }
    private fun edit(value:PlanarWorkshopController) {
        compose.runOnIdle{value.edit(value.draft!!.copy(anchors=value.draft!!.anchors.copy(head=PlanarPoint(31.0,19.0)),
            parts=listOf(PlanarPart("wing","单翼","accessory",20.0,listOf(PlanarPoint(3.0,4.0)),PlanarPoint(5.0,6.0))))) }
    }
    @After fun cleanup() {
        compose.runOnUiThread{controllers.forEach {it.dispose()}}
        runBlocking{controllers.forEach {runCatching {it.awaitCheckpoint()}}};scope.cancel();context.close()
        val root=context.filesDir.canonicalFile;check(root.parentFile==base.filesDir.canonicalFile&&root.name.startsWith("v4_"));root.deleteRecursively()
    }
    @Test fun controllerRecreationRestoresIncompleteEditsAndExactSource() {
        val first=controller();open(first);edit(first);runBlocking{first.awaitCheckpoint()}
        compose.runOnIdle{first.dispose()}
        val restored=controller();open(restored)
        assertEquals(PlanarPoint(31.0,19.0),restored.draft!!.anchors.head)
        assertEquals(1,restored.draft!!.parts.single().polygon.size);assertTrue(restored.dirty)
        assertFalse(restored.draft!!.suggestionsConfirmed);assertFalse(restored.canUndo)
        assertEquals(0xffe7cccc.toInt(),restored.source!!.getPixel(0,0))
    }
    @Test fun differentReferenceOrVisualVersionNeverReceivesOldEdits() {
        val first=controller();open(first)
        compose.runOnIdle{first.save("旧路径已保存角色")};compose.waitUntil(10000){!first.busy&&first.active!=null}
        edit(first);runBlocking{first.awaitCheckpoint()}
        val changed=controller();open(changed,binding,binding+":new-visual")
        assertNotEquals(PlanarPoint(31.0,19.0),changed.draft!!.anchors.head);assertTrue(changed.draft!!.parts.isEmpty())
        assertEquals("旧路径已保存角色",changed.active!!.name);assertFalse(changed.dirty)
    }
    @Test fun closingEditorKeepsRecoverableDraftWhileExplicitDiscardRemovesOnlyDraft() {
        val first=controller();open(first);edit(first);runBlocking{first.awaitCheckpoint()}
        compose.runOnIdle{first.closeEditor()}
        open(first);assertEquals(1,first.draft!!.parts.size)
        compose.runOnIdle{first.discardDraft()};compose.waitUntil(5000){!first.busy}
        open(first);assertTrue(first.draft!!.parts.isEmpty())
    }
    @Test fun saveClearsCheckpointAndLaterDiscardKeepsPublishedCharacter() {
        val first=controller();open(first)
        compose.runOnIdle{first.edit(first.draft!!.copy(anchors=first.draft!!.anchors.copy(head=PlanarPoint(30.0,18.0))));first.save("保留角色")}
        compose.waitUntil(10000){!first.busy&&first.active!=null};assertNull(first.error)
        edit(first);runBlocking{first.awaitCheckpoint()};val active=first.active
        compose.runOnIdle{first.discardDraft()};compose.waitUntil(5000){!first.busy}
        assertSame(active,first.active)
        val restored=controller();open(restored)
        assertEquals("保留角色",restored.active!!.name);assertEquals(PlanarPoint(30.0,18.0),restored.draft!!.anchors.head)
        assertTrue(restored.draft!!.parts.isEmpty());assertFalse(restored.dirty)
    }
    @Test fun immediateDisposalAndReopenWaitsForRegisteredCheckpointWrite() {
        val first=controller();open(first);edit(first)
        compose.runOnIdle{first.dispose()}
        val restored=controller();open(restored)
        assertEquals(PlanarPoint(31.0,19.0),restored.draft!!.anchors.head)
        assertEquals(1,restored.draft!!.parts.size)
    }
    @Test fun failedNewestCheckpointStillRecoversTheLastDurableEdits() {
        val first=controller();open(first);edit(first);runBlocking{first.awaitCheckpoint()}
        compose.runOnIdle{first.edit(first.draft!!.copy(anchors=first.draft!!.anchors.copy(head=PlanarPoint(Double.NaN,1.0))))}
        assertTrue(runBlocking{runCatching {first.awaitCheckpoint()}.isFailure})
        val restored=controller();open(restored)
        assertEquals(PlanarPoint(31.0,19.0),restored.draft!!.anchors.head);assertEquals(1,restored.draft!!.parts.size)
    }
    @Test fun oldControllerCannotResurrectDraftAfterAnotherControllerDiscardsIt() {
        val first=controller();open(first);edit(first);runBlocking{first.awaitCheckpoint()}
        val second=controller();open(second)
        compose.runOnIdle{second.discardDraft()};compose.waitUntil(5000){!second.busy}
        compose.runOnIdle{first.checkpoint()}
        assertTrue(runBlocking{runCatching {first.awaitCheckpoint()}.isFailure})
        val restored=controller();open(restored);assertTrue(restored.draft!!.parts.isEmpty())
    }
}
