package com.zhiyu.app.data.planar

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.NativeV4Context
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlanarDraftStoreNativeTest {
    @Test fun privateSymlinksCannotRedirectCheckpointReadsWritesOrDiscard() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val context=NativeV4Context(base);val root=File(context.filesDir.canonicalFile,"checkpoint").apply {mkdirs()}
        val outside=File(context.filesDir,"outside").apply {writeText("keep original")}
        try {
            val store=PlanarDraftStore(root)
            val value=PlanarDraftCheckpoint("a".repeat(64),null,32,24,byteArrayOf(1,2,3),"edits")
            store.save(value);assertNotNull(store.load(value.bindingSha,null));store.clear()
            Files.createSymbolicLink(File(root,"draft.checkpoint").toPath(),outside.toPath())
            assertThrows(IllegalArgumentException::class.java){store.save(value)}
            assertThrows(IllegalArgumentException::class.java){store.load(value.bindingSha,null)}
            assertThrows(IllegalArgumentException::class.java){store.clear()}
            assertEquals("keep original",outside.readText())
        } finally {
            Files.deleteIfExists(File(root,"draft.checkpoint").toPath());context.close()
            val owned=context.filesDir.canonicalFile;check(owned.parentFile==base.filesDir.canonicalFile&&owned.name.startsWith("v4_"));owned.deleteRecursively()
        }
    }
}
