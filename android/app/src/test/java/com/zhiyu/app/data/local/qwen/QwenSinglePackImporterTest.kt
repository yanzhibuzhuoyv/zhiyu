package com.zhiyu.app.data.local.qwen

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException

class QwenSinglePackImporterTest {
    @get:Rule val temp = TemporaryFolder()
    private val paths = listOf("student.gguf", "adapter.fp32.bin", "dit.gguf", "vae.gguf")
    private val payloads = paths.indices.map { role ->
        ByteArray(70_001 + role * 101) { ((it * 7 + role * 31) % 251).toByte() }
    }
    private val manifestBytes get() = manifestJson().toByteArray(Charsets.UTF_8)
    private val trustedSha get() = QwenModelPackManifestTest.sha(manifestBytes)

    @Test fun importsFourDistinctPayloadsInManifestOrderAndPublishesOnlyVerifiedBytes() {
        val root = root()
        val manifest = QwenModelPackManifestTest.parse(manifestJson())
        val reports = mutableListOf<Pair<Long, Long>>()
        val installed = QwenSinglePackImporter(root).install(ByteArrayInputStream(pack()), trustedSha,
            progress = { done, total -> reports.add(done to total) })
        assertEquals(manifest.manifestSha256, installed.name)
        paths.forEachIndexed { index, path -> assertArrayEquals(payloads[index], File(installed, path).readBytes()) }
        assertEquals(trustedSha, File(installed, "verified.sha256").readText())
        assertEquals(manifest.totalBytes to manifest.totalBytes, reports.last())
        assertTrue(reports.zipWithNext().all { (a, b) -> a.first <= b.first })
        assertEquals(4, QwenModelPackVerifier.verify(installed, manifest, manifest.runtime).files.size)
        assertEquals(listOf(installed.name), root.list()!!.toList())
    }

    @Test fun rejectsWrongMagicAndEveryInvalidUnsignedHeaderLengthBeforePublishing() {
        val badMagic = pack().also { it[0] = 'X'.code.toByte() }
        rejected(badMagic)
        listOf(0, -1, 65_537, Int.MAX_VALUE).forEach { length -> rejected(header(length)) }
    }

    @Test fun requiresTrustedExternalManifestDigestRatherThanTrustingInputContent() {
        val root = root()
        assertThrows(IllegalArgumentException::class.java) {
            QwenSinglePackImporter(root).install(ByteArrayInputStream(pack()), "0".repeat(64))
        }
        assertEmpty(root)
        assertThrows(IllegalArgumentException::class.java) {
            QwenSinglePackImporter(root).install(ByteArrayInputStream(pack()), "not-a-sha")
        }
        assertEmpty(root)
    }

    @Test fun headerAndManifestTruncationCannotBeReportedAsAnInstalledPack() {
        val complete = pack()
        listOf(0, 7, 8, 11, 12, 12 + manifestBytes.size - 1).forEach { length ->
            rejected(complete.copyOf(length))
        }
    }

    @Test fun corruptionInEachOfTheFourPayloadsFailsAndLeavesNoPublishedOrStagingFiles() {
        payloads.indices.forEach { index ->
            rejected(pack().also { bytes ->
                val offset = payloadStart(index)
                bytes[offset] = (bytes[offset].toInt() xor 0x7f).toByte()
            })
        }
    }

    @Test fun truncationInEachOfTheFourPayloadsFailsAndRemovesOwnedStaging() {
        payloads.indices.forEach { index ->
            rejected(pack().copyOf(payloadStart(index) + payloads[index].size - 1))
        }
    }

    @Test fun swappingPayloadOrderCannotPassTheIndividualTrustedDigests() {
        rejected(pack(listOf(payloads[1], payloads[0], payloads[2], payloads[3])))
    }

    @Test fun anyTrailingByteFailsBeforeTheAtomicPublish() {
        listOf(byteArrayOf(0), byteArrayOf(1, 2, 3), "ZYQPACK1".toByteArray()).forEach { suffix ->
            val root = root()
            assertThrows(IllegalArgumentException::class.java) {
                QwenSinglePackImporter(root).install(ByteArrayInputStream(pack() + suffix), trustedSha)
            }
            assertFalse(File(root, trustedSha).exists())
            assertEmpty(root)
        }
    }

    @Test fun cancellationOfAnotherReleasePreservesTheOldPackAndUnrelatedFiles() {
        val root = root()
        val old = installExisting(root)
        val unrelated = File(root, "unrelated.txt").apply { writeText("preserve me") }
        val newJson = manifestJson().replace("edge-test", "edge-next")
        val newBytes = newJson.toByteArray(Charsets.UTF_8)
        var copied = 0L
        assertThrows(CancellationException::class.java) {
            QwenSinglePackImporter(root).install(ByteArrayInputStream(pack(manifest = newBytes)),
                QwenModelPackManifestTest.sha(newBytes),
                checkCancelled = { if (copied > 0) throw CancellationException("test interruption") },
                progress = { done, _ -> copied = done })
        }
        assertTrue(copied > 0)
        assertOriginalPack(root, old)
        assertEquals("preserve me", unrelated.readText())
        assertEquals(setOf(old.name, unrelated.name), root.list()!!.toSet())
    }

