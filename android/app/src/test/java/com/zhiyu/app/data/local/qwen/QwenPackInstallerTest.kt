package com.zhiyu.app.data.local.qwen

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CancellationException

class QwenPackInstallerTest {
    @get:Rule val temp = TemporaryFolder()
    private val weights = ByteArray(180_000) { (it % 251).toByte() }
    private fun manifest() = QwenModelPackManifestTest.parse(QwenModelPackManifestTest.fixture(
        weights.size.toLong(), QwenModelPackManifestTest.sha(weights)))

    @Test fun aCompletePackIsPublishedOnlyAfterAllFourDigestsPass() {
        val root = temp.newFolder().canonicalFile
        val manifest = manifest()
        val progress = mutableListOf<Long>()
        val installed = QwenPackInstaller(root).install(manifest, { ByteArrayInputStream(weights) },
            progress = { copied, total -> assertEquals(manifest.totalBytes, total); progress.add(copied) })
        assertEquals(manifest.manifestSha256, installed.name)
        assertEquals(manifest.totalBytes, progress.last())
        assertTrue(progress.size >= 12)
        assertEquals(4, QwenModelPackVerifier.verify(installed, manifest, manifest.runtime).files.size)
        assertTrue(root.listFiles()!!.none { it.name.startsWith("staging-") })
    }

    @Test fun cancellationPreservesTheExistingInstalledPackAndRemovesOnlyOwnedStaging() {
        val root = temp.newFolder().canonicalFile
        val manifest = manifest()
        val installer = QwenPackInstaller(root)
        val installed = installer.install(manifest, { ByteArrayInputStream(weights) })
        val marker = File(root, "unrelated.txt").apply { writeText("preserve") }
        var reads = 0
        assertThrows(CancellationException::class.java) {
            installer.install(manifest, { ByteArrayInputStream(weights) }, { if (++reads == 3) throw CancellationException() })
        }
        assertTrue(installed.isDirectory)
        assertEquals("preserve", marker.readText())
        assertEquals(4, QwenModelPackVerifier.verify(installed, manifest, manifest.runtime).files.size)
        assertTrue(root.listFiles()!!.none { it.name.startsWith("staging-") })
    }

    @Test fun aCorruptOrExtendedSourceNeverBecomesAnInstalledDirectory() {
        listOf(weights.copyOf().also { it[0] = 99 }, weights + byteArrayOf(1)).forEach { source ->
            val root = temp.newFolder().canonicalFile
            assertThrows(IllegalArgumentException::class.java) {
                QwenPackInstaller(root).install(manifest(), { ByteArrayInputStream(source) })
            }
            assertEquals(0, root.listFiles()!!.size)
        }
    }
}
