package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class OcCallCharacterRulesTest {
    private val reference = OcAssetRef("b".repeat(32), "c".repeat(64), 7)
    private fun saved(route: String) = OcIdentityRules.fresh(route, "a".repeat(32)).copy(
        confirmed = true, revision = 2, reference = reference,
        photoSource = if (route == "photo_subject") reference.copy(id = "d".repeat(32)) else null,
        parts = listOf(OcPart("only_wing", "wing", "left")))

    @Test fun confirmedImportedOrGeneratedImageNeedsNoSeparateEditorManifest() {
        val result = checkNotNull(OcCallCharacterRules.resolve(null, reference))
        assertTrue(result.confirmed)
        assertEquals("photo_subject", result.route)
        assertEquals(reference, result.reference)
        assertEquals(reference, result.photoSource)
    }
    @Test fun unknownEditorDraftDoesNotHideAnAlreadyConfirmedImage() {
        assertEquals(reference, OcCallCharacterRules.resolve(OcIdentityRules.fresh("unknown"), reference)?.photoSource)
    }
    @Test fun repeatedResolutionHasStableIdentityForAnimationAndBitmapOwnership() {
        val first = checkNotNull(OcCallCharacterRules.resolve(null, reference))
        assertEquals(first, OcCallCharacterRules.resolve(null, reference))
        assertEquals(reference.id, first.ocId)
        assertEquals(reference.visualVersion, first.revision)
    }
    @Test fun matchingPhotoRetainsItsOriginalSourceAndRig() {
        val saved = saved("photo_subject")
        assertEquals(saved, OcCallCharacterRules.resolve(saved, reference))
    }
    @Test fun historicalModelsUseThePublishedPictureWithoutRebuildingGeometry() {
        for (route in listOf("parts_2d", "avatar_3d")) {
            val saved = saved(route).copy(body = OcBody(headWidth = 1.2f))
            val result = checkNotNull(OcCallCharacterRules.resolve(saved, reference))
            assertEquals("photo_subject", result.route)
            assertEquals(reference, result.photoSource)
            assertTrue(result.parts.isEmpty())
            assertEquals(OcBody(), result.body)
        }
    }
    @Test fun absentConfirmedImageDoesNotReuseAnOldCharacterOrInventOne() {
        assertNull(OcCallCharacterRules.resolve(saved("avatar_3d"), null))
        assertNull(OcCallCharacterRules.resolve(null, null))
    }
    @Test fun changedIdHashOrVersionDoesNotReuseAStaleModelOrSource() {
        val saved = saved("photo_subject")
        for (fresh in listOf(reference.copy(id = "e".repeat(32)), reference.copy(sha256 = "f".repeat(64)),
            reference.copy(visualVersion = 8))) {
            val result = checkNotNull(OcCallCharacterRules.resolve(saved, fresh))
            assertEquals(fresh, result.reference)
            assertEquals(fresh, result.photoSource)
            assertTrue(result.parts.isEmpty())
        }
    }
    @Test fun unconfirmedDraftIsNotUsedForCalls() {
        val result = checkNotNull(OcCallCharacterRules.resolve(saved("parts_2d").copy(confirmed = false), reference))
        assertEquals("photo_subject", result.route)
        assertTrue(result.parts.isEmpty())
    }
    @Test fun malformedConfirmedReferenceIsRejected() {
        for (invalid in listOf(reference.copy(id = "../private"), reference.copy(sha256 = "bad"), reference.copy(visualVersion = -1))) {
            try { OcCallCharacterRules.resolve(null, invalid); fail("invalid reference accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
