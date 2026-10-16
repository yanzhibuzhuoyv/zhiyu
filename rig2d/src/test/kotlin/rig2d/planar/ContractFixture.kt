package rig2d.planar

import java.nio.file.Files
import java.nio.file.Path

/** Test-only projection of this small, trusted JSON: production callers provide parsed contract data. */
internal fun contractFixture(path: Path = Path.of("studio/contract.json")): PlanarContract {
    val source = Files.readString(path)
    fun integer(key: String): Int = Regex("\\\"$key\\\"\\s*:\\s*(\\d+)").find(source)!!.groupValues[1].toInt()
    val parameterBlock = source.substringAfter("\"parameters\": {").substringBefore("}")
    val parameters = Regex("\\\"([^\\\"]+)\\\"\\s*:\\s*\\[([^]]+)\\]").findAll(parameterBlock).associate { match ->
        val values = match.groupValues[2].split(',').map { it.trim().toFloat() }
        check(values.size == 3)
        match.groupValues[1] to ParameterRange(values[0], values[1], values[2])
    }
    check(parameters.size == 8)
    return PlanarContract(integer("maxSide"), integer("maxVertices"), integer("maxTriangles"), parameters)
}

internal fun squareMesh(
    contract: PlanarContract = contractFixture(),
    deltas: Map<String, FloatArray> = emptyMap(),
): PlanarMesh = PlanarMesh(contract, floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f),
    floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3), deltas)
