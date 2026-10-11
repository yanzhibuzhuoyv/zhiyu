package com.zhiyu.app.data

import com.zhiyu.app.domain.AgnesImageProtocol
import com.zhiyu.app.domain.ApiFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Offline Call.Factory only; no DNS, real credentials or paid requests. */
class AgnesOfficialImageTransportTest {
    @Test fun officialClientRejectsRedirectsRetriesAndHasBoundedConcurrencyAndTimeouts() {
        val client = AgnesOfficialImageTransport.officialClient
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(360_000, client.callTimeoutMillis)
        assertEquals(2, client.dispatcher.maxRequests); assertEquals(2, client.dispatcher.maxRequestsPerHost)
    }

    @Test fun officialPostUsesBearerOnlyInHeaderAndClosesResponse() = runBlocking {
        val fake = FakeCall()
        val payload = AgnesImageProtocol.payload("无人背景")
        assertEquals("{}", transport(fake).exchange("synthetic-key", payload))
        assertEquals(AgnesImageProtocol.ENDPOINT, fake.request.url.toString())
        assertEquals("POST", fake.request.method)
        assertEquals("Bearer synthetic-key", fake.request.header("Authorization"))
        val buffer = Buffer()
        fake.request.body!!.writeTo(buffer)
        assertEquals(payload, buffer.readUtf8())
        assertFalse(payload.contains("synthetic-key"))
        assertTrue(fake.body.closed.get() > 0)
    }

    @Test fun redirectsAndErrorsNeverReadProviderBody() = runBlocking {
        mapOf(302 to "image_redirect_rejected", 401 to "image_auth_failed",
            402 to "image_balance_insufficient", 429 to "image_rate_limited",
            422 to "image_request_invalid", 503 to "image_request_unknown").forEach { (status, code) ->
            val fake = FakeCall(status = status)
            try { transport(fake).exchange("synthetic-key", "{}"); fail() }
            catch (error: ApiFailure) {
                assertEquals(code, error.errorCode)
                assertNull(error.cause)
            }
            assertEquals(0, fake.body.reads.get()); assertTrue(fake.body.closed.get() > 0)
        }
    }

    @Test fun oversizedAndMalformedResponsesFailClosedAndCloseBody() = runBlocking {
        val oversized = FakeCall(body = FakeBody(advertised = AgnesImageProtocol.MAX_RESPONSE_BYTES.toLong() + 1))
        val invalid = FakeCall(body = FakeBody(bytes = byteArrayOf(0xc3.toByte(), 0x28)))
        listOf(oversized, invalid).forEach { fake ->
            try { transport(fake).exchange("synthetic-key", "{}"); fail() }
            catch (error: ApiFailure) { assertEquals("image_response_invalid", error.errorCode) }
            assertTrue(fake.body.closed.get() > 0)
        }
        assertEquals(0, oversized.body.reads.get())
    }

    @Test fun cancellationBeforeEnqueueCannotStartRequest() = runBlocking {
        val fake = FakeCall()
        val job = async(start = CoroutineStart.LAZY) {
            AgnesOfficialImageTransport(Call.Factory { request ->
                fake.request = request
                coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                fake
            }).exchange("synthetic-key", "{}")
        }
        job.start(); job.join()
        assertTrue(job.isCancelled); assertTrue(fake.isCanceled())
        assertEquals(0, fake.starts.get())
    }

    @Test fun cancellationWhileQueuedDoesNotStartRequest() = runBlocking {
        val fake = FakeCall(queued = true)
        val job = async { transport(fake).exchange("synthetic-key", "{}") }
        fake.enqueued.await()
        job.cancelAndJoin()
        fake.deliver()
        assertTrue(fake.isCanceled()); assertEquals(0, fake.starts.get())
    }

    @Test fun cancellationDuringReadCancelsCallAndClosesBody() = runBlocking {
        withTimeout(5_000) {
            val body = FakeBody(blocked = true)
            val fake = FakeCall(body = body, threaded = true)
            val job = async { transport(fake).exchange("synthetic-key", "{}") }
            body.started.await()
            job.cancelAndJoin()
            fake.worker!!.join(3000)
            assertTrue(fake.isCanceled()); assertFalse(fake.worker!!.isAlive)
            assertTrue(body.closed.get() > 0)
        }
    }

    @Test fun invalidCredentialNeverCreatesCall() = runBlocking {
        var opens = 0
        try { AgnesOfficialImageTransport(Call.Factory { opens++; FakeCall() })
            .exchange("key\nInjected: value", "{}"); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, opens)
    }

    @Test fun onlyFourRequestsCanRemainPendingAndCancellationReleasesCapacity() = runBlocking {
        withTimeout(5_000) {
            val calls = List(4) { FakeCall(queued = true) }
            val jobs = calls.map { fake -> async { transport(fake).exchange("synthetic-key", "{}") } }
            try {
                calls.forEach { it.enqueued.await() }
                try { transport(FakeCall()).exchange("synthetic-key", "{}"); fail() }
                catch (error: ApiFailure) { assertEquals("image_service_busy", error.errorCode) }
            } finally { jobs.forEach { it.cancelAndJoin() } }
            assertEquals("{}", transport(FakeCall()).exchange("synthetic-key", "{}"))
        }
    }

    private fun transport(fake: FakeCall) = AgnesOfficialImageTransport(Call.Factory { request ->
        fake.request = request; fake
    })

    private class FakeCall(val status: Int = 200, val body: FakeBody = FakeBody(),
        private val queued: Boolean = false, private val threaded: Boolean = false) : Call {
        lateinit var request: Request
        private var callback: Callback? = null
        private var cancelled = false
        private var delivered = false
        val starts = AtomicInteger()
        val enqueued = CompletableDeferred<Unit>()
        var worker: Thread? = null
        override fun request() = request
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback; enqueued.complete(Unit)
            if (cancelled) responseCallback.onFailure(this, IOException("cancelled"))
            else if (!queued) deliver()
        }
        fun deliver() {
            if (delivered || cancelled) return
            delivered = true; starts.incrementAndGet()
            val action = { callback!!.onResponse(this, Response.Builder().request(request)
                .protocol(Protocol.HTTP_1_1).code(status).message("synthetic").body(body).build()) }
            if (threaded) worker = Thread { action() }.apply { start() } else action()
        }
        override fun cancel() {
            cancelled = true; body.release.countDown()
            if (!delivered) callback?.onFailure(this, IOException("cancelled"))
        }
        override fun isCanceled() = cancelled
        override fun isExecuted() = callback != null
        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun execute(): Response = error("not used")
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(status)
    }

    private class FakeBody(private val bytes: ByteArray = "{}".toByteArray(),
        private val advertised: Long = -1, private val blocked: Boolean = false) : ResponseBody() {
        val reads = AtomicInteger(); val closed = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        private val data = Buffer().write(bytes)
        private val stream: BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reads.incrementAndGet(); started.complete(Unit)
                if (blocked) { check(release.await(3, TimeUnit.SECONDS)); throw IOException("cancelled") }
                return data.read(sink, byteCount)
            }
            override fun close() { closed.incrementAndGet() }
            override fun timeout() = Timeout()
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = advertised
        override fun source() = stream
    }
}
