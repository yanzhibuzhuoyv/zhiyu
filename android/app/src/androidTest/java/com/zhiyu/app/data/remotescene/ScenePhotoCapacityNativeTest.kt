package com.zhiyu.app.data.remotescene

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.NativeV4Context
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class ScenePhotoCapacityNativeTest {
    @Test fun fullPrivateBudgetStopsBeforeGenerationAndClearingSpaceRestoresCapacity() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val context=NativeV4Context(base)
        try {
            val root=File(context.filesDir,"remote_scene_capacity_test")
            val store=RemoteSceneCandidateStore(context,"remote_scene_capacity_test")
            assertTrue(store.hasCapacity());assertFalse(root.exists())
            check(root.mkdir());val filler=File(root,"budget-only.bin")
            RandomAccessFile(filler,"rw").use {it.setLength(124L*1024*1024)}
            assertFalse(store.hasCapacity());assertEquals(124L*1024*1024,filler.length())
            check(filler.delete());assertTrue(store.hasCapacity())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {context.close();val root=context.filesDir.canonicalFile
            check(root.parentFile==base.filesDir.canonicalFile&&root.name.startsWith("v4_"));check(root.deleteRecursively())}
    }
}
