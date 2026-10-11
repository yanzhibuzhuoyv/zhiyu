package com.zhiyu.app.data

import org.junit.Assert.*
import org.junit.Test

class LocalVoiceTtsCallbackTest {
    @Test fun nativeCallbackHasTheExactBoxedIntegerSignatureSherpaLooksUp() {
        val method = LocalVoiceTtsCallback::class.java.getMethod("invoke", FloatArray::class.java)
        assertEquals(java.lang.Integer::class.java, method.returnType)
        assertFalse(method.isBridge)
        assertTrue(java.lang.reflect.Modifier.isPublic(method.modifiers))
    }

    @Test fun callbackKeepsCancellationLiveWithoutReplacingOrCopyingTheGeneratedSamples() {
        var active = true
        val callback = LocalVoiceTtsCallback { active }
        val samples = floatArrayOf(0.25f, -0.25f)
        assertEquals(1, callback(samples))
        active = false
        assertEquals(0, callback(samples))
        assertArrayEquals(floatArrayOf(0.25f, -0.25f), samples, 0f)
    }
}
