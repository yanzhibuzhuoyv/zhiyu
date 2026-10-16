package rig2d.parts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

/** Cross-language fixture, explicitly not the output of a segmentation model. */
class PartFixtureIntegrationTest {
    private val root=Path.of("artifacts/parts-fixture-2026-10-13-r1")
    private fun mask(relative: String): PixelMask {
        val bytes=Files.readAllBytes(root.resolve(relative))
        require(bytes.size==256*256 && bytes.all { it==0.toByte() || it==1.toByte() })
        return PixelMask.fromBooleans(256,256,BooleanArray(bytes.size) { bytes[it]==1.toByte() })
    }
    private fun raster(): PartRaster {
        val bytes=Files.readAllBytes(root.resolve("source.argb"))
        require(bytes.size==256*256*4)
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return PartRaster(256,256,IntArray(256*256) { buffer.int })
    }
    private fun candidates(): List<PartCandidate> = (1..11).map { number ->
        val id="wing_%02d".format(number)
        PartCandidate(id,"accessory",mask("instances/$id.mask"),(.94-.01*number).toFloat())
    } + listOf("weapon_01" to "accessory","back_hair" to "back_hair", "face" to "face",
               "front_hair" to "front_hair", "body" to "body").map { (id,kind) ->
        PartCandidate(id,kind,mask("instances/$id.mask"),.91f)
    }
    @Test fun modelSubjectAloneCannotClaimRecoveryOfTheMissingWing() {
        val draft=PartLayerAssembler.assemble(raster(),mask("subject.mask"),PixelMask.empty(256,256),candidates())
        assertEquals(872,mask("foreground.mask").subtract(draft.foreground).count)
        assertFalse(draft.identityVerified)
    }
    @Test fun protectedWingsHairAndWeaponRestoreEveryArgbPixelIncludingTinyTransparency() {
        val source=raster()
        val draft=PartLayerAssembler.assemble(source,mask("subject.mask"),mask("protected.mask"),candidates())
        assertEquals(18304,draft.foreground.count)
        assertEquals(mask("foreground.mask"),draft.foreground)
        assertEquals(2,draft.ambiguous.count)
        assertEquals(913,draft.retained.count)
        assertEquals(16,draft.planes.size)
        assertArrayEquals(source.toArgb(),draft.recomposeArgb())
        assertEquals(163,draft.recomposeArgb().count { (it ushr 24) in 1..254 })
        assertTrue(draft.reviewRequired)
    }
    @Test fun allTwelveGroundTruthWingMasksRemainCoveredIncludingTheUnclassifiedWing() {
        val draft=PartLayerAssembler.assemble(raster(),mask("subject.mask"),mask("protected.mask"),candidates())
        for (number in 1..12) {
            val wing=mask("ground_truth/wing_%02d.mask".format(number))
            assertTrue(wing.count>0)
            assertEquals(0,wing.subtract(draft.foreground).count)
        }
        val missingWing=mask("ground_truth/wing_12.mask")
        assertEquals(0,missingWing.subtract(draft.retained).count)
        assertFalse(draft.planes.any { it.id=="wing_12" })
    }
    @Test fun largestSupportedFramePreservesItsFarEdgeWithoutUnboundedLayers() {
        val side=PixelMask.MAX_SIDE
        val pixels=IntArray(side*side); pixels[pixels.lastIndex]=0x01112233
        val source=PartRaster(side,side,pixels)
        val subject=PixelMask.fromIndices(side,side,intArrayOf(pixels.lastIndex))
        val draft=PartLayerAssembler.assemble(source,subject,PixelMask.empty(side,side),emptyList())
        assertEquals(1,draft.foreground.count)
        assertEquals(0x01112233,draft.recomposeArgb().last())
        assertArrayEquals(pixels,draft.recomposeArgb())
    }
}
