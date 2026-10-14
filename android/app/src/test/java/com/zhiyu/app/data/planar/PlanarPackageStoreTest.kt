package com.zhiyu.app.data.planar

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PlanarPackageStoreTest {
    private fun withStore(block: (PlanarPackageStore, File) -> Unit) {
        val root = Files.createTempDirectory("planar-store-test-").toFile()
        try { block(PlanarPackageStore(root, PlanarPackageFixtures.contract), root) } finally { root.deleteRecursively() }
    }
    @Test fun importedActiveSurvivesStoreRecreationAndNativeExport() = withStore { store, root ->
        assertNull(store.loadActive())
        store.import(ByteArrayInputStream(PlanarPackageFixtures.archive()))
        assertEquals("测试角色", PlanarPackageStore(root, PlanarPackageFixtures.contract).loadActive()!!.name)
        assertArrayEquals(store.loadActive()!!.sourcePng, PlanarPackageReader(PlanarPackageFixtures.contract).read(ByteArrayInputStream(store.export())).sourcePng)
    }
    @Test fun corruptAndCancelledImportsKeepPreviousActive() = withStore { store, _ ->
        store.import(ByteArrayInputStream(PlanarPackageFixtures.archive()))
        val previous = store.export()
        assertThrows(IllegalArgumentException::class.java) { store.import(ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        val cancelled = object : InputStream() { override fun read(): Int = throw CancellationException("user cancelled") }
        assertThrows(CancellationException::class.java) { store.import(cancelled) }
        assertArrayEquals(previous, store.export())
    }
    @Test fun invalidSaveCannotReplaceActiveAndLeavesNoExtractedZipPaths() = withStore { store, root ->
        val original = store.import(ByteArrayInputStream(PlanarPackageFixtures.archive()))
        assertThrows(IllegalArgumentException::class.java) { store.save(original.copy(width = 100_000)) }
        assertEquals(2, store.loadActive()!!.width)
        assertTrue(root.listFiles()!!.all { it.isFile })
    }
    @Test fun cancellationAtPublicationBoundaryKeepsPreviousActive() = withStore { store, _ ->
        val original = store.import(ByteArrayInputStream(PlanarPackageFixtures.archive()))
        assertThrows(CancellationException::class.java) { store.save(original.copy(name = "新角色")) { throw CancellationException("cancelled before publish") } }
        assertEquals(original.name, store.loadActive()!!.name)
    }
}
