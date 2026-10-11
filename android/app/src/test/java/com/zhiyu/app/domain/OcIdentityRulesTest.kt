package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class OcIdentityRulesTest {
    @Test fun arbitraryAsymmetricAccessoriesSurviveImmutableEdits() {
        val original = OcIdentityRules.fresh("parts_2d", "a".repeat(32))
        val parts = (0 until 12).map { i -> OcPart("wing_$i", "wing", if (i == 0) "left" else "right", x = .2f + i * .02f) } +
            OcPart("sword", "weapon", "left", x = .25f, y = .58f)
        val changed = OcIdentityRules.check(original.copy(parts = parts))
        assertEquals(13, changed.parts.size)
        assertEquals("left", changed.parts.last().side)
        assertTrue(original.parts.isEmpty())
        assertEquals(changed.parts, OcIdentityRules.check(changed).parts)
    }

    @Test fun changingAppearanceRetainsIdentityAndIncrementsRevision() {
        val original = OcIdentityRules.fresh("avatar_3d", "a".repeat(32))
        val changed = OcIdentityRules.next(original, original.copy(body = original.body.copy(headWidth = 1.12f)))
        assertEquals(original.ocId, changed.ocId)
        assertEquals(original.revision + 1, changed.revision)
        assertNotEquals(OcIdentityRules.packageSha(original), OcIdentityRules.packageSha(changed))
    }

    @Test fun stillAndAnimationDoNotMutateIdentityOrMirrorParts() {
        val identity = OcIdentityRules.fresh("parts_2d", "a".repeat(32)).copy(
            parts = listOf(OcPart("one_wing", "wing", "left", x = .25f, angle = -25f)))
        val sha = OcIdentityRules.packageSha(identity)
        OcRigRules.vertices(12, 16, 100, 160, identity.rig, OcMotion(headTurn = .6f, breath = .4f))
        assertEquals(sha, OcIdentityRules.packageSha(identity))
        assertEquals("left", identity.parts.single().side)
        assertEquals(-25f, identity.parts.single().angle)
    }

    @Test fun resourceCapsAreIndependentOfAnatomy() {
        val identity = OcIdentityRules.fresh("parts_2d", "a".repeat(32))
        val parts = (0 until OcIdentityRules.MAX_PARTS).map { OcPart("p$it", "wing", "left") }
        assertEquals(OcIdentityRules.MAX_PARTS, OcIdentityRules.check(identity.copy(parts = parts)).parts.size)
        rejects { OcIdentityRules.check(identity.copy(parts = parts + OcPart("overflow", "wing", "left"))) }
        rejects { OcIdentityRules.check(identity.copy(parts = listOf(OcPart("duplicate", "wing"), OcPart("duplicate", "weapon")))) }
    }

    @Test fun invalidNumbersAndSourcesAreRejected() {
        val identity = OcIdentityRules.fresh("photo_subject", "a".repeat(32))
        rejects { OcIdentityRules.check(identity.copy(body = identity.body.copy(headWidth = Float.NaN))) }
        rejects { OcIdentityRules.check(identity.copy(reference = OcAssetRef("../private", "0".repeat(64), 1))) }
        rejects { OcIdentityRules.check(identity.copy(route = "guess_from_photo")) }
        rejects { OcIdentityRules.check(identity.copy(rig = identity.rig.copy(regions = listOf(OcRigRegion("bad", "head", x = 2f))))) }
    }

    @Test fun neutralPhotoMeshEqualsEveryOriginalCoordinate() {
        val mesh = OcRigRules.vertices(4, 6, 120, 180, OcRigRules.defaultRig(), OcMotion())
        assertEquals(5 * 7 * 2, mesh.size)
        for (row in 0..6) for (column in 0..4) {
            val at = (row * 5 + column) * 2
            assertEquals(column * 30f, mesh[at], 0f)
            assertEquals(row * 30f, mesh[at + 1], 0f)
        }
    }

    @Test fun regionalRigDeformsHeadWithoutTranslatingWholeImage() {
        val rig = OcRig(listOf(OcRigRegion("head", "head", x = .3f, y = .1f, width = .4f, height = .3f)))
        val moved = OcRigRules.vertices(10, 10, 100, 100, rig, OcMotion(headTurn = 1f))
        assertEquals(0f, moved[0], 0f)
        assertEquals(100f, moved[moved.size - 1], 0f)
        assertNotEquals(50f, moved[(2 * 11 + 5) * 2])
    }

    @Test fun overlappingRigRegionsBlendInsteadOfMultiplyingDisplacementByRegionCount() {
        val rig = OcRig((0 until 128).map { OcRigRegion("head_$it", "head", strength = 2f) })
        val moved = OcRigRules.vertices(10, 10, 100, 100, rig, OcMotion(headTurn = 1f))
        assertTrue(moved.all { it.isFinite() })
        assertEquals(0f, moved[0], 0f)
        val at = (2 * 11 + 5) * 2
        assertTrue(moved[at] > 50f)
        assertTrue(moved[at] <= 54f)
    }

    @Test fun meshGenerationCreatesFiniteDepthGeometryAndKeepsAccessories() {
        val identity = OcIdentityRules.fresh("avatar_3d", "a".repeat(32))
        val base = AvatarMeshFactory.build(identity, OcMotion())
        val equipped = AvatarMeshFactory.build(identity.copy(parts = listOf(OcPart("single", "wing", "left"), OcPart("sword", "weapon", "right"))), OcMotion())
        assertTrue(base.vertices.isNotEmpty())
        assertTrue(base.vertices.all { it.isFinite() })
        assertTrue(base.vertices.filterIndexed { index, _ -> index % 10 == 2 }.any { it != 0f })
        assertTrue(equipped.vertexCount > base.vertexCount)
    }

    @Test fun maximumWingCountStaysBoundedAndAnimationKeepsTheSameGeometryContract() {
        val identity = OcIdentityRules.fresh("avatar_3d", "a".repeat(32)).copy(parts =
            (0 until OcIdentityRules.MAX_PARTS).map { OcPart("wing_$it", "wing", if (it % 3 == 0) "left" else "right") })
        val sha = OcIdentityRules.packageSha(identity)
        val neutral = AvatarMeshFactory.build(identity)
        val moving = AvatarMeshFactory.build(identity, OcMotion(breath = 1f, blink = 1f, mouthOpen = .8f, accessorySway = 1f))
        assertEquals(neutral.vertexCount, moving.vertexCount)
        assertTrue(neutral.vertices.size <= 5_000_000)
        assertTrue(moving.vertices.all { it.isFinite() })
        assertFalse(neutral.vertices.contentEquals(moving.vertices))
        assertEquals(sha, OcIdentityRules.packageSha(identity))
        assertEquals(OcIdentityRules.MAX_PARTS, identity.parts.size)
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("expected validation failure") } catch (_: IllegalArgumentException) { }
    }
}
