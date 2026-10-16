package rig2d.planar

import org.junit.Assert.*
import org.junit.Test

class PlanarMeshTest {
    private val positions = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f)
    private val textureUv = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f)
    private val triangles = intArrayOf(0, 1, 2)

    private fun mesh(base: FloatArray = positions, uv: FloatArray = textureUv,
                     indices: IntArray = triangles, deltas: Map<String, FloatArray> = emptyMap()) =
        PlanarMesh(contractFixture(), base, uv, indices, deltas)

    @Test fun neutralPreservesSourceVerticesExactly() {
        val mesh = squareMesh()
        assertArrayEquals(mesh.baseVertices(), PlanarMixer.vertices(mesh, PlanarParameters(mesh.contract)), 0f)
        assertEquals(4, mesh.vertexCount)
    }

    @Test fun meshSnapshotsAndReturnsIndependentPrimitiveArrays() {
        val base = positions.copyOf(); val uv = textureUv.copyOf(); val indices = triangles.copyOf()
        val basis = FloatArray(6) { 1f }; val deltas = mutableMapOf("headX" to basis)
        val mesh = mesh(base, uv, indices, deltas)
        base[0] = 999f; uv[0] = 1f; indices[0] = 2; basis[0] = 999f; deltas.clear()
        assertArrayEquals(positions, mesh.baseVertices(), 0f)
        assertArrayEquals(textureUv, mesh.uv(), 0f)
        assertArrayEquals(triangles, mesh.indices())
        assertEquals(1f, mesh.deltas().getValue("headX")[0], 0f)
        mesh.baseVertices()[0] = 900f; mesh.uv()[0] = 1f; mesh.indices()[0] = 2
        mesh.deltas().getValue("headX")[0] = 900f
        assertEquals(0f, mesh.baseVertices()[0], 0f)
        assertEquals(0f, mesh.uv()[0], 0f)
        assertEquals(0, mesh.indices()[0])
        assertEquals(1f, mesh.deltas().getValue("headX")[0], 0f)
    }

    @Test fun mixedVerticesSumPrecomputedBasesWithoutChangingMesh() {
        val contract = contractFixture()
        val mesh = squareMesh(contract, mapOf("headX" to FloatArray(8) { 2f }, "mouth" to FloatArray(8) { 4f }))
        val mixed = PlanarMixer.vertices(mesh, PlanarParameters(contract, mapOf("headX" to -.5f, "mouth" to .25f)))
        assertArrayEquals(mesh.baseVertices(), mixed, 0f)
        mixed[0] = 555f
        assertEquals(0f, mesh.baseVertices()[0], 0f)
    }

    @Test fun meshRejectsInvalidVertexAndUvShapes() {
        for (bad in listOf(floatArrayOf(), floatArrayOf(0f, 1f, 2f), FloatArray(4))) {
            assertThrows(IllegalArgumentException::class.java) { mesh(base = bad) }
        }
        for (bad in listOf(floatArrayOf(), FloatArray(4), FloatArray(8))) {
            assertThrows(IllegalArgumentException::class.java) { mesh(uv = bad) }
        }
    }

    @Test fun meshRejectsNonFinitePositionUvAndBasis() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { mesh(base = positions.copyOf().also { it[0] = bad }) }
            assertThrows(IllegalArgumentException::class.java) { mesh(uv = textureUv.copyOf().also { it[0] = bad }) }
            assertThrows(IllegalArgumentException::class.java) { mesh(deltas = mapOf("headX" to FloatArray(6).also { it[0] = bad })) }
        }
    }

    @Test fun meshRejectsUvOutsideTexture() {
        for (bad in listOf(-.01f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) { mesh(uv = textureUv.copyOf().also { it[0] = bad }) }
        }
    }

    @Test fun meshRejectsInvalidTriangleShapeAndIndices() {
        for (bad in listOf(intArrayOf(), intArrayOf(0, 1), intArrayOf(0, 1, 3), intArrayOf(-1, 1, 2))) {
            assertThrows(IllegalArgumentException::class.java) { mesh(indices = bad) }
        }
    }

    @Test fun meshRejectsDegenerateTriangles() {
        assertThrows(IllegalArgumentException::class.java) { mesh(indices = intArrayOf(0, 0, 1)) }
        assertThrows(IllegalArgumentException::class.java) { mesh(base = floatArrayOf(0f, 0f, 5f, 5f, 10f, 10f)) }
    }

    @Test fun meshEnforcesContractGeometryBudgets() {
        val contract = contractFixture()
        val base = FloatArray((contract.maxVertices + 1) * 2)
        assertThrows(IllegalArgumentException::class.java) { mesh(base, FloatArray(base.size)) }
        val indices = IntArray((contract.maxTriangles + 1) * 3) { it % 3 }
        assertThrows(IllegalArgumentException::class.java) { mesh(indices = indices) }
    }

    @Test fun basisKeysAndShapesAreValidatedEvenWhenUnused() {
        assertThrows(IllegalArgumentException::class.java) { mesh(deltas = mapOf("unknown" to FloatArray(6))) }
        assertThrows(IllegalArgumentException::class.java) { mesh(deltas = mapOf("mouth" to FloatArray(4))) }
    }

    @Test fun mixingRejectsInvertedOrCollapsedTriangles() {
        val contract = contractFixture()
        val mesh = squareMesh(contract, mapOf("headX" to floatArrayOf(0f, 0f, -20f, 0f, -20f, 0f, 0f, 0f)))
        for (value in listOf(.5f, 1f)) {
            assertThrows(IllegalArgumentException::class.java) { PlanarMixer.vertices(mesh, PlanarParameters(contract, mapOf("headX" to value))) }
        }
        assertTrue(PlanarMixer.vertices(mesh, PlanarParameters(contract, mapOf("headX" to .1f))).all(Float::isFinite))
    }

    @Test fun mixingRejectsOverflowInsteadOfReturningInfinity() {
        val contract = contractFixture()
        val mesh = squareMesh(contract, mapOf("headX" to FloatArray(8) { Float.MAX_VALUE }, "headY" to FloatArray(8) { Float.MAX_VALUE }))
        assertThrows(IllegalArgumentException::class.java) {
            PlanarMixer.vertices(mesh, PlanarParameters(contract, mapOf("headX" to 1f, "headY" to 1f)))
        }
    }

    @Test fun mixingRejectsIncompatibleContracts() {
        val mesh = squareMesh(); val old = mesh.contract
        val changed = PlanarContract(old.maxSide, old.maxVertices, old.maxTriangles,
            old.parameters + ("mouth" to ParameterRange(0f, .5f, 0f)))
        assertThrows(IllegalArgumentException::class.java) { PlanarMixer.vertices(mesh, PlanarParameters(changed)) }
    }

    @Test fun deltaSnapshotSurvivesMutationByMapIterator() {
        val basis = FloatArray(6) { 1f }
        val entrySet: Set<Map.Entry<String, FloatArray>> = object : AbstractSet<Map.Entry<String, FloatArray>>() {
            override val size = 2
            override fun iterator(): Iterator<Map.Entry<String, FloatArray>> = object : Iterator<Map.Entry<String, FloatArray>> {
                var offset = 0
                override fun hasNext() = offset < 2
                override fun next(): Map.Entry<String, FloatArray> {
                    if (offset++ == 0) return java.util.AbstractMap.SimpleEntry("headX", basis)
                    basis.fill(99f)
                    return java.util.AbstractMap.SimpleEntry("headY", FloatArray(6))
                }
            }
        }
        val values = object : AbstractMap<String, FloatArray>() {
            override val entries: Set<Map.Entry<String, FloatArray>> get() = entrySet
        }
        val mesh = mesh(deltas = values)
        assertEquals(1f, mesh.deltas().getValue("headX")[0], 0f)
    }
}
