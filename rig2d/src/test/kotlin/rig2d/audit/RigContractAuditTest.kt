package rig2d.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import rig2d.domain.Fixtures
import rig2d.domain.OcPackageSha
import rig2d.domain.OcRigAssetRules
import rig2d.domain.OcRigRegionV1
import rig2d.domain.OcRigV1
import rig2d.domain.OcViewBlendRules

/** Independent import/blending regressions; all references are synthetic, no Android/media/API I/O. */
class RigContractAuditTest {
    @Test fun blendFirstItemIsTheDominantViewThroughoutTheDomain() {
        for (step in -360..360) {
            val yaw = step / 4f
            val weights = OcViewBlendRules.blend(Fixtures.CANONICAL_BANDS, yaw)
            assertEquals("yaw=$yaw: first item must be the published primary view",
                OcViewBlendRules.nearest(Fixtures.CANONICAL_BANDS, yaw), weights.first().viewId)
            assertTrue("yaw=$yaw: weights must be descending",
                weights.zipWithNext().all { (left, right) -> left.weight >= right.weight })
        }
    }

    @Test fun nearestRejectsNonFiniteAndUncoveredAnglesLikeBlend() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -91f, 91f).forEach { yaw ->
            assertThrows("nearest must fail closed for yaw=$yaw", IllegalArgumentException::class.java) {
                OcViewBlendRules.nearest(Fixtures.CANONICAL_BANDS, yaw)
            }
        }
        val three = Fixtures.CANONICAL_BANDS.filter { kotlin.math.abs(it.center) < 90f }
        assertThrows(IllegalArgumentException::class.java) { OcViewBlendRules.nearest(three, 80f) }
    }

    @Test fun checkedSnapshotOwnsTheProxyPlane() {
        val plane = mutableListOf(0f, 0f, 1f, 0f)
        val source = Fixtures.fiveViewAsset().let { it.copy(proxy = it.proxy!!.copy(symmetryPlane = plane)) }
        val checked = OcRigAssetRules.check(source)
        val digest = OcPackageSha.v2(checked)
        plane[3] = .25f
        assertEquals("caller must not rewrite a validated snapshot", listOf(0f, 0f, 1f, 0f), checked.proxy!!.symmetryPlane)
        assertEquals(digest, OcPackageSha.v2(checked))
    }

    @Test fun checkedSnapshotOwnsEveryViewRig() {
        val regions = mutableListOf(OcRigRegionV1("head", "head"))
        val source = Fixtures.fiveViewAsset().let { asset ->
            asset.copy(views = asset.views.mapIndexed { index, view ->
                if (index == 0) view.copy(rig = OcRigV1(regions)) else view
            })
        }
        val checked = OcRigAssetRules.check(source)
        val digest = OcPackageSha.v2(checked)
        regions[0] = regions[0].copy(strength = .2f)
        assertEquals(1f, checked.views.first { it.viewId == "front" }.rig.regions.single().strength, 0f)
        assertEquals(digest, OcPackageSha.v2(checked))
    }

    @Test fun everyProxyAssetReferenceRejectsBadShaOrNegativeVisualVersion() {
        val source = Fixtures.fiveViewAsset()
        val proxy = source.proxy!!
        val normal = proxy.normal!!
        val invalid = listOf(
            proxy.copy(normal = normal.copy(sha256 = "not-a-sha")),
            proxy.copy(mesh = proxy.mesh.copy(visualVersion = -1)),
            proxy.copy(depth = proxy.depth.copy(visualVersion = -1)),
            proxy.copy(normal = normal.copy(visualVersion = -1)),
        )
        invalid.forEach { candidate ->
            assertFalse("all proxy refs share the asset SHA/version contract",
                OcRigAssetRules.isValid(source.copy(proxy = candidate)))
        }
    }

    @Test fun unknownProxyVersionIsRejected() {
        val source = Fixtures.fiveViewAsset()
        val proxy = source.proxy!!
        assertFalse(OcRigAssetRules.isValid(source.copy(proxy = proxy.copy(version = 0))))
        assertFalse(OcRigAssetRules.isValid(source.copy(proxy = proxy.copy(version = 2))))
    }

    @Test fun v2ReferencesRequireSafeBoundedIdsWithoutImposingV1HexIds() {
        val source = Fixtures.fiveViewAsset()
        val proxy = source.proxy!!
        listOf("", "../outside", "a/b", "a\\b", "x".repeat(81)).forEach { unsafe ->
            val layer = source.views.first().layers.first()
            val views = source.views.mapIndexed { index, view ->
                if (index == 0) view.copy(layers = view.layers.mapIndexed { at, item ->
                    if (at == 0) layer.copy(asset = layer.asset.copy(id = unsafe)) else item
                }) else view
            }
            assertFalse("layer ref id must be safe: $unsafe", OcRigAssetRules.isValid(source.copy(views = views)))
            assertFalse("mesh ref id must be safe: $unsafe", OcRigAssetRules.isValid(source.copy(
                views = source.views.mapIndexed { index, view ->
                    if (index == 0) view.copy(mesh = view.mesh.copy(asset = view.mesh.asset.copy(id = unsafe))) else view
                })))
            assertFalse("proxy ref id must be safe: $unsafe", OcRigAssetRules.isValid(source.copy(
                proxy = proxy.copy(depth = proxy.depth.copy(id = unsafe)))))
        }
        assertTrue("existing named assets are allowed; v1 32-hex restriction must not leak into v2",
            OcRigAssetRules.isValid(source))
    }

    @Test fun viewAdequacyUsesEachLayersOwnRequirement() {
        val thin = Fixtures.fiveViewAsset(margin = .001f)
        val table = OcViewBlendRules.requiredMargins(thin)
        val repaired = thin.copy(views = thin.views.map { view ->
            view.copy(layers = view.layers.map { it.copy(margin = table.getValue(view.viewId).getValue(it.id)) })
        })
        val result = OcViewBlendRules.adequacy(repaired)
        assertTrue(result.passed)
        assertTrue("view summary must agree with the per-layer gaps", result.views.all { it.passed })
    }
}
