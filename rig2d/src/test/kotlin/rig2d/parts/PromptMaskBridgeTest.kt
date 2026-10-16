package rig2d.parts

import org.junit.Assert.*
import org.junit.Test
import rig2d.domain.OcRigAssetRules
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

class PromptMaskBridgeTest {
    private fun source() = PartRaster(8, 8, IntArray(64) { i ->
        if (i == 63) 0 else ((if (i == 62) 64 else 255) shl 24) or (i * 131 + 1)
    })
    private fun mask(vararg indices: Int) = PixelMask.fromIndices(8, 8, indices)
    private fun record(
        id: String = "part", indices: IntArray = intArrayOf(0), quality: Float = 1f,
        accepted: Boolean = true, kind: String = "accessory",
    ) = PromptMaskRecord(id, kind, ByteArray(64) { if (it in indices) 1 else 0 }, quality, accepted)

    private fun assemble(
        records: List<PromptMaskRecord>, protected: PixelMask = mask(), minimumQuality: Float = .8f,
    ): PartLayerDraft {
        val source = source()
        return PromptMaskBridge.assemble(source, source.pixelSha256, records, protected, minimumQuality)
    }

    @Test fun acceptedMasksFormSubjectAndKeepArgbIncludingPartialAlpha() {
        val draft = assemble(listOf(record("face", intArrayOf(1), kind = "face"), record("weapon", intArrayOf(62))))
        assertEquals(mask(1, 62), draft.foreground)
        assertEquals(2, draft.planes.size)
        assertEquals(0, draft.retained.count)
        assertArrayEquals(source().selectedArgb(mask(1, 62)), draft.recomposeArgb())
        assertFalse(draft.reviewRequired)
        assertFalse(draft.identityVerified)
    }

    @Test fun sourceBindingRejectsDifferentPixelsEvenWithSameDimensions() {
        val original = source()
        val changed = PartRaster(8, 8, original.toArgb().also { it[0] = it[0] xor 1 })
        assertThrows(IllegalArgumentException::class.java) {
            PromptMaskBridge.assemble(changed, original.pixelSha256, listOf(record()), mask())
        }
    }

    @Test fun sourceBindingIncludesDimensionsAndDoesNotAcceptPngFileDigest() {
        val original = source()
        val changedShape = PartRaster(4, 16, original.toArgb())
        assertThrows(IllegalArgumentException::class.java) {
            PromptMaskBridge.assemble(changedShape, original.pixelSha256, listOf(record()), PixelMask.empty(4, 16))
        }
        val png = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        png.setRGB(0, 0, 8, 8, original.toArgb(), 0, 8)
        val encoded = ByteArrayOutputStream()
        assertTrue(ImageIO.write(png, "png", encoded))
        val fileSha = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertNotEquals(original.pixelSha256, fileSha)
        assertThrows(IllegalArgumentException::class.java) {
            PromptMaskBridge.assemble(original, fileSha, listOf(record()), mask())
        }
    }

    @Test fun invalidSourceBindingsCannotPassThroughAsUnverifiedDrafts() {
        val original = source()
        for (sha in listOf("", "0".repeat(64), original.pixelSha256.uppercase(), "../source.png")) {
            assertThrows(IllegalArgumentException::class.java) {
                PromptMaskBridge.assemble(original, sha, listOf(record()), mask())
            }
        }
    }

    @Test fun rejectedRecordsCannotAddSubjectPixelsOrCandidatePlanes() {
        val draft = assemble(listOf(record("accepted", intArrayOf(0)), record("rejected", intArrayOf(1, 2), accepted = false)))
        assertEquals(mask(0), draft.foreground)
        assertEquals(listOf("accepted"), draft.planes.map { it.id })
        assertEquals(0, draft.recomposeArgb()[1])
        assertEquals(0, draft.recomposeArgb()[2])
    }

    @Test fun allRejectedRecordsKeepOnlyExplicitStaticProtection() {
        val draft = assemble(listOf(record("rejected", intArrayOf(0, 1), accepted = false)), mask(62))
        assertEquals(mask(62), draft.foreground)
        assertEquals(mask(62), draft.retained)
        assertTrue(draft.planes.isEmpty())
        assertTrue(draft.reviewRequired)
        assertEquals(source().toArgb()[62], draft.recomposeArgb()[62])
        assertEquals(0, draft.recomposeArgb()[0])
    }

