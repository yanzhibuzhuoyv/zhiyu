package com.zhiyu.app.data.local.qwen

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

/** Actual Android filesystem and decoder, with isolated small synthetic weights, no JNI inference. */
class QwenSinglePackImportNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "single-pack-native-${UUID.randomUUID()}").canonicalFile.apply { check(mkdirs()) }
    private val weights = (0..3).map { role -> ByteArray(130_003 + role * 37) { ((it + role * 17) % 251).toByte() } }

    @After fun cleanup() {
        require(root.parentFile == context.cacheDir.canonicalFile && !Files.isSymbolicLink(root.toPath()))
        Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
            require(path.startsWith(root.toPath())); Files.deleteIfExists(path)
        } }
    }

    @Test fun oneContainerPublishesFourDistinctVerifiedFilesOnTheActualAndroidFilesystem() {
        val manifest = fixture()
        val expected = QwenModelPackManifest.parse(manifest, sha(manifest))
        val selected = File(root, "selected.zyqwen").apply { writeBytes(container(manifest)) }
        val outputRoot = File(root, "installed").apply { check(mkdir()) }
        val originalSha = sha(selected.readBytes())
        val installed = selected.inputStream().use {
            QwenSinglePackImporter(outputRoot).install(it, sha(manifest))
        }
        expected.files.forEachIndexed { index, file -> assertArrayEquals(weights[index], File(installed, file.path).readBytes()) }
        assertEquals(expected.manifestSha256, File(installed, "verified.sha256").readText())
        assertEquals(4, QwenModelPackVerifier.verify(installed, expected, expected.runtime).files.size)
        assertEquals(originalSha, sha(selected.readBytes()))
        assertEquals(listOf(installed.name), outputRoot.list()!!.toList())
    }

    @Test fun trailingBytesFailBeforePublicationAndCannotDamageAnAlreadyInstalledPack() {
        val manifest = fixture()
        val outputRoot = File(root, "installed").apply { check(mkdir()) }
        val importer = QwenSinglePackImporter(outputRoot)
        assertThrows(IllegalArgumentException::class.java) {
            importer.install(ByteArrayInputStream(container(manifest) + byteArrayOf(1)), sha(manifest))
        }
        assertEquals(0, outputRoot.list()!!.size)
        val installed = importer.install(ByteArrayInputStream(container(manifest)), sha(manifest))
        val snapshots = installed.listFiles()!!.associate { it.name to sha(it.readBytes()) }
        assertThrows(IllegalArgumentException::class.java) {
            importer.install(ByteArrayInputStream(container(manifest) + byteArrayOf(1)), sha(manifest))
        }
        assertEquals(snapshots, installed.listFiles()!!.associate { it.name to sha(it.readBytes()) })
    }

    @Test fun cancellationRemovesOnlyOwnedStagingAndLeavesAnExistingReleaseUntouched() {
        val original = fixture()
        val outputRoot = File(root, "installed").apply { check(mkdir()) }
        val importer = QwenSinglePackImporter(outputRoot)
        val installed = importer.install(ByteArrayInputStream(container(original)), sha(original))
        val snapshots = installed.listFiles()!!.associate { it.name to sha(it.readBytes()) }
        val unrelated = File(outputRoot, "unrelated.txt").apply { writeText("keep") }
        val next = fixture("native-next")
        var copied = 0L
        assertThrows(CancellationException::class.java) {
            importer.install(ByteArrayInputStream(container(next)), sha(next),
                checkCancelled = { if (copied > 0) throw CancellationException() },
                progress = { done, _ -> copied = done })
        }
        assertEquals(setOf(installed.name, unrelated.name), outputRoot.list()!!.toSet())
        assertEquals("keep", unrelated.readText())
        assertEquals(snapshots, installed.listFiles()!!.associate { it.name to sha(it.readBytes()) })
    }

    @Test fun aTruncatedActualReleaseContainerCannotBeMistakenForAnInstalledModel() {
        val manifest = context.assets.open("qwen/manifest.json").use { it.readBytes() }
        assertEquals(QwenRelease.MANIFEST_SHA, sha(manifest))
        val outputRoot = File(root, "installed").apply { check(mkdir()) }
        assertThrows(IllegalArgumentException::class.java) {
            QwenSinglePackImporter(outputRoot).install(ByteArrayInputStream(container(manifest)), QwenRelease.MANIFEST_SHA)
        }
        assertEquals(0, outputRoot.list()!!.size)
    }

    private fun fixture(id: String = "native-fixture"): ByteArray {
        val json = context.assets.open("qwen/manifest.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        json.put("pack_id", id)
        val files = json.getJSONArray("files")
        weights.forEachIndexed { index, bytes -> files.getJSONObject(index).put("bytes", bytes.size).put("sha256", sha(bytes)) }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun container(manifest: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { stream ->
            stream.write("ZYQPACK1".toByteArray(Charsets.US_ASCII)); stream.writeInt(manifest.size)
            stream.write(manifest); weights.forEach { stream.write(it) }
        }
    }.toByteArray()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
