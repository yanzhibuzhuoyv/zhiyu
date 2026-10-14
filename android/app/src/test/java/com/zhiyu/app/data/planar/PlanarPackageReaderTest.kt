package com.zhiyu.app.data.planar

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class PlanarPackageReaderTest {
    private val fixtures = PlanarPackageFixtures
    private fun reader() = PlanarPackageReader(fixtures.contract)
    private fun read(bytes: ByteArray) = reader().read(ByteArrayInputStream(bytes))
    @Test fun desktopPackageLoadsValidatedMeshSourceAndRoles() {
        val doc = read(fixtures.archive())
        assertEquals(2, doc.width); assertEquals("测试角色", doc.name)
        assertEquals("base", doc.layers.single().role)
        assertArrayEquals(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f), doc.layers.single().mesh.baseVertices(), 0f)
        assertArrayEquals(fixtures.png, doc.sourcePng)
    }
    @Test fun nativeExportDeclaresNativeFormatAndRoundTripsDesktopInput() {
        val original = read(fixtures.archive())
        val bytes = reader().write(original)
        val entries = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes))
        var manifest = ""
        entries.use { zip -> while (true) { val entry = zip.nextEntry ?: break; if (entry.name == "manifest.json") manifest = zip.readBytes().toString(Charsets.UTF_8) } }
        assertTrue(manifest.contains("rig2d-planar-native-package-1"))
        assertArrayEquals(original.sourcePng, read(bytes).sourcePng)
    }
    @Test fun traversalUnknownPathsUnexpectedEntriesAndMissingManifestAreRejected() {
        listOf("../source.png", "textures/../source.png", "/source.png", "folder/", "extra.txt").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { read(fixtures.archive(fixtures.files() + (path to byteArrayOf(1)))) }
        }
        assertThrows(IllegalArgumentException::class.java) { read(byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { read(fixtures.archive(fixtures.files().minus("source.png"))) }
    }
    @Test fun badHashesMismatchSourceShaAndUnknownFormatsAreRejected() {
        val manifest = "{\"format\":\"rig2d-planar-package-1\",\"files\":{}}"
        assertThrows(IllegalArgumentException::class.java) { read(fixtures.archive(manifestOverride = manifest)) }
        assertThrows(IllegalArgumentException::class.java) { read(fixtures.archive(format = "future")) }
        val broken = fixtures.files().toMutableMap(); broken["source.png"] = byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) { read(fixtures.archive(broken)) }
        assertThrows(IllegalArgumentException::class.java) { reader().read(ByteArrayInputStream(fixtures.archive()), "f".repeat(64)) }
    }
    @Test fun unsafeMeshShapesIndicesCoordinatesUvsAndDeltasAreRejected() {
        listOf(
            "\"indices\":[0,1,2]" to "\"indices\":[0,1,3]",
            "\"indices\":[0,1,2]" to "\"indices\":[0,1,1]",
            "\"uv\":[0,0,1,0,0,1]" to "\"uv\":[0,0,2,0,0,1]",
            "\"vertices\":[0,0,2,0,0,2]" to "\"vertices\":[0,0,3,0,0,2]",
            "\"headX\":[0,0,0,0,0,0]" to "\"headX\":[0,0]",
            "\"headX\":[0,0,0,0,0,0]" to "\"unknown\":[0,0,0,0,0,0]",
            "\"width\":2" to "\"width\":2.5",
            "\"role\":\"base\"" to "\"role\":\"future\"",
            "\"headX\":[-1,1,0]" to "\"headX\":[-2,2,0]",
        ).forEach { (from, to) -> assertThrows(IllegalArgumentException::class.java) { read(fixtures.archive(fixtures.files({ it.replace(from, to) }))) } }
    }
    @Test fun duplicateJsonKeysPngDimensionsAndCorruptionCannotReachDecoder() {
        assertThrows(IllegalArgumentException::class.java) { read(fixtures.archive(fixtures.files({ it.replace("\"width\":2", "\"width\":2,\"width\":2") }))) }
        val largePng = fixtures.png(100_000, 100_000)
        val oversized = fixtures.files({ it.replace("\"sourceSha\":\"${fixtures.sha(fixtures.png)}\"", "\"sourceSha\":\"${fixtures.sha(largePng)}\"") }).toMutableMap(); oversized["source.png"] = largePng
        assertThrows(IllegalArgumentException::class.java) { read(fixtures.archive(oversized)) }
        val corrupt = fixtures.png; corrupt[40] = (corrupt[40].toInt() xor 1).toByte()
        val files = fixtures.files({ it.replace("\"sourceSha\":\"${fixtures.sha(fixtures.png)}\"", "\"sourceSha\":\"${fixtures.sha(corrupt)}\"") }).toMutableMap(); files["source.png"] = corrupt
        assertThrows(IllegalArgumentException::class.java) { read(fixtures.archive(files)) }
    }
    @Test fun compressedInputIsCountedEvenForStreamsWithoutLengthMetadata() {
        val infinite = object : InputStream() {
            var bytes = 0L
            override fun read(): Int { bytes++; return 0 }
            override fun read(buffer: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(buffer, off, off + len, 0.toByte()); bytes += len; return len }
        }
        assertThrows(IllegalArgumentException::class.java) { reader().read(infinite) }
        assertTrue(infinite.bytes <= 64L * 1024 * 1024 + 8192)
    }
    @Test fun zipCentralMetadataRejectsSymlinksEncryptionAndExpandedBudget() {
        val bytes = fixtures.archive()
        val marker = byteArrayOf(80, 75, 1, 2)
        val central = (0..bytes.size - 46).first { index -> marker.indices.all { bytes[index + it] == marker[it] } }
        fun mutate(offset: Int, value: Int): ByteArray = bytes.copyOf().also { raw -> repeat(4) { raw[central + offset + it] = (value ushr (it * 8)).toByte() } }
        assertThrows(IllegalArgumentException::class.java) { read(mutate(38, 0xA1FF0000.toInt())) }
        val encrypted = bytes.copyOf(); encrypted[central + 8] = (encrypted[central + 8].toInt() or 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { read(encrypted) }
        assertThrows(IllegalArgumentException::class.java) { read(mutate(24, 128 * 1024 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { read(mutate(16, 0)) }
    }
    @Test fun duplicateArchivePathsAreRejectedEvenBeforeManifestChecks() {
        val bytes = fixtures.archive()
        val from = "source.png".toByteArray(); val to = "asset.json".toByteArray()
        for (index in 0..bytes.size - from.size) if (from.indices.all { bytes[index + it] == from[it] }) to.copyInto(bytes, index)
        assertThrows(IllegalArgumentException::class.java) { read(bytes) }
    }
    @Test fun desktopDefinitionAcceptsOneBitAndPaletteBinaryMasksWithoutBitmapDecode() {
        val rows = byteArrayOf(0, 64, 0, -128)
        val masks = listOf(fixtures.png(2, 2, 0, 1, rows), fixtures.png(2, 2, 3, 1, rows, byteArrayOf(0, 0, 0, -1, -1, -1)))
        for (mask in masks) {
            val encoded = java.util.Base64.getEncoder().encodeToString(mask)
            val part = """{"id":"hair","name":"发丝","role":"hair","z":0.5,"pivot":[1,1],"strength":1,"mask":"$encoded"}"""
            val definition = fixtures.definition.replace("\"parts\":[]", "\"parts\":[$part]")
            assertEquals(2, read(fixtures.archive(fixtures.files(definitionText = definition))).width)
        }
    }
    @Test fun idatWithValidCrcButWrongPixelExpansionFailsBeforeBitmapDecode() {
        val invalid = fixtures.png(2, 2, scanlines = byteArrayOf(0, 0))
        val files = fixtures.files({ it.replace("\"sourceSha\":\"${fixtures.sha(fixtures.png)}\"", "\"sourceSha\":\"${fixtures.sha(invalid)}\"") }).toMutableMap()
        files["source.png"] = invalid
        assertThrows(IllegalArgumentException::class.java) { read(fixtures.archive(files)) }
    }
    @Test fun texturePixelBudgetExcludesTheSeparatelyBoundedSource() {
        val source = fixtures.png(1536, 1536, scanlines = ByteArray((1536 * 4 + 1) * 1536))
        val texture = fixtures.png(1411, 1411, scanlines = ByteArray((1411 * 4 + 1) * 1411))
        val textureSha = fixtures.sha(texture)
        val template = PlanarJson.parse(fixtures.files().getValue("asset.json").toString(Charsets.UTF_8)) as Map<*, *>
        fun archive(count: Int): ByteArray {
            val asset = template.entries.associate { it.key as String to it.value }.toMutableMap()
            asset["width"] = 1536; asset["height"] = 1536; asset["sourceSha"] = fixtures.sha(source)
            asset["layers"] = List(count) { index -> mapOf(
                "id" to "part_$index", "name" to "part_$index", "role" to "body", "z" to index,
                "texture" to "textures/$textureSha.png", "textureSha" to textureSha,
                "bounds" to listOf(0, 0, 1411, 1411), "phase" to 0, "strength" to 1,
                "vertices" to listOf(0, 0, 1411, 0, 0, 1411), "uv" to listOf(0, 0, 1, 0, 0, 1),
                "indices" to listOf(0, 1, 2), "deltas" to emptyMap<String, Any>(),
            ) }
            return fixtures.archive(mapOf("source.png" to source, "textures/$textureSha.png" to texture,
                "asset.json" to PlanarJson.encode(asset).toByteArray(), "definition.json" to fixtures.definition.toByteArray()))
        }
        assertEquals(12, read(archive(12)).layers.size) // 23,891,052 texture pixels; source adds 2,359,296 separately.
        val error = assertThrows(IllegalArgumentException::class.java) { read(archive(13)) }
        assertEquals("PLANAR_DECODE_BUDGET", error.message)
    }
}
