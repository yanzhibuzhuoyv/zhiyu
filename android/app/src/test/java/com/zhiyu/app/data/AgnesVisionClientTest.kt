package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgnesVisionClientTest {
    private val bytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1)
    private val photo = ChatPhotoPayload("a".repeat(64), ChatPhotoRules.sha(bytes), "image/png", bytes)
    private val response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"一只猫\"}}]}"

    @Test fun oneSyntheticKeyRequestDoesNotChangeChatBinding() = runTest {
        var calls = 0
        val client = AgnesVisionClient({ "synthetic-key" }, AgnesVisionTransport { key, json ->
            calls++
            assertEquals("synthetic-key", key)
            assertEquals(AgnesVisionProtocol.payload(photo), json)
            response
        }, UnconfinedTestDispatcher(testScheduler))
        assertEquals("一只猫", client.describe(photo))
        assertEquals(1, calls)
    }
    @Test fun missingOrInvalidSeparateImageKeyNeverStartsRequest() = runTest {
        var calls = 0
        val transport = AgnesVisionTransport { _, _ -> calls++; response }
        try { AgnesVisionClient({ null }, transport, UnconfinedTestDispatcher(testScheduler)).describe(photo); fail() }
        catch (failure: ApiFailure) { assertEquals("photo_vision_unconfigured", failure.errorCode) }
        try { AgnesVisionClient({ "bad key" }, transport, UnconfinedTestDispatcher(testScheduler)).describe(photo); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }
    @Test fun callerCancellationDoesNotBecomeUnconfiguredOrRetry() = runTest {
        var calls = 0
        val client = AgnesVisionClient({ "synthetic-key" }, AgnesVisionTransport { _, _ -> calls++; throw CancellationException() },
            UnconfinedTestDispatcher(testScheduler))
        try { client.describe(photo); fail() } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }
    @Test fun boundedWaitCancelsTransportInsteadOfReturningIncompleteObservation() = runTest {
        var finished = false
        val client = AgnesVisionClient({ "synthetic-key" }, AgnesVisionTransport { _, _ ->
            try { delay(180_001L); response } finally { finished = true }
        }, UnconfinedTestDispatcher(testScheduler))
        try { client.describe(photo); fail() }
        catch (failure: ApiFailure) { assertEquals("photo_vision_timeout", failure.errorCode) }
        assertTrue(finished)
    }
}
