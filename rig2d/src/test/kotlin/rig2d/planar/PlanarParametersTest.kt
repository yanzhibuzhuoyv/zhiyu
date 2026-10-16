package rig2d.planar

import org.junit.Assert.*
import org.junit.Test

class PlanarParametersTest {
    @Test fun defaultsAndRangesComeFromMachineContract() {
        val contract = contractFixture(); val parameters = PlanarParameters(contract)
        assertEquals(contract.parameters.keys, parameters.toMap().keys)
        contract.parameters.forEach { (name, range) -> assertEquals(range.default, parameters[name], 0f) }
    }

    @Test fun unknownAndNonFiniteParametersAreRejected() {
        val contract = contractFixture()
        assertThrows(IllegalArgumentException::class.java) { PlanarParameters(contract, mapOf("other" to 0f)) }
        assertThrows(IllegalArgumentException::class.java) { PlanarParameters(contract)["other"] }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { PlanarParameters(contract, mapOf("headX" to value)) }
        }
    }

    @Test fun boundariesAcceptedButOutsideRangeRejected() {
        val contract = contractFixture()
        contract.parameters.forEach { (name, range) ->
            assertEquals(range.minimum, PlanarParameters(contract, mapOf(name to range.minimum))[name], 0f)
            assertEquals(range.maximum, PlanarParameters(contract, mapOf(name to range.maximum))[name], 0f)
            assertThrows(IllegalArgumentException::class.java) { PlanarParameters(contract, mapOf(name to range.minimum - .01f)) }
            assertThrows(IllegalArgumentException::class.java) { PlanarParameters(contract, mapOf(name to range.maximum + .01f)) }
        }
    }

    @Test fun parameterValuesAreImmutableSnapshots() {
        val contract = contractFixture(); val input = mutableMapOf("mouth" to .7f)
        val parameters = PlanarParameters(contract, input)
        input["mouth"] = .1f
        (parameters.toMap() as MutableMap<String, Float>)["mouth"] = .2f
        assertEquals(.7f, parameters["mouth"], 0f)
    }

    @Test fun invalidContractRangesAndDefaultsAreRejected() {
        val contract = contractFixture()
        for (bad in listOf(ParameterRange(Float.NaN, 1f, 0f), ParameterRange(1f, 0f, 0f),
            ParameterRange(0f, 1f, 2f), ParameterRange(0f, Float.POSITIVE_INFINITY, 0f))) {
            assertThrows(IllegalArgumentException::class.java) {
                PlanarContract(contract.maxSide, contract.maxVertices, contract.maxTriangles,
                    contract.parameters + ("mouth" to bad))
            }
        }
    }

    @Test fun invalidContractCapacityAndNamesAreRejected() {
        val contract = contractFixture()
        assertThrows(IllegalArgumentException::class.java) { PlanarContract(0, contract.maxVertices, contract.maxTriangles, contract.parameters) }
        assertThrows(IllegalArgumentException::class.java) { PlanarContract(contract.maxSide, 2, contract.maxTriangles, contract.parameters) }
        assertThrows(IllegalArgumentException::class.java) { PlanarContract(contract.maxSide, contract.maxVertices, 0, contract.parameters) }
        assertThrows(IllegalArgumentException::class.java) { PlanarContract(contract.maxSide, contract.maxVertices, contract.maxTriangles, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { PlanarContract(contract.maxSide, contract.maxVertices, contract.maxTriangles,
            contract.parameters + ("../evil" to ParameterRange(0f, 1f, 0f))) }
    }

    @Test fun contractDoesNotRetainCallerMap() {
        val contract = contractFixture(); val input = contract.parameters.toMutableMap()
        val copy = PlanarContract(contract.maxSide, contract.maxVertices, contract.maxTriangles, input)
        input.clear()
        assertEquals(contract.parameters, copy.parameters)
        assertEquals(contract, copy)
        assertEquals(contract.hashCode(), copy.hashCode())
    }
}
