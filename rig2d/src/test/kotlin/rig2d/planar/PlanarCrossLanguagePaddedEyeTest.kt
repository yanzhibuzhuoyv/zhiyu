package rig2d.planar

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** Real Python-producer data and an independent scalar oracle, consumed by the shipping mixer. */
class PlanarCrossLanguagePaddedEyeTest {
    private val directory = Path.of("artifacts/studio-cross-language-2026-10-13-r3")
    private val data = Properties().also { properties ->
        Files.newBufferedReader(directory.resolve("fixture.properties")).use(properties::load)
    }
    private val contract = contractFixture()
    private val meshCount = data.getProperty("mesh.count").toInt()
    private val caseCount = data.getProperty("case.count").toInt()

    private fun values(key: String) = data.getProperty(key).split(',').map(String::toFloat).toFloatArray()
    private fun integers(key: String) = data.getProperty(key).split(',').map(String::toInt).toIntArray()
    private fun order(key: String) = data.getProperty(key).split(',').filter(String::isNotEmpty)

    private fun mesh(index: Int): PlanarMesh {
        val prefix = "mesh.$index."
        val deltas = order(prefix + "deltaOrder").associateWithTo(LinkedHashMap()) { values(prefix + "delta." + it) }
        return PlanarMesh(contract, values(prefix + "vertices"), values(prefix + "uv"), integers(prefix + "indices"), deltas)
    }

    private fun parameters(index: Int): PlanarParameters {
        val prefix = "case.$index."
        return PlanarParameters(contract, order(prefix + "parameterOrder").associateWith {
            data.getProperty(prefix + "parameter." + it).toFloat()
        })
    }

    private fun bytes(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).also { buffer -> values.forEach(buffer::putFloat) }.array()

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun frozenFixtureBindsCurrentContractSourceAndActualProducerTriangle() {
        assertEquals("rig2d-planar-numeric-fixture-1", data.getProperty("format"))
        val snapshot = directory.resolve("contract.json")
        assertEquals(data.getProperty("contract.sha"), sha(Files.readAllBytes(snapshot)))
        val frozenParameters = contractFixture(snapshot).parameters
        assertEquals("Numeric ABI names must be unchanged", frozenParameters.keys, contract.parameters.keys)
        for ((name, range) in frozenParameters) {
            assertEquals("Numeric ABI range/default must be unchanged: $name", range, contract.parameters.getValue(name))
        }
        assertEquals(data.getProperty("producer.assetSha"), sha(Files.readAllBytes(directory.resolve("producer-asset.json"))))
        assertEquals(data.getProperty("producer.sourceSha"), sha(Files.readAllBytes(directory.resolve("source.png"))))
        assertEquals(21, meshCount)
        assertEquals(260, caseCount)
        assertEquals(12, (0 until meshCount).count { data.getProperty("mesh.$it.id").startsWith("wing_") })
        val original = (0 until meshCount).first { data.getProperty("mesh.$it.id") == data.getProperty("triangle.sourceLayer") }
        val firstTriangle = integers("triangle.sourceIndices").toList()
        val offset = data.getProperty("triangle.sourceOffset").toInt()
        assertEquals(firstTriangle, integers("mesh.$original.indices").drop(offset).take(3))
        val vertices = values("mesh.$original.vertices")
        val selected = firstTriangle.flatMap { listOf(vertices[it * 2], vertices[it * 2 + 1]) }.toFloatArray()
        assertEquals("actual_triangle_eye_left", data.getProperty("mesh.${meshCount - 1}.id"))
        assertArrayEquals(selected, mesh(meshCount - 1).baseVertices(), 0f)
        assertArrayEquals(intArrayOf(0, 1, 2), mesh(meshCount - 1).indices())
        assertTrue(mesh(meshCount - 1).deltas().getValue("blinkLeft").any { it != 0f })
    }

    @Test fun everyFrozenCoordinateMatchesIndependentPythonOracleAsExactLittleEndianFloatBits() {
        val meshes = (0 until meshCount).map(::mesh)
        var coordinates = 0
        for (sample in 0 until caseCount) {
            val parameters = parameters(sample)
            for ((index, mesh) in meshes.withIndex()) {
                val actual = PlanarMixer.vertices(mesh, parameters)
                val prefix = "case.$sample.mesh.$index."
                val label = "${data.getProperty("case.$sample.name")} / ${data.getProperty("mesh.$index.id")}" 
                assertEquals(label, data.getProperty(prefix + "sha"), sha(bytes(actual)))
                coordinates += actual.size
            }
        }
        assertEquals(data.getProperty("coordinateChecks").toInt(), coordinates)
    }

    @Test fun selectedPosesExposePerCoordinateDiagnosticsAndNeutralNeverMutatesRealMesh() {
        var compared = 0
        for (sample in 0 until caseCount) for (index in 0 until meshCount) {
            val expected = data.getProperty("case.$sample.mesh.$index.bits") ?: continue
            val mesh = mesh(index)
            val baseline = mesh.baseVertices()
            val actual = PlanarMixer.vertices(mesh, parameters(sample))
            val bits = expected.split(',').map { it.toLong(16).toInt() }
            assertEquals(actual.size, bits.size)
            actual.forEachIndexed { coordinate, value ->
                assertEquals("case $sample mesh $index coordinate $coordinate", bits[coordinate], value.toRawBits())
                compared++
            }
            if (sample == 0) assertArrayEquals(bytes(baseline), bytes(actual))
            actual.fill(999f)
            assertArrayEquals(bytes(baseline), bytes(mesh.baseVertices()))
        }
        assertEquals(data.getProperty("rawBitChecks").toInt(), compared)
    }

    @Test fun producerGeometryWasCanonicalFloat32BeforeEitherConsumerLoadedIt() {
        for (index in 0 until meshCount) {
            val prefix = "mesh.$index."
            val names = listOf(prefix + "vertices", prefix + "uv") + order(prefix + "deltaOrder").map { prefix + "delta." + it }
            for (key in names) for (text in data.getProperty(key).split(',')) {
                val original = text.toDouble()
                assertEquals("$key = $text", original.toRawBits(), original.toFloat().toDouble().toRawBits())
            }
        }
    }
}