    @Test fun anExistingVerifiedPackStillRejectsBadNewSourceRatherThanReturningFalseSuccess() {
        val root = root()
        val old = installExisting(root)
        val invalid = listOf(pack().also { it[payloadStart(2)] = 127 }, pack() + byteArrayOf(9),
            pack().copyOf(pack().size - 1))
        invalid.forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                QwenSinglePackImporter(root).install(ByteArrayInputStream(source), trustedSha)
            }
            assertOriginalPack(root, old)
            assertEquals(setOf(old.name), root.list()!!.toSet())
        }
    }

    @Test fun anExistingVerifiedPackConsumesAndValidatesTheCompleteNewContainer() {
        val root = root()
        val old = installExisting(root)
        val source = TrackingInput(pack())
        val actual = QwenSinglePackImporter(root).install(source, trustedSha)
        assertEquals(old, actual)
        assertEquals(pack().size.toLong(), source.consumed)
        assertTrue(source.eofReads > 0)
        assertOriginalPack(root, old)
    }

    @Test(timeout = 3_000) fun aZeroLengthReadInTheHeaderFailsWithoutSpinningOrPublishing() {
        val source = ZeroReadInput(pack(), zeroAfter = 0)
        val root = root()
        assertThrows(IllegalArgumentException::class.java) {
            QwenSinglePackImporter(root).install(source, trustedSha)
        }
        assertTrue(source.zeroReads in 1..2)
        assertEmpty(root)
    }

    @Test(timeout = 3_000) fun aZeroLengthReadInPayloadFailsWithoutSpinningOrPublishing() {
        val source = ZeroReadInput(pack(), zeroAfter = payloadStart(0))
        val root = root()
        assertThrows(IllegalArgumentException::class.java) {
            QwenSinglePackImporter(root).install(source, trustedSha)
        }
        assertTrue(source.zeroReads in 1..2)
        assertEmpty(root)
    }

    @Test fun aPayloadLargerThanIntMaxUsesLongCountersAndCanBeCancelledAfterOneSmallBlock() {
        val declared = Int.MAX_VALUE.toLong() + 12_345L
        val bytes = QwenModelPackManifestTest.fixture(declared, "0".repeat(64)).toByteArray()
        val source = LargeSyntheticInput(header(bytes.size) + bytes)
        val root = root()
        var copied = 0L
        var total = 0L
        assertThrows(CancellationException::class.java) {
            QwenSinglePackImporter(root).install(source, QwenModelPackManifestTest.sha(bytes),
                checkCancelled = { if (copied > 0) throw CancellationException("stop after a small block") },
                progress = { done, expected -> copied = done; total = expected })
        }
        assertEquals(declared * 4L, total)
        assertTrue(copied in 1..65_536L)
        assertTrue(source.payloadBytes in 1..65_536L)
        assertTrue(source.maxRequested <= 65_536)
        assertEmpty(root)
    }

    private fun manifestJson(): String {
        var json = QwenModelPackManifestTest.fixture(1, "0".repeat(64))
        paths.forEachIndexed { index, path ->
            val prefix = "\"path\":\"$path\",\"bytes\":"
            json = json.replace(prefix + "1,\"sha256\":\"${"0".repeat(64)}\"",
                prefix + "${payloads[index].size},\"sha256\":\"${QwenModelPackManifestTest.sha(payloads[index])}\"")
        }
        return json
    }

    private fun header(length: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { it.write("ZYQPACK1".toByteArray(Charsets.US_ASCII)); it.writeInt(length) }
    }.toByteArray()

    private fun pack(data: List<ByteArray> = payloads, manifest: ByteArray = manifestBytes): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            bytes.write(header(manifest.size)); bytes.write(manifest); data.forEach { bytes.write(it) }
        }.toByteArray()

    private fun payloadStart(index: Int) = 12 + manifestBytes.size + payloads.take(index).sumOf { it.size }
    private fun root() = temp.newFolder().canonicalFile
    private fun assertEmpty(root: File) = assertEquals(emptySet<String>(), root.list()!!.toSet())

    private fun rejected(source: ByteArray) {
        val root = root()
        assertThrows(IllegalArgumentException::class.java) {
            QwenSinglePackImporter(root).install(ByteArrayInputStream(source), trustedSha)
        }
        assertEmpty(root)
    }

    private fun installExisting(root: File): File {
        val manifest = QwenModelPackManifestTest.parse(manifestJson())
        return QwenPackInstaller(root).install(manifest, { path ->
            ByteArrayInputStream(payloads[paths.indexOf(path)])
        })
    }

    private fun assertOriginalPack(root: File, old: File) {
        assertEquals(root, old.parentFile)
        val manifest = QwenModelPackManifestTest.parse(manifestJson())
        assertEquals(4, QwenModelPackVerifier.verify(old, manifest, manifest.runtime).files.size)
        assertEquals(trustedSha, File(old, "verified.sha256").readText())
    }

    private class TrackingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var consumed = 0L
        var eofReads = 0
        override fun read(): Int = super.read().also { if (it >= 0) consumed++ else eofReads++ }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length)
            .also { if (it > 0) consumed += it else if (it < 0) eofReads++ }
    }

    private class ZeroReadInput(bytes: ByteArray, private val zeroAfter: Int) : ByteArrayInputStream(bytes) {
        var zeroReads = 0
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (pos >= zeroAfter) { zeroReads++; return 0 }
            return super.read(bytes, offset, minOf(length, zeroAfter - pos))
        }
    }

    private class LargeSyntheticInput(private val prefix: ByteArray) : InputStream() {
        private var prefixOffset = 0
        var payloadBytes = 0L
        var maxRequested = 0
        override fun read(): Int = if (prefixOffset < prefix.size) prefix[prefixOffset++].toInt() and 255
            else { payloadBytes++; 0 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            maxRequested = maxOf(maxRequested, length)
            if (prefixOffset < prefix.size) {
                val count = minOf(length, prefix.size - prefixOffset)
                prefix.copyInto(bytes, offset, prefixOffset, prefixOffset + count)
                prefixOffset += count
                return count
            }
            val count = minOf(length, 65_536)
            bytes.fill(0, offset, offset + count); payloadBytes += count
            return count
        }
    }
}