    @Test fun emptySubjectsFailUnlessOpaqueStaticProtectionRemains() {
        for (records in listOf(emptyList(), listOf(record(accepted = false)), listOf(record(indices = intArrayOf(), accepted = false)))) {
            assertThrows(IllegalArgumentException::class.java) { assemble(records) }
        }
        val protectedOnly = assemble(emptyList(), mask(62))
        assertEquals(mask(62), protectedOnly.retained)
        assertThrows(IllegalArgumentException::class.java) { assemble(emptyList(), mask(63)) }
    }

    @Test fun acceptedFlagRejectsBelowThresholdQualityAndEmptyMask() {
        assertThrows(IllegalArgumentException::class.java) { assemble(listOf(record(quality = .799f)), mask(62)) }
        assertThrows(IllegalArgumentException::class.java) { assemble(listOf(record(indices = intArrayOf())), mask(62)) }
        val threshold = assemble(listOf(record(quality = .8f)))
        assertEquals(1, threshold.planes.size)
    }

    @Test fun rejectedLowQualityAndEmptyMasksAreValidWithoutBecomingSubject() {
        val draft = assemble(listOf(record("low", quality = .2f, accepted = false),
            record("empty", intArrayOf(), 0f, false)), mask(62))
        assertEquals(mask(62), draft.foreground)
        assertTrue(draft.planes.isEmpty())
    }

