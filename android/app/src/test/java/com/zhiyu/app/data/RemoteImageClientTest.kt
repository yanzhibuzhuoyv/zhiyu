package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RemoteImageClientTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS/0AAAAASUVORK5CYII=")
    private val inline = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""

    @Test fun validatedReferenceIsSentOnceAndInlineDoesNotDownload() = runTest {
        var requests = 0; var validations = 0
        val client = RemoteImageClient(RemoteImageTransport { key, request ->
            requests++; assertEquals("synthetic-key", key); assertArrayEquals(png, request.reference); inline
        }, RemoteImageDownload { throw AssertionError("inline must never download") },
            RemoteImageBoundsValidator { bytes, mime -> validations++; assertArrayEquals(png, bytes); assertEquals("image/png", mime) },
            StandardTestDispatcher(testScheduler))
        assertArrayEquals(png, client.generate("synthetic-key", "same OC tea", png).bytes)
        assertEquals(1, requests); assertEquals(2, validations)
    }

    @Test fun safeUrlDownloadsThenValidatesOnceWithoutResubmittingGeneration() = runTest {
        var requests = 0; var downloads = 0; var validations = 0
        val client = RemoteImageClient(RemoteImageTransport { _, _ -> requests++; """{"data":[{"url":"https://images.example.com/result.png"}]}""" },
            RemoteImageDownload { url -> downloads++; assertEquals("https://images.example.com/result.png", url); png },
            RemoteImageBoundsValidator { _, _ -> validations++ }, StandardTestDispatcher(testScheduler))
        assertArrayEquals(png, client.generate("synthetic-key", "new portrait").bytes)
        assertEquals(1, requests); assertEquals(1, downloads); assertEquals(1, validations)
    }

    @Test fun invalidInputAndRasterNeverLeaveTheDevice() = runTest {
        var requests = 0
        val client = RemoteImageClient(RemoteImageTransport { _, _ -> requests++; inline },
            bounds = RemoteImageBoundsValidator { _, _ -> throw IllegalArgumentException("bad image") },
            processingDispatcher = StandardTestDispatcher(testScheduler))
        try { client.generate("invalid key", "portrait", png); fail() } catch (_: IllegalArgumentException) { }
        try { client.generate("synthetic-key", "portrait", png); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, requests)
    }

    @Test fun generatedBadRasterAndCancellationPreserveFailuresWithoutRetry() = runTest {
        var requests = 0
        val invalid = RemoteImageClient(RemoteImageTransport { _, _ -> requests++; inline },
            bounds = RemoteImageBoundsValidator { _, _ -> throw IllegalArgumentException("provider secret") },
            processingDispatcher = StandardTestDispatcher(testScheduler))
        try { invalid.generate("synthetic-key", "portrait"); fail() }
        catch (failure: ApiFailure) { assertEquals("image_response_invalid", failure.errorCode); assertFalse(failure.message!!.contains("provider secret")) }
        assertEquals(1, requests)
        val cancelled = RemoteImageClient(RemoteImageTransport { _, _ -> requests++; throw CancellationException("cancel") },
            bounds = RemoteImageBoundsValidator { _, _ -> }, processingDispatcher = StandardTestDispatcher(testScheduler))
        try { cancelled.generate("synthetic-key", "portrait"); fail() } catch (_: CancellationException) { }
        assertEquals(2, requests)
    }

    @Test fun timeoutIsUnknownAndNeverMakesAnAutomaticPaidRetry() = runTest {
        var requests = 0
        val client = RemoteImageClient(RemoteImageTransport { _, _ -> requests++; delay(181_000); inline },
            bounds = RemoteImageBoundsValidator { _, _ -> }, processingDispatcher = StandardTestDispatcher(testScheduler))
        try { client.generate("synthetic-key", "portrait"); fail() }
        catch (failure: ApiFailure) { assertEquals("image_request_unknown", failure.errorCode) }
        assertEquals(1, requests)
    }

    @Test fun providerCannotReflectTheApiKeyIntoAnExternalDownloadUrl() = runTest {
        var downloads = 0
        val client = RemoteImageClient(RemoteImageTransport { _, _ ->
            """{"data":[{"url":"https://images.example.com/result.png?credential=synthetic%2Dkey"}]}"""
        }, RemoteImageDownload { downloads++; png }, bounds = RemoteImageBoundsValidator { _, _ -> },
            processingDispatcher = StandardTestDispatcher(testScheduler))
        try { client.generate("synthetic-key", "portrait"); fail() }
        catch (failure: ApiFailure) { assertEquals("image_response_invalid", failure.errorCode) }
        assertEquals(0, downloads)
    }
}
