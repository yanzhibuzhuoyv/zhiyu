package rig2d.parts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

/** Review-only RGB ink evidence from synthetic oracle seeds, not semantic or identity acceptance. */
class InkDraftIntegrationTest {
    private val root = Path.of("artifacts/ink-eval-2026-10-13-r2")
    private val properties = Properties().also { p ->
        Files.newInputStream(root.resolve("packet.properties")).use(p::load)
    }
    private val width = properties.getProperty("width").toInt()
    private val height = properties.getProperty("height").toInt()
    private fun source(): PartRaster {
        val bytes = Files.readAllBytes(root.resolve("source.argb"))
        assertEquals(width * height * 4, bytes.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return PartRaster(width, height, IntArray(width * height) { buffer.int })
    }
    private fun mask(name: String): PixelMask {
        val bytes = Files.readAllBytes(root.resolve(name))
        assertEquals(width * height, bytes.size)
        assertTrue(bytes.all { it == 0.toByte() || it == 1.toByte() })
        return PixelMask.fromBooleans(width, height, BooleanArray(bytes.size) { bytes[it] == 1.toByte() })
    }
    @Test fun selectedInkCanBePreservedStaticallyWithoutInventingSemanticConfidence() {
        val raster = source()
        val raw = Files.readAllBytes(root.resolve("raw_parts-t32.mask"))
        val sha = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        assertEquals("a80f7c28f28453283a69984f75230fa5bd363639bf147ee707fd429e657fd19f", sha)
        val selected = mask("raw_parts-t32.mask")
        val draft = PromptMaskBridge.assemble(raster, properties.getProperty("sourceArgbSha"), emptyList(), selected)
        assertEquals(12554, selected.count)
        assertEquals(selected, draft.retained)
        assertTrue(draft.planes.isEmpty())
        assertTrue(draft.reviewRequired)
        assertFalse(draft.identityVerified)
        assertArrayEquals(raster.selectedArgb(selected), draft.recomposeArgb())
    }
    @Test fun explicitProtectionCanRetainUnseededIslandsWithoutAssigningThemACharacterPart() {
        val raster = source()
        val selected = mask("raw_parts-t32.mask")
        val unresolved = mask("raw_parts-t32.possible.mask")
        assertEquals(810, unresolved.count)
        assertEquals(0, selected.intersect(unresolved).count)
        val protected = selected.union(unresolved)
        val draft = PromptMaskBridge.assemble(raster, properties.getProperty("sourceArgbSha"), emptyList(), protected)
        assertEquals(13364, draft.foreground.count)
        assertTrue(draft.reviewRequired)
        assertTrue(draft.planes.isEmpty())
        assertArrayEquals(raster.selectedArgb(protected), draft.recomposeArgb())
    }
}
