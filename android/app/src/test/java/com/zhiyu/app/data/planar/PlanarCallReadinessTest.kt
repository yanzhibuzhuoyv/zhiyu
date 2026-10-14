package com.zhiyu.app.data.planar

import org.junit.Assert.*
import org.junit.Test
import rig2d.planar.PlanarMesh

class PlanarCallReadinessTest {
    private val fixtures=PlanarPackageFixtures
    private val reader=PlanarPackageReader(fixtures.contract)
    private val doc=reader.read(fixtures.archive().inputStream())
    private val fixed="""{"metadata":{"generator":"android-fixed-scene-v2","backgroundRetained":true,"backgroundFixed":true,"sourceAlphaPreserved":true,"subjectMaskMeaning":"motion_constraint","suggestionsConfirmed":true}}"""
    @Test fun oldCharacterPackageIsEditableButNotReadyForBackgroundPreservingCalls() {
        assertFalse(PlanarCallReadiness.fixedBackground(doc))
        assertFalse(PlanarCallReadiness.fixedBackground(doc.copy(layers=emptyList(),definitionJson=fixed)))
    }
    @Test fun forgedFixedMetadataCannotHideMovingBackgroundBasis() {
        val base=doc.layers.first()
        val mesh=base.mesh
        val moving=PlanarMesh(doc.contract,mesh.baseVertices(),mesh.uv(),mesh.indices(),mapOf("headX" to FloatArray(mesh.baseVertices().size){.1f}))
        val falseClaim=doc.copy(definitionJson=fixed,layers=listOf(base.copy(role="base",mesh=moving)))
        assertFalse(PlanarCallReadiness.fixedBackground(falseClaim))
        assertTrue(PlanarCallReadiness.fixedBackground(doc.copy(definitionJson=fixed)))
        assertFalse(PlanarCallReadiness.fixedBackground(doc.copy(definitionJson=fixed.replace("\"backgroundFixed\":true","\"backgroundFixed\":false"))))
        assertFalse(PlanarCallReadiness.fixedBackground(doc.copy(definitionJson=fixed.replace("\"suggestionsConfirmed\":true","\"suggestionsConfirmed\":false"))))
    }
}
