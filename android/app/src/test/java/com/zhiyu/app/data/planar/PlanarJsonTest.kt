package com.zhiyu.app.data.planar

import org.junit.Assert.*
import org.junit.Test

class PlanarJsonTest {
    @Test fun duplicateKeysCannotReplaceGeometryOrHashIncludingNestedNull() {
        listOf("{\"width\":2,\"width\":1536}", "{\"x\":{\"a\":null,\"a\":1}}", "{\"a\":0,\"\\u0061\":1}").forEach {
            assertThrows(IllegalArgumentException::class.java) { PlanarJson.parse(it) }
        }
    }
    @Test fun nonfiniteMalformedAndTrailingDataAreRejected() {
        listOf("NaN", "Infinity", "1e999", "[01]", "[true,]", "{x:1}", "{} tail", "\"\\uD800\"").forEach {
            assertThrows(IllegalArgumentException::class.java) { PlanarJson.parse(it) }
        }
    }
    @Test fun geometryArraysAndUnicodeRoundTripWithoutCoercingStrings() {
        val original = mapOf("mesh" to List(6144) { it }, "name" to "中文😀\n\"\\")
        val parsed = PlanarJson.parse(PlanarJson.encode(original)) as Map<*, *>
        assertEquals("中文😀\n\"\\", parsed["name"])
        assertEquals(6144, (parsed["mesh"] as List<*>).size)
        assertEquals("1", PlanarJson.parse("\"1\""))
    }
    @Test fun nestingAndArrayBudgetsAreEnforcedBeforeRecursionOrAllocation() {
        assertThrows(IllegalArgumentException::class.java) { PlanarJson.parse("[".repeat(18) + "0" + "]".repeat(18)) }
        assertThrows(IllegalArgumentException::class.java) { PlanarJson.parse("[" + List(8193) { "0" }.joinToString(",") + "]") }
        assertThrows(IllegalArgumentException::class.java) { PlanarJson.encode(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { PlanarJson.parse("0." + "0".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { PlanarJson.parse("{" + List(129) { "\"k$it\":0" }.joinToString(",") + "}") }
    }
}
