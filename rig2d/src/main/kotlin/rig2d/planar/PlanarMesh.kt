package rig2d.planar

import kotlin.math.abs

/** Immutable blendshape data. Geometry is built by the asset producer, not duplicated here. */
class PlanarMesh(
    val contract: PlanarContract,
    baseVertices: FloatArray,
    uv: FloatArray,
    indices: IntArray,
    deltas: Map<String, FloatArray> = emptyMap(),
) {
    private val base: FloatArray
    private val textureUv: FloatArray
    private val triangles: IntArray
    private val bases: Map<String, FloatArray>
    private val orientations: DoubleArray
    val vertexCount: Int get() = base.size / 2

    init {
        require(baseVertices.size in 6..contract.maxVertices * 2 && baseVertices.size % 2 == 0) {
            "Vertex shape or budget is invalid"
        }
        require(uv.size == baseVertices.size) { "UV shape does not match vertices" }
        require(indices.isNotEmpty() && indices.size % 3 == 0 && indices.size / 3 <= contract.maxTriangles) {
            "Triangle shape or budget is invalid"
        }
        base = baseVertices.copyOf()
        textureUv = uv.copyOf()
        triangles = indices.copyOf()
        require(base.all { it.isFinite() && it in 0f..contract.maxSide.toFloat() }) { "Invalid source coordinate" }
        require(textureUv.all { it.isFinite() && it in 0f..1f }) { "Invalid UV coordinate" }
        require(triangles.all { it in 0 until vertexCount }) { "Vertex index outside mesh" }
        orientations = DoubleArray(triangles.size / 3) { triangle ->
            area(base, triangle).also { require(abs(it) > MIN_AREA) { "Degenerate source triangle" } }
        }
        bases = snapshotDeltas(deltas)
    }

    private fun snapshotDeltas(deltas: Map<String, FloatArray>): Map<String, FloatArray> {
        require(deltas.size <= PlanarContract.CHANNELS.size) { "Too many blendshape bases" }
        val result = LinkedHashMap<String, FloatArray>()
        for ((name, values) in deltas) {
            require(result.size < PlanarContract.CHANNELS.size && name !in result) { "Repeated or excess blendshape" }
            contract.range(name)
            require(values.size == base.size) { "Blendshape shape does not match vertices" }
            val copy = values.copyOf()
            require(copy.all(Float::isFinite)) { "Nonfinite blendshape" }
            result[name] = copy
        }
        return result
    }

    fun baseVertices(): FloatArray = base.copyOf()
    fun uv(): FloatArray = textureUv.copyOf()
    fun indices(): IntArray = triangles.copyOf()
    fun deltas(): Map<String, FloatArray> = bases.mapValues { it.value.copyOf() }

    internal fun mixed(parameters: PlanarParameters): FloatArray {
        require(contract == parameters.contract) { "Parameters belong to a different contract" }
        val sum = DoubleArray(base.size) { base[it].toDouble() }
        for ((name, delta) in bases) {
            val weight = parameters[name].toDouble()
            for (index in sum.indices) sum[index] += weight * delta[index].toDouble()
        }
        val result = FloatArray(base.size) { index ->
            sum[index].toFloat().also { require(it.isFinite()) { "Blendshape overflow" } }
        }
        for (triangle in orientations.indices) {
            val deformed = area(result, triangle)
            require(abs(deformed) > MIN_AREA && (deformed > 0) == (orientations[triangle] > 0)) {
                "Unsafe blendshape collapse or fold"
            }
        }
        return result
    }

    private fun area(vertices: FloatArray, triangle: Int): Double {
        val a = triangles[triangle * 3] * 2
        val b = triangles[triangle * 3 + 1] * 2
        val c = triangles[triangle * 3 + 2] * 2
        return (vertices[b].toDouble() - vertices[a]) * (vertices[c + 1].toDouble() - vertices[a + 1]) -
            (vertices[b + 1].toDouble() - vertices[a + 1]) * (vertices[c].toDouble() - vertices[a])
    }

    private companion object { const val MIN_AREA = 1e-6 }
}

object PlanarMixer {
    fun vertices(mesh: PlanarMesh, parameters: PlanarParameters): FloatArray = mesh.mixed(parameters)
}