    @Test fun malformedRecordsFailEvenWhenRejected() {
        val malformed = listOf(
            record(id = "../part", accepted = false),
            record(kind = "wing", accepted = false),
            record(quality = Float.NaN, accepted = false),
            record(accepted = false).copy(pixels = ByteArray(63)),
            record(accepted = false).copy(pixels = ByteArray(64) { if (it == 63) 2 else 0 }),
        )
        malformed.forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { assemble(listOf(bad), mask(62)) }
        }
    }

    @Test fun binaryBytesRejectWrongLengthsUnsigned255AndNegativeValues() {
        val invalidArrays = listOf(ByteArray(0), ByteArray(63), ByteArray(65),
            ByteArray(64).also { it[0] = 2 }, ByteArray(64).also { it[63] = (-1).toByte() },
            ByteArray(64).also { it[32] = Byte.MIN_VALUE })
        invalidArrays.forEach { pixels ->
            assertThrows(IllegalArgumentException::class.java) { assemble(listOf(record().copy(pixels = pixels)), mask(62)) }
        }
    }

    @Test fun allQualityAndThresholdValuesMustBeFiniteProbabilities() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.001f, 1.001f)) {
            assertThrows(IllegalArgumentException::class.java) { assemble(listOf(record(quality = invalid)), mask(62)) }
            assertThrows(IllegalArgumentException::class.java) { assemble(listOf(record()), mask(62), invalid) }
        }
        assertEquals(1, assemble(listOf(record(quality = 0f)), minimumQuality = 0f).planes.size)
        assertEquals(1, assemble(listOf(record(quality = 1f)), minimumQuality = 1f).planes.size)
    }

    @Test fun everyDeclaredExistingKindIsPreservedWithoutClaimingIdentityVerification() {
        val records = OcRigAssetRules.layerKinds.mapIndexed { index, kind -> record("part_$index", intArrayOf(index), kind = kind) }
        val draft = assemble(records)
        assertEquals(19, draft.planes.size)
        assertEquals(OcRigAssetRules.layerKinds, draft.planes.map { it.kind }.toSet())
        assertFalse(draft.identityVerified)
    }

    @Test fun invalidAndDuplicateIdsFailAcrossAcceptedAndRejectedRecords() {
        for (id in listOf("", "a".repeat(81), "a b", "部位", "../wing")) {
            assertThrows(IllegalArgumentException::class.java) { assemble(listOf(record(id = id)), mask(62)) }
        }
        assertEquals(1, assemble(listOf(record(id = "a".repeat(80)))).planes.size)
        assertThrows(IllegalArgumentException::class.java) { assemble(listOf(record("same"), record("same", accepted = false)), mask(62)) }
        assertThrows(IllegalArgumentException::class.java) { assemble(listOf(record("same", accepted = false), record("same", accepted = false)), mask(62)) }
    }

    @Test fun returnedDraftDoesNotAliasMutableRecordBytesOrList() {
        val first = record(indices = intArrayOf(0, 62))
        val input = mutableListOf(first)
        val draft = assemble(input)
        first.pixels.fill(0)
        input.clear()
        draft.source.toArgb().fill(0)
        assertEquals(mask(0, 62), draft.foreground)
        assertEquals(mask(0, 62), draft.planes.single().mask)
        assertArrayEquals(source().selectedArgb(mask(0, 62)), draft.recomposeArgb())
        assertThrows(UnsupportedOperationException::class.java) { (draft.planes as MutableList).clear() }
    }

    @Test fun byteSnapshotOccursBeforeReadingLaterCallerRecords() {
        val first = record("first", intArrayOf(0))
        val input = object : AbstractList<PromptMaskRecord>() {
            override val size = 2
            override fun get(index: Int): PromptMaskRecord = when (index) {
                0 -> first
                else -> { first.pixels.fill(0); record("second", intArrayOf(1)) }
            }
        }
        val draft = assemble(input)
        assertEquals(mask(0, 1), draft.foreground)
        assertEquals(listOf("first", "second"), draft.planes.map { it.id })
    }

    @Test fun twelveSeparateWingPromptsRemainTwelveAccessoryInstances() {
        val records = (0 until 12).map { record("wing_%02d".format(it), intArrayOf(it)) }
        val draft = assemble(records)
        assertEquals(12, draft.planes.size)
        assertEquals((0 until 12).map { "wing_%02d".format(it) }, draft.planes.map { it.id })
        assertTrue(draft.planes.all { it.kind == "accessory" && it.mask.count == 1 })
        assertFalse(draft.reviewRequired)
    }

    @Test fun threeWayConflictsAndUnexplainedProtectionRemainStatic() {
        val records = listOf(record("a", intArrayOf(0, 3)), record("b", intArrayOf(1, 3)), record("c", intArrayOf(2, 3)))
        val draft = assemble(records, mask(62))
        assertEquals(mask(3), draft.ambiguous)
        assertEquals(mask(3, 62), draft.retained)
        assertEquals(3, draft.planes.sumOf { it.mask.count })
        assertTrue(draft.reviewRequired)
        assertArrayEquals(source().selectedArgb(mask(0, 1, 2, 3, 62)), draft.recomposeArgb())
        assertEquals(draft.planes, assemble(records.reversed(), mask(62)).planes)
    }

    @Test fun transparentPixelsCannotEnterReconstructionAndWrongProtectionShapeFails() {
        val draft = assemble(listOf(record(indices = intArrayOf(0, 63))), mask(63))
        assertEquals(mask(0), draft.foreground)
        assertEquals(0, draft.recomposeArgb()[63])
        assertThrows(IllegalArgumentException::class.java) { assemble(listOf(record(indices = intArrayOf(63)))) }
        assertThrows(IllegalArgumentException::class.java) { assemble(listOf(record()), PixelMask.empty(4, 16)) }
    }

    @Test fun resourceCapCountsRejectedRecordsAndAcceptsSixtyThreeInstances() {
        val full = assemble((0 until 63).map { record("part_$it", intArrayOf(it)) })
        assertEquals(63, full.planes.size)
        assertEquals(63, full.foreground.count)
        assertThrows(IllegalArgumentException::class.java) {
            assemble((0 until 64).map { record("part_$it", accepted = false) }, mask(62))
        }
    }

    @Test fun growingListCannotEvadeSnapshotCapEvenWhenInitialSizeIsSmall() {
        var sizeReads = 0
        val growing = object : AbstractList<PromptMaskRecord>() {
            override val size: Int get() = if (++sizeReads == 1) 1 else 64
            override fun get(index: Int) = record("part_$index", accepted = false)
        }
        assertThrows(IllegalArgumentException::class.java) { assemble(growing, mask(62)) }
    }
}
