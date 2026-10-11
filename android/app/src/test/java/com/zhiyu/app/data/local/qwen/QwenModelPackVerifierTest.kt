package com.zhiyu.app.data.local.qwen

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException

class QwenModelPackVerifierTest {
    @get:Rule val temp = TemporaryFolder()
    private val payload = ByteArray(200_000) { (it % 251).toByte() }
    private val runtime = QwenRuntimeLock("1".repeat(40), "2".repeat(40), 1)

    @Test fun allFilesAreStreamedAndVerifiedAgainstActualSizesAndDigests() {
        val (root, manifest) = pack()
        var chunks = 0
        val result = QwenModelPackVerifier.verify(root, manifest, runtime) { chunks++ }
        assertEquals(payload.size.toLong() * 4, result.manifest.totalBytes)
        assertEquals(4, result.files.size)
        assertTrue(chunks >= 12)
        assertEquals(File(root, "dit.gguf").canonicalFile, result.files.getValue(QwenFileRole.DIT))
    }

    @Test fun missingTruncatedAndExtendedFilesAreRejected() {
        listOf("missing", "truncated", "extended").forEach { change ->
            val (root, manifest) = pack()
            val file = File(root, "dit.gguf")
            when (change) {
                "missing" -> assertTrue(file.delete())
                "truncated" -> file.writeBytes(payload.copyOf(payload.size - 1))
                else -> file.appendBytes(byteArrayOf(1))
            }
            assertThrows(IllegalArgumentException::class.java) {
                QwenModelPackVerifier.verify(root, manifest, runtime)
            }
        }
    }

    @Test fun sameSizeWrongWeightsCannotPassHashValidation() {
        val (root, manifest) = pack()
        File(root, "student.gguf").writeBytes(payload.copyOf().also { it[0] = 1 })
        assertThrows(IllegalArgumentException::class.java) {
            QwenModelPackVerifier.verify(root, manifest, runtime)
        }
    }

    @Test fun runtimeMismatchFailsBeforeOpeningOrHashingWeights() {
        val (root, manifest) = pack()
        var checks = 0
        assertThrows(IllegalArgumentException::class.java) {
            QwenModelPackVerifier.verify(root, manifest, runtime.copy(llamaCppCommit = "3".repeat(40))) { checks++ }
        }
        assertEquals(0, checks)
    }

    @Test fun cancellationPropagatesWithoutMarkingThePackReady() {
        val (root, manifest) = pack()
        var checks = 0
        val cancelled = CancellationException("synthetic cancellation")
        val observed = assertThrows(CancellationException::class.java) {
            QwenModelPackVerifier.verify(root, manifest, runtime) {
                if (++checks == 3) throw cancelled
            }
        }
        assertSame(cancelled, observed)
        assertEquals(payload.size.toLong(), File(root, "student.gguf").length())
    }

    @Test fun changedFilesDuringValidationAreRejected() {
        val (root, manifest) = pack()
        var checks = 0
        assertThrows(IllegalArgumentException::class.java) {
            QwenModelPackVerifier.verify(root, manifest, runtime) {
                if (++checks == 3) File(root, "student.gguf").appendBytes(byteArrayOf(1))
            }
        }
    }

    @Test fun aDirectoryCannotStandInForAWeightFile() {
        val (root, manifest) = pack()
        val dit = File(root, "dit.gguf")
        assertTrue(dit.delete())
        assertTrue(dit.mkdir())
        assertThrows(IllegalArgumentException::class.java) {
            QwenModelPackVerifier.verify(root, manifest, runtime)
        }
    }

    @Test fun validatedFilesMapCannotBeChangedAfterVerification() {
        val (root, manifest) = pack()
        val result = QwenModelPackVerifier.verify(root, manifest, runtime)
        assertThrows(UnsupportedOperationException::class.java) { (result.files as MutableMap).clear() }
    }

    @Test fun verifierDoesNotAcceptAnImplicitPathAlias() {
        val (root, manifest) = pack()
        assertThrows(IllegalArgumentException::class.java) {
            QwenModelPackVerifier.verify(File(root, "../${root.name}"), manifest, runtime)
        }
    }

    private fun pack(): Pair<File, QwenModelPackManifest> {
        val root = temp.newFolder().canonicalFile
        listOf("student.gguf", "adapter.fp32.bin", "dit.gguf", "vae.gguf").forEach {
            File(root, it).writeBytes(payload)
        }
        return root to QwenModelPackManifestTest.parse(QwenModelPackManifestTest.fixture(
            payload.size.toLong(), QwenModelPackManifestTest.sha(payload)))
    }
}
