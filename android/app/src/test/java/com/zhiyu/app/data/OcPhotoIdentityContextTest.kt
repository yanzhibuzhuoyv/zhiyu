package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OcPhotoIdentityContextTest {
    private val referenceBytes = png(1)
    private val originalBytes = png(2)
    private val current = MediaAttachment("a".repeat(32), "image", MediaRules.path("a".repeat(32)), "image/png",
        ChatPhotoRules.sha(referenceBytes), null, 2, "OC 原图")
    private val profile = VisualProfile("import", "", "unspecified", "unspecified", 2, current.id, current.url,
        GenerationQuota(false, 0, 0, 2))
    private val details = LocalMediaDetails(current, "", emptyList(), "portrait")
    private val reference = OcAssetRef(current.id, current.sha256, 2)
    private val original = OcAssetRef("b".repeat(32), ChatPhotoRules.sha(originalBytes), 1)
    private val identity = OcIdentity("c".repeat(32), 1, "photo_subject", reference = reference,
        confirmed = true, photoSource = original)
    private val saved = OcIdentitySnapshot(identity, OcIdentityRules.packageSha(identity), 1)
    private fun snapshot() = requireNotNull(OcPhotoIdentitySnapshot.capture(profile, details, saved))
    private fun png(seed: Int) = byteArrayOf(-119,80,78,71,13,10,26,10) + ByteArray(16) { (seed + it).toByte() }
    private fun payload(raw: ByteArray) = ChatPhotoPayload(ChatPhotoRules.sha(raw), ChatPhotoRules.sha(raw), "image/png", raw)

    @Test fun explicitUserAttributionConfirmsOwnOcEvenForATextOnlyModel() {
        var loads = 0
        val result = OcPhotoIdentityContext({ loads++; referenceBytes }) { raw, _ -> payload(raw) }
            .build(snapshot(), ChatPhotoRules.sha(png(3)), false, 0, userConfirmedSelf = true)
        assertEquals(OcPhotoIdentityMatch.USER_CONFIRMED, result.match)
        assertNull(result.reference); assertEquals(0, loads)
        assertTrue(result.evidence.contains("明确说明"))
        assertTrue(result.evidence.contains("不要否认"))
        assertTrue(result.evidence.contains("不是现实人物身份"))
    }

    @Test fun onlyExplicitAttributionConfirmsASelfPortrait() {
        assertTrue(SelfPortraitAttribution.confirmed("这是你"))
        assertTrue(SelfPortraitAttribution.confirmed("这张像你"))
        assertTrue(SelfPortraitAttribution.confirmed("给你画的"))
        assertFalse(SelfPortraitAttribution.confirmed("这是你吗"))
        assertFalse(SelfPortraitAttribution.confirmed("这是你的猫"))
        assertFalse(SelfPortraitAttribution.confirmed("不像你"))
    }

    @Test fun identityQuestionsNegationsAndReportedClaimsAreNotUserConfirmation() {
        for (text in listOf("这不是你，这是别人的自画像", "你的画像在哪儿？", "他说这是你，但我不这么认为",
            "你看这像你吗", "这是你？", "朋友说这就是你", "如果这是你会怎么样", "不是你的画像")) {
            assertFalse(text, SelfPortraitAttribution.confirmed(text))
        }
    }

    @Test fun exactImportedOriginalIsKnownAsOwnOcEvenForATextOnlyModel() {
        var loads = 0
        val context = OcPhotoIdentityContext({ loads++; referenceBytes }) { raw, _ -> payload(raw) }
        val result = context.build(snapshot(), original.sha256, false, 0)
        assertEquals(OcPhotoIdentityMatch.EXACT_REFERENCE, result.match)
        assertTrue(result.evidence.contains("自己的设定形象"))
        assertTrue(result.evidence.contains("不是现实人物身份"))
        assertTrue(result.evidence.contains("不能执行"))
        assertNull(result.reference); assertEquals(0, loads)
    }

    @Test fun staleCreatorPhotoIsNotSilentlyLinkedToANewerVisualVersion() {
        val newer = requireNotNull(OcPhotoIdentitySnapshot.capture(profile.copy(visualVersion = 3), details, saved))
        assertNull(newer.original)
        val result = OcPhotoIdentityContext().build(newer, original.sha256, false, 0)
        assertEquals(OcPhotoIdentityMatch.UNVERIFIED, result.match)
        assertFalse(result.evidence.contains("同一份原图"))
        assertNotEquals(snapshot(), newer)
    }

    @Test fun nativeVisionReceivesABoundedCurrentReferenceForComparisonWithoutClaimingAMatch() {
        var observedBudget = 0
        val context = OcPhotoIdentityContext({ ref -> assertEquals(reference, ref); referenceBytes }) { raw, limit ->
            observedBudget = limit; payload(raw)
        }
        val result = context.build(snapshot(), ChatPhotoRules.sha(png(3)), true, 1000)
        assertEquals(OcPhotoIdentityMatch.REFERENCE_COMPARISON, result.match)
        assertEquals(current.sha256, result.reference?.originalSha256)
        assertTrue(observedBudget in 1..512 * 1024)
        assertTrue(result.evidence.contains("不确定")); assertTrue(result.evidence.contains("不能执行"))
        assertFalse(result.evidence.contains("同一份原图"))
    }

    @Test fun aTextOnlyModelNeverReceivesReferencePixelsAndCannotPretendToHaveComparedFaces() {
        var calls = 0
        val result = OcPhotoIdentityContext({ calls++; referenceBytes }) { raw, _ -> payload(raw) }
            .build(snapshot(), ChatPhotoRules.sha(png(3)), false, 0)
        assertEquals(OcPhotoIdentityMatch.UNVERIFIED, result.match)
        assertNull(result.reference); assertEquals(0, calls)
        assertTrue(result.evidence.contains("不能假装"))
    }

    @Test fun fiveMiBCombinedBudgetKeepsCurrentPhotoAndSkipsReferenceInsteadOfBreakingSend() {
        var loads = 0
        val context = OcPhotoIdentityContext({ loads++; referenceBytes }) { raw, _ -> payload(raw) }
        val full = context.build(snapshot(), ChatPhotoRules.sha(png(3)), true, ChatPhotoRules.MAX_UPLOAD_BYTES)
        assertNull(full.reference); assertEquals(0, loads)
        val tooLittle = context.build(snapshot(), ChatPhotoRules.sha(png(3)), true, ChatPhotoRules.MAX_UPLOAD_BYTES - 8)
        assertNull(tooLittle.reference)
        assertEquals(OcPhotoIdentityMatch.UNVERIFIED, tooLittle.match)
    }

    @Test fun corruptOrMismatchedReferenceDoesNotInventAnIdentityOrSuppressCancellation() {
        val photo = ChatPhotoRules.sha(png(3))
        val corrupt = OcPhotoIdentityContext({ png(4) }) { raw, _ -> payload(raw) }.build(snapshot(), photo, true, 1000)
        assertNull(corrupt.reference); assertEquals(OcPhotoIdentityMatch.UNVERIFIED, corrupt.match)
        val wrong = OcPhotoIdentityContext({ referenceBytes }) { _, _ -> payload(png(4)) }.build(snapshot(), photo, true, 1000)
        assertNull(wrong.reference); assertEquals(OcPhotoIdentityMatch.UNVERIFIED, wrong.match)
        assertThrows(CancellationException::class.java) {
            OcPhotoIdentityContext({ throw CancellationException("cancel") }).build(snapshot(), photo, true, 1000)
        }
    }

    @Test fun noConfirmedOcOrUnrelatedLibraryAssetCannotBecomeTrustedOwnershipEvidence() {
        assertNull(OcPhotoIdentitySnapshot.capture(profile.copy(mode = "none", referenceId = null), null, saved))
        assertNull(OcPhotoIdentitySnapshot.capture(profile, details.copy(purpose = "library"), saved))
        assertNull(OcPhotoIdentitySnapshot.capture(profile.copy(referenceId = "d".repeat(32)), details, saved))
        val empty = OcPhotoIdentityContext().build(null, current.sha256, false, 0)
        assertEquals(OcPhotoIdentityMatch.NONE, empty.match); assertEquals("", empty.evidence)
    }

    @Test fun aTextConversationKeepsTheCurrentConfirmedAppearanceAsBoundedIndependentPixels() {
        var calls = 0
        val result = OcPhotoIdentityContext({ ref -> calls++; assertEquals(reference, ref); referenceBytes }) { raw, budget ->
            assertTrue(budget in 1..OcPhotoIdentityContext.MAX_REFERENCE_BYTES); payload(raw)
        }.appearance(snapshot(), nativeVision = true)
        assertEquals(1, calls)
        assertEquals(OcPhotoIdentityMatch.APPEARANCE_REFERENCE, result.match)
        assertEquals(snapshot(), result.snapshot)
        assertArrayEquals(referenceBytes, result.reference?.bytes)
        assertTrue(result.evidence.contains("固定外貌"))
        assertTrue(result.evidence.contains("不是用户新消息"))
        assertFalse(result.evidence.contains("同一份原图"))
    }

    @Test fun appearanceAnchorCannotInventPixelsForTextOnlyOrBrokenReference() {
        var loads = 0
        val text = OcPhotoIdentityContext({ loads++; referenceBytes }) { raw, _ -> payload(raw) }
            .appearance(snapshot(), nativeVision = false)
        assertEquals(0, loads); assertNull(text.reference)
        assertTrue(text.evidence.contains("不要编造"))
        val broken = OcPhotoIdentityContext({ png(4) }) { raw, _ -> payload(raw) }
            .appearance(snapshot(), nativeVision = true)
        assertNull(broken.reference)
        assertFalse(broken.evidence.contains("本次附带"))
        assertThrows(CancellationException::class.java) {
            OcPhotoIdentityContext({ throw CancellationException("cancel") }).appearance(snapshot(), true)
        }
    }

}
