package com.zhiyu.app.data.local.qwen

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class QwenModelPackManifestTest {
    @Test fun trustedCompletePackRetainsActualBytesAndRuntimeLocks() {
        val pack = parse(fixture())
        assertEquals("edge-test", pack.packId)
        assertEquals(4096L, pack.totalBytes)
        assertEquals(listOf(-1, -4, -7, -10, -13, -16), pack.layers)
        assertEquals(9, pack.sigmas.size)
        assertEquals(0.0, pack.sigmas.last(), 0.0)
        assertEquals(4, pack.files.size)
        assertEquals("1".repeat(40), pack.runtime.llamaCppCommit)
    }

    @Test fun manifestMustMatchAnExternalTrustedDigest() {
        assertThrows(IllegalArgumentException::class.java) {
            QwenModelPackManifest.parse(fixture().toByteArray(), "0".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QwenModelPackManifest.parse(fixture().toByteArray(), "not-a-digest")
        }
    }

    @Test fun unsupportedArchitectureAndNonBackgroundCapabilitiesAreRejected() {
        rejected(fixture().replace("qwen-image-2.1-turbo", "qwen-image-2.0"))
        rejected(fixture().replace("text_to_image_background", "reference_edit"))
        rejected(fixture().replace("\"text_to_image_background\"", "\"text_to_image_background\",\"vision\""))
        rejected(fixture().replace("\"format_version\":1", "\"format_version\":2"))
    }

    @Test fun duplicateUnknownAndMissingFieldsCannotSilentlyChangeTheContract() {
        rejected(fixture().replace("\"format_version\":1", "\"format_version\":1,\"format_version\":1"))
        rejected(fixture().replace("\"format_version\":1", "\"format_version\":1,\"download_url\":\"https://example.test\""))
        rejected(fixture().replace("\"out_dim\":4096,", ""))
        rejected(fixture().replace("\"bridge_version\":1", "\"bridge_version\":1,\"fallback\":\"agnes\""))
    }

    @Test fun allFourRolesAndUniquePathsAreRequired() {
        rejected(fixture().replace("\"role\":\"vae\"", "\"role\":\"dit\""))
        rejected(fixture().replace("\"path\":\"vae.gguf\"", "\"path\":\"dit.gguf\""))
        rejected(fixture().replace("\"role\":\"vae\"", "\"role\":\"unknown\""))
    }

    @Test fun traversalAbsoluteNetworkAndWindowsPathsAreRejected() {
        listOf("../student.gguf", "/student.gguf", "C:/student.gguf", "folder//student.gguf",
            "folder/./student.gguf", "folder/../student.gguf", "student.gguf:stream", "https://example.test/model",
            "folder\\\\student.gguf").forEach { path ->
            rejected(fixture().replace("student.gguf", path))
        }
    }

    @Test fun fileBytesCannotBeNegativeFractionalZeroOrTooLargeForExactJsonIntegers() {
        listOf("0", "-1", "1.5", "9007199254740993", "1e309").forEach { count ->
            rejected(fixture().replace("\"bytes\":1024", "\"bytes\":$count"))
        }
    }

    @Test fun malformedDigestsAndRuntimeRevisionsAreRejected() {
        rejected(fixture().replace("a".repeat(64), "A".repeat(64)))
        rejected(fixture().replace("1".repeat(40), "main"))
        rejected(fixture().replace("\"bridge_version\":1", "\"bridge_version\":2"))
    }

    @Test fun adapterAndSixHiddenLayersCannotDriftFromTheExportedBridge() {
        rejected(fixture().replace("zqadap1_f32_le", "pytorch_pickle"))
        rejected(fixture().replace("\"in_dim\":6144", "\"in_dim\":1024"))
        rejected(fixture().replace("\"out_dim\":4096", "\"out_dim\":1024"))
        rejected(fixture().replace("[-1,-4,-7,-10,-13,-16]", "[-1,-4,-7,-10,-13,-13]"))
        rejected(fixture().replace("\"drop_tokens\":14", "\"drop_tokens\":0"))
    }

    @Test fun samplingMustUseTheCompleteFrozenEightStepGrid() {
        rejected(fixture().replace("\"width\":512", "\"width\":1024"))
        rejected(fixture().replace("\"steps\":8", "\"steps\":7"))
        rejected(fixture().replace("0.414568,0", "0.414568"))
        rejected(fixture().replace("0.978453", "0.99"))
        rejected(fixture().replace("0.414568,0", "0.414568,0.01"))
    }

    @Test fun overlargeInvalidUtf8AndTrailingContentAreRejected() {
        rejected(fixture() + " trailing")
        val invalid = byteArrayOf(0xc3.toByte(), 0x28)
        assertThrows(IllegalArgumentException::class.java) {
            QwenModelPackManifest.parse(invalid, sha(invalid))
        }
        val large = ByteArray(65 * 1024) { ' '.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            QwenModelPackManifest.parse(large, sha(large))
        }
    }

    @Test fun exposedListsCannotMutateTheVerifiedManifest() {
        val pack = parse(fixture())
        assertThrows(UnsupportedOperationException::class.java) { (pack.files as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (pack.layers as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (pack.sigmas as MutableList).clear() }
    }

    private fun rejected(json: String) {
        assertThrows(IllegalArgumentException::class.java) { parse(json) }
    }

    companion object {
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun parse(json: String): QwenModelPackManifest = json.toByteArray().let {
            QwenModelPackManifest.parse(it, sha(it))
        }

        fun fixture(bytes: Long = 1024, sha: String = "a".repeat(64)): String = """
            {"format_version":1,"pack_id":"edge-test","architecture":"qwen-image-2.1-turbo",
             "capabilities":["text_to_image_background"],
             "files":[{"role":"student","path":"student.gguf","bytes":$bytes,"sha256":"$sha"},
              {"role":"adapter","path":"adapter.fp32.bin","bytes":$bytes,"sha256":"$sha"},
              {"role":"dit","path":"dit.gguf","bytes":$bytes,"sha256":"$sha"},
              {"role":"vae","path":"vae.gguf","bytes":$bytes,"sha256":"$sha"}],
             "runtime":{"llama_cpp_commit":"${"1".repeat(40)}","stable_diffusion_cpp_commit":"${"2".repeat(40)}","bridge_version":1},
             "conditioning":{"adapter_format":"zqadap1_f32_le","in_dim":6144,"out_dim":4096,"layers":[-1,-4,-7,-10,-13,-16],"drop_tokens":14},
             "sampling":{"width":512,"height":512,"steps":8,"sigmas":[1,0.978453,0.95418,0.926626,0.89508,0.845148,0.704534,0.414568,0]}}
        """.trimIndent()
    }
}
