package com.zhiyu.app.data.planar

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class PlanarDraftStoreTest {
    private val binding="a".repeat(64)
    private val parent="b".repeat(64)
    private fun snapshot(text:String="{\"anchors\":[]}")=PlanarDraftCheckpoint(binding,parent,32,24,byteArrayOf(1,2,3),text)
    private fun withStore(test:(PlanarDraftStore,File)->Unit) {
        val root=Files.createTempDirectory("planar-draft-").toFile()
        try {test(PlanarDraftStore(root),root)} finally {root.deleteRecursively()}
    }
    @Test fun recreationRecoversExactPrivateSourceAndEdits()=withStore {store,root->
        val expected=snapshot();store.save(expected)
        val actual=PlanarDraftStore(root).load(binding,parent)!!
        assertArrayEquals(expected.sourcePng,actual.sourcePng);assertEquals(expected.draftJson,actual.draftJson)
        assertEquals(expected.width,actual.width);assertEquals(expected.height,actual.height)
    }
    @Test fun bindingAndPublishedPackageChangesNeverRecoverStaleEdits()=withStore {store,_->
        store.save(snapshot());assertNull(store.load("c".repeat(64),parent));assertNull(store.load(binding,null))
        assertNotNull(store.load(binding,parent))
    }
    @Test fun corruptionIsRejectedWithoutDeletingPublishedPackage()=withStore {store,root->
        store.save(snapshot());val active=File(root,"active.planar").apply {writeText("saved character")}
        File(root,"draft.checkpoint").writeBytes(byteArrayOf(1,2,3))
        assertThrows(IllegalArgumentException::class.java){store.load(binding,parent)}
        assertEquals("saved character",active.readText())
    }
    @Test fun cancelledReplacementKeepsLastDurableCheckpoint()=withStore {store,root->
        store.save(snapshot("old"))
        assertThrows(CancellationException::class.java){store.save(snapshot("new")){throw CancellationException("cancel")}}
        assertEquals("old",store.load(binding,parent)!!.draftJson)
        assertEquals(listOf("draft.checkpoint"),root.list()!!.toList())
    }
    @Test fun explicitDiscardRemovesOnlyDraftAndNeverAnActivePackage()=withStore {store,root->
        store.save(snapshot());val active=File(root,"active.planar").apply {writeText("saved character")}
        store.clear();store.clear();assertNull(store.load(binding,parent));assertEquals("saved character",active.readText())
    }
    @Test fun malformedIdentityAndOversizedInputsCannotReplaceDurableDraft()=withStore {store,_->
        store.save(snapshot("old"))
        listOf(snapshot().copy(bindingSha="../bad"),snapshot().copy(width=1537),
            snapshot().copy(sourcePng=ByteArray(20*1024*1024+1)),snapshot().copy(draftJson="x".repeat(32*1024*1024+1))).forEach {
            assertThrows(IllegalArgumentException::class.java){store.save(it)}
        }
        assertEquals("old",store.load(binding,parent)!!.draftJson)
    }
}
