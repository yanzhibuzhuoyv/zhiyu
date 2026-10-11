package com.zhiyu.app.data

import com.zhiyu.app.domain.AgnesImageProtocol
import com.zhiyu.app.domain.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class AgnesImageClientTest {
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1)
    private val response get() = "{\"data\":[{\"b64_json\":\"${Base64.getEncoder().encodeToString(png)}\"}]}"

    @Test fun suppliedTransportReceivesSyntheticKeyAndBase64ContractExactlyOnce() = runTest {
        var calls = 0
        var validations = 0
        val client = AgnesImageClient(AgnesImageTransport { key, payload ->
            calls++
            assertEquals("synthetic-key", key)
            assertEquals(AgnesImageProtocol.payload("背景", png), payload)
            response
        }, AgnesImageBoundsValidator { _, _ -> validations++ }, UnconfinedTestDispatcher(testScheduler))
        assertArrayEquals(png, client.generate("synthetic-key", "背景", png).bytes)
        assertEquals(1, calls)
        assertEquals(2, validations)
    }

    @Test fun invalidKeyAndReferenceNeverStartNetwork() = runTest {
        var calls = 0
        val client = AgnesImageClient(AgnesImageTransport { _, _ -> calls++; response },
            AgnesImageBoundsValidator { _, _ -> throw IllegalArgumentException("invalid input") },
            UnconfinedTestDispatcher(testScheduler))
        try { client.generate("bad key", "背景") ; fail() } catch (_: IllegalArgumentException) { }
        try { client.generate("synthetic-key", "背景", png); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }

    @Test fun oversizedReferenceIsRejectedBeforeValidationOrNetwork() = runTest {
        var calls = 0
        var validations = 0
        val client = AgnesImageClient(AgnesImageTransport { _, _ -> calls++; response },
            AgnesImageBoundsValidator { _, _ -> validations++ }, UnconfinedTestDispatcher(testScheduler))
        try { client.generate("synthetic-key", "背景", ByteArray(AgnesImageProtocol.MAX_IMAGE_BYTES + 1)); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, validations)
        assertEquals(0, calls)
    }

    @Test fun cancellationIsPropagatedWithoutRetryOrRewritingItAsFailure() = runTest {
        var calls = 0
        val client = AgnesImageClient(AgnesImageTransport { _, _ -> calls++; throw CancellationException("cancel") },
            AgnesImageBoundsValidator { _, _ -> }, UnconfinedTestDispatcher(testScheduler))
        try { client.generate("synthetic-key", "背景"); fail() } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun outputDecodeFailureDoesNotLeakProviderDiagnostic() = runTest {
        val client = AgnesImageClient(AgnesImageTransport { _, _ -> response },
            AgnesImageBoundsValidator { _, _ -> throw IllegalArgumentException("provider-body-or-secret") },
            UnconfinedTestDispatcher(testScheduler))
        try {
            client.generate("synthetic-key", "背景"); fail()
        } catch (error: ApiFailure) {
            assertEquals("image_response_invalid", error.errorCode)
            assertFalse(error.message!!.contains("provider-body-or-secret"))
        }
    }

    @Test fun sixMinuteDeadlineReportsUnknownResultAndNeverRetries() = runTest {
        var calls = 0
        var cancelled = false
        val client = AgnesImageClient(AgnesImageTransport { _, _ ->
            calls++
            try { delay(360_001L); response } finally { cancelled = true }
        }, AgnesImageBoundsValidator { _, _ -> }, UnconfinedTestDispatcher(testScheduler))
        try {
            client.generate("synthetic-key", "背景"); fail()
        } catch (error: ApiFailure) {
            assertEquals(504, error.status)
            assertEquals("image_request_unknown", error.errorCode)
        }
        assertEquals(1, calls)
        assertTrue(cancelled)
        assertEquals(360_000L, testScheduler.currentTime)
    }

    @Test fun callerDeadlineIsNotMistakenForProviderDeadline() = runTest {
        val client = AgnesImageClient(AgnesImageTransport { _, _ -> delay(10_000L); response },
            AgnesImageBoundsValidator { _, _ -> }, UnconfinedTestDispatcher(testScheduler))
        try {
            withTimeout(1_000L) { client.generate("synthetic-key", "背景") }; fail()
        } catch (_: CancellationException) { }
        assertEquals(1_000L, testScheduler.currentTime)
    }
}
