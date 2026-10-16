package rig2d.parts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

/** Actual CPU inference artifacts, with oracle prompts; not an Android or identity test. */
class PromptMaskBridgeIntegrationTest {
    private fun readCase(name: String): Pair<PartRaster,PartLayerDraft> {
        val root=Path.of("artifacts/sam2-model-eval-2026-10-13-r2",name)
        val properties=Properties().also { props -> Files.newInputStream(root.resolve("packet.properties")).use(props::load) }
        val width=properties.getProperty("width").toInt()
        val height=properties.getProperty("height").toInt()
        val bytes=Files.readAllBytes(root.resolve("source.argb"))
        require(bytes.size==width*height*4)
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val source=PartRaster(width,height,IntArray(width*height) { buffer.int })
        val records=(0 until properties.getProperty("records").toInt()).map { i ->
            PromptMaskRecord(properties.getProperty("$i.id"),properties.getProperty("$i.kind"),
                             Files.readAllBytes(root.resolve(properties.getProperty("$i.mask"))),
                             properties.getProperty("$i.quality").toFloat(),
                             properties.getProperty("$i.accepted").toBooleanStrict())
        }
        val draft=PromptMaskBridge.assemble(source,properties.getProperty("sourceArgbSha"),records,PixelMask.empty(width,height))
        val union=Files.readAllBytes(root.resolve("accepted-union.mask"))
        assertEquals(PixelMask.fromBooleans(width,height,BooleanArray(union.size) { union[it]==1.toByte() }),draft.foreground)
        assertArrayEquals(source.selectedArgb(draft.foreground),draft.recomposeArgb())
        assertFalse(draft.identityVerified)
        return source to draft
    }
    @Test fun publicPhotoModelMasksPassCrossLanguagePixelSourceBinding() {
        val (source,draft)=readCase("astronaut")
        assertEquals(512,source.width)
        assertTrue(draft.foreground.count>0)
    }
    @Test fun actualColoredMasksRecomposeAcceptedPixelsWithoutClaimingGroundTruthCoverage() {
        val (_,draft)=readCase("colored_oc")
        assertEquals(184262,draft.foreground.count)
        assertTrue(draft.retained.count>0)
    }
    @Test fun actualRejectedLineWingAndWeaponDoNotEnterSemanticPlanes() {
        val (_,draft)=readCase("line_oc")
        assertEquals(204817,draft.foreground.count)
        assertFalse(draft.planes.any { it.id in setOf("wing_12","weapon_01") })
    }
}
