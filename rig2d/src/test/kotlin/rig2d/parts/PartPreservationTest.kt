package rig2d.parts

import org.junit.Assert.*
import org.junit.Test

class PartPreservationTest {
    private fun mask(vararg indices: Int) = PixelMask.fromIndices(8, 8, indices)
    private fun source(): PartRaster = PartRaster(8, 8, IntArray(64) { i ->
        if (i == 63) 0 else ((if (i == 62) 64 else 255) shl 24) or (i * 131 + 1)
    })
    private fun candidate(id: String, indices: IntArray, confidence: Float = 1f, kind: String = "accessory") =
        PartCandidate(id, kind, PixelMask.fromIndices(8, 8, indices), confidence)

    @Test fun masksNeverAliasCallerArrays() {
        val input = booleanArrayOf(true) + BooleanArray(63)
        val checked = PixelMask.fromBooleans(8, 8, input)
        input[0] = false
        checked.toBooleans().fill(false)
        assertTrue(checked[0]); assertEquals(1, checked.count)
    }
    @Test fun bitOperationsPreserveTailAndOriginals() {
        val a = PixelMask.fromIndices(3, 23, intArrayOf(0, 63, 68))
        val b = PixelMask.fromIndices(3, 23, intArrayOf(63, 64))
        assertEquals(4, a.union(b).count)
        assertEquals(1, a.intersect(b).count)
        assertEquals(2, a.subtract(b).count)
        assertEquals(3, a.count); assertEquals(69, a.union(b).toBooleans().size)
    }
    @Test fun dimensionsAndMaskLengthsCannotOverflowOrAllocateUnboundedStorage() {
        for ((w,h) in listOf(0 to 1, -1 to 1, Int.MAX_VALUE to 2, 1537 to 1)) {
            assertThrows(IllegalArgumentException::class.java) { PixelMask.empty(w,h) }
        }
        assertThrows(IllegalArgumentException::class.java) { PixelMask.fromBooleans(8,8,BooleanArray(63)) }
        assertThrows(IllegalArgumentException::class.java) { PixelMask.fromIndices(8,8,intArrayOf(64)) }
        assertThrows(IllegalArgumentException::class.java) { mask()[64] }
        assertThrows(IllegalArgumentException::class.java) { mask().union(PixelMask.empty(4,16)) }
    }
    @Test fun confidencePixelsRejectInvalidValuesAndKeepBoundary() {
        val values = FloatArray(64) { .1f }; values[3] = .8f
        val kept = PixelMask.fromConfidence(8,8,values,.8f)
        values[3] = 0f; assertTrue(kept[3]); assertEquals(1,kept.count)
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 1.1f)) {
            val bad = FloatArray(64) { .9f }; bad[63] = invalid
            assertThrows(IllegalArgumentException::class.java) { PixelMask.fromConfidence(8,8,bad,.8f) }
            assertThrows(IllegalArgumentException::class.java) { PixelMask.fromConfidence(8,8,FloatArray(64),invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { PixelMask.fromConfidence(8,8,FloatArray(63),.8f) }
    }
    @Test fun rasterCopiesArgbAndHashesDimensionsAndHalfTransparency() {
        val raw = IntArray(64) { 0x40ABCDEF }; val raster=PartRaster(8,8,raw)
        raw.fill(0); raster.toArgb().fill(0)
        assertEquals(0x40ABCDEF, raster.toArgb()[0])
        assertEquals(64, raster.alphaMask().count)
        assertNotEquals(raster.pixelSha256,PartRaster(4,16,IntArray(64) { 0x40ABCDEF }).pixelSha256)
        assertNotEquals(raster.pixelSha256,PartRaster(8,8,IntArray(64) { 0x41ABCDEF }).pixelSha256)
        assertThrows(IllegalArgumentException::class.java) { PartRaster(8,8,IntArray(63)) }
    }
    @Test fun twelveSeparateWingsRemainTwelveInstances() {
        val proposals=(0 until 12).map { candidate("wing_%02d".format(it),intArrayOf(it)) }
        val draft=PartLayerAssembler.assemble(source(),mask(*(0 until 12).toList().toIntArray()),mask(),proposals)
        assertEquals(12,draft.planes.size); assertEquals(0,draft.retained.count)
        assertFalse(draft.reviewRequired); assertFalse(draft.identityVerified)
        assertArrayEquals(source().selectedArgb(draft.foreground),draft.recomposeArgb())
    }
    @Test fun protectedSinglePixelWeaponCanRestoreModelSubjectOmission() {
        val draft=PartLayerAssembler.assemble(source(),mask(0,1),mask(62),emptyList())
        assertEquals(3,draft.foreground.count); assertTrue(draft.retained[62])
        assertEquals(source().toArgb()[62],draft.recomposeArgb()[62])
        assertTrue(draft.reviewRequired)
    }
    @Test fun lowConfidenceForegroundSurvivesWithoutBecomingAnInventedSemanticClass() {
        val draft=PartLayerAssembler.assemble(source(),mask(3,4),mask(),listOf(candidate("unknown",intArrayOf(3,4),.79f)))
        assertTrue(draft.planes.isEmpty()); assertEquals(2,draft.retained.count)
        assertArrayEquals(source().selectedArgb(mask(3,4)),draft.recomposeArgb())
    }
    @Test fun overlappingPartsGoToStaticRetainedLayerInsteadOfArbitraryPriority() {
        val proposals=listOf(candidate("face",intArrayOf(1,2),1f,"face"),candidate("hair",intArrayOf(2,3),.9f,"front_hair"))
        val draft=PartLayerAssembler.assemble(source(),mask(1,2,3,4),mask(),proposals)
        assertEquals(1,draft.ambiguous.count); assertTrue(draft.retained[2]); assertTrue(draft.retained[4])
        assertEquals(2,draft.planes.sumOf { it.mask.count })
        assertArrayEquals(source().selectedArgb(mask(1,2,3,4)),draft.recomposeArgb())
    }
    @Test fun backgroundClaimsCannotIntroducePixelsOutsideSelectedForeground() {
        val draft=PartLayerAssembler.assemble(source(),mask(1),mask(),listOf(candidate("face",intArrayOf(0,1,63),1f,"face")))
        assertEquals(1,draft.planes.single().mask.count)
        assertEquals(0,draft.recomposeArgb()[0]); assertEquals(0,draft.recomposeArgb()[63])
    }
    @Test fun transparentSourcePixelsAreNotInventedByProtection() {
        val draft=PartLayerAssembler.assemble(source(),mask(1),mask(63),emptyList())
        assertEquals(1,draft.foreground.count); assertFalse(draft.foreground[63])
        assertThrows(IllegalArgumentException::class.java) { PartLayerAssembler.assemble(source(),mask(63),mask(),emptyList()) }
    }
    @Test fun reorderedCandidateInputsProduceSamePartitionsAndStableIds() {
        val proposals=listOf(candidate("b",intArrayOf(1,2)),candidate("a",intArrayOf(2,3)))
        val first=PartLayerAssembler.assemble(source(),mask(1,2,3),mask(),proposals)
        val second=PartLayerAssembler.assemble(source(),mask(1,2,3),mask(),proposals.reversed())
        assertEquals(first.planes,second.planes); assertEquals(first.retained,second.retained)
        assertEquals(listOf("a","b"),first.planes.map { it.id })
    }
    @Test fun resultListDoesNotAliasOrAllowMutation() {
        val proposals=mutableListOf(candidate("wing",intArrayOf(0)))
        val draft=PartLayerAssembler.assemble(source(),mask(0),mask(),proposals)
        proposals.clear(); assertEquals(1,draft.planes.size)
        assertThrows(UnsupportedOperationException::class.java) { (draft.planes as MutableList).clear() }
    }
    @Test fun invalidCandidatesAreRejectedEvenWhenTheirConfidenceWouldBeIgnored() {
        for (bad in listOf(candidate("../wing",intArrayOf(0)),candidate("w",intArrayOf(0),0f,"wing"),
                           candidate("w",intArrayOf(0),Float.NaN),candidate("w",intArrayOf(0),1.1f))) {
            assertThrows(IllegalArgumentException::class.java) { PartLayerAssembler.assemble(source(),mask(0),mask(),listOf(bad)) }
        }
    }
    @Test fun duplicateIdsWrongDimensionsAndResourceOverBudgetFailExplicitly() {
        val first=candidate("wing",intArrayOf(0))
        assertThrows(IllegalArgumentException::class.java) { PartLayerAssembler.assemble(source(),mask(0),mask(),listOf(first,first)) }
        assertThrows(IllegalArgumentException::class.java) { PartLayerAssembler.assemble(source(),PixelMask.empty(4,16),mask(),emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PartLayerAssembler.assemble(source(),mask(0),PixelMask.empty(4,16),emptyList()) }
        val wrong=PartCandidate("wing","accessory",PixelMask.empty(4,16),1f)
        assertThrows(IllegalArgumentException::class.java) { PartLayerAssembler.assemble(source(),mask(0),mask(),listOf(wrong)) }
        val tooMany=(0 until 64).map { candidate("w_$it",intArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { PartLayerAssembler.assemble(source(),mask(0),mask(),tooMany) }
        assertThrows(IllegalArgumentException::class.java) { PartLayerAssembler.assemble(source(),mask(0),mask(),listOf(first),Float.NaN) }
    }
    @Test fun candidateSnapshotStillEnforcesCapWhenCallerListGrowsDuringCapture() {
        var sizeReads=0
        val changing=object: AbstractList<PartCandidate>() {
            override val size: Int get() = if (++sizeReads==1) 1 else 64
            override fun get(index: Int) = candidate("wing_$index",intArrayOf(index))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartLayerAssembler.assemble(source(),mask(*(0 until 63).toList().toIntArray()),mask(),changing)
        }
    }
    @Test fun sixtyThreeIndependentCandidatesAndThreeWayConflictAreBounded() {
        val proposals=(0 until 63).map { candidate("layer_$it",intArrayOf(it)) }
        val full=PartLayerAssembler.assemble(source(),mask(*(0 until 63).toList().toIntArray()),mask(),proposals)
        assertEquals(63,full.planes.size); assertEquals(0,full.retained.count)
        val crossing=listOf(candidate("a",intArrayOf(0,3)),candidate("b",intArrayOf(1,3)),candidate("c",intArrayOf(2,3)))
        val draft=PartLayerAssembler.assemble(source(),mask(0,1,2,3),mask(),crossing)
        assertEquals(1,draft.ambiguous.count); assertEquals(1,draft.retained.count)
        assertEquals(3,draft.planes.sumOf { it.mask.count })
    }
    @Test fun independentCanonicalHashIncludesEveryFullChunkAndTailPixel() {
        val raster=PartRaster(33,35,IntArray(33*35) { 0x40ABCDEF+it })
        assertEquals("f866a8b6cd1fdb9cbb2dc3ca0e94b74d019e69010cb910b913e7affcf9f95ba7",raster.pixelSha256)
    }
}
