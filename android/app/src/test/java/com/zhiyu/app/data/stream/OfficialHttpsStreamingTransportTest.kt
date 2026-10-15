package com.zhiyu.app.data.stream

import com.zhiyu.app.data.DirectProviderCatalog
import com.zhiyu.app.domain.ApiFailure
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Production reader with an injected Call.Factory; no internet, TLS handshake or real Key is used. */
class OfficialHttpsStreamingTransportTest {
    private val endpoint = DirectProviderCatalog.endpoint("deepseek")
    private class Body(input: InputStream, private val type: String = "text/event-stream") : ResponseBody() {
        val closed = CountDownLatch(1); val reads = AtomicInteger()
        private val stream = object : ForwardingSource(input.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long { reads.incrementAndGet(); return super.read(sink, byteCount) }
            override fun close() { closed.countDown(); super.close() }
        }.buffer()
        override fun contentType(): MediaType = type.toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = stream
    }
    private class FakeCall(private val original: Request, val body: Body, private val status: Int = 200,
        private val automatic: Boolean = true, private val onCancel: () -> Unit = {}) : Call {
        val enqueued = CountDownLatch(1); val cancelled = AtomicBoolean()
        @Volatile var callback: Callback? = null
        override fun request() = original
        override fun execute(): Response = throw UnsupportedOperationException("Async test call")
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback; enqueued.countDown()
            if (automatic) Thread({ deliver() }, "zhiyu-synthetic-call").apply { isDaemon = true; start() }
        }
        fun deliver() { callback!!.onResponse(this, Response.Builder().request(original).protocol(Protocol.HTTP_1_1)
            .code(status).message("synthetic").body(body).build()) }
        override fun cancel() { cancelled.set(true); onCancel() }
        override fun isExecuted() = callback != null
        override fun isCanceled() = cancelled.get()
        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall(original, body, status, automatic, onCancel)
    }

    @Test fun cancellingABlockedSocketPermanentlyCancelsTheOwnedCallAndClosesItsBody() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int { entered.countDown(); release.await(3, TimeUnit.SECONDS); throw IOException("synthetic cancelled read") }
        }
        val body = Body(input); lateinit var call: FakeCall
        val factory = Call.Factory { request -> FakeCall(request, body, onCancel = { release.countDown() }).also { call = it } }
        val job = launch(Dispatchers.Default) { OfficialHttpsTransport(factory).exchangeStreaming(endpoint, "synthetic-key", "{}") {} }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled()); assertTrue(body.closed.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); job.cancelAndJoin() }
    }

    @Test fun slowSpeechConsumerCannotAccumulateAnUnboundedNetworkQueue() = runBlocking {
        val reads = AtomicInteger(); val second = CountDownLatch(1); val third = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read() = 1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = reads.incrementAndGet()
                if (count == 2) second.countDown()
                if (count == 3) third.countDown()
                buffer[offset] = 1; return 1
            }
        }
        val body = Body(input); val hold = CompletableDeferred<Unit>()
        val factory = Call.Factory { request -> FakeCall(request, body) }
        val job = launch(Dispatchers.Default) { OfficialHttpsTransport(factory).exchangeStreaming(endpoint, "synthetic-key", "{}") { hold.await() } }
        try {
            assertTrue(second.await(3, TimeUnit.SECONDS))
            assertFalse("At most one consumer chunk and one blocking read", third.await(150, TimeUnit.MILLISECONDS))
            assertEquals(2, reads.get())
        } finally { withTimeout(2000) { job.cancelAndJoin() } }
        assertTrue(body.closed.await(2, TimeUnit.SECONDS))
    }

    @Test fun wrongMimeAndHttpFailuresAreSanitizedAndNeverEmitBodyText() = runBlocking {
        for ((status, mime) in listOf(200 to "application/json", 401 to "text/event-stream", 302 to "text/event-stream")) {
            val body = Body(ByteArrayInputStream("private-provider-error".toByteArray()), mime)
            val factory = Call.Factory { request -> FakeCall(request, body, status) }
            val chunks = mutableListOf<ByteArray>()
            try {
                OfficialHttpsTransport(factory).exchangeStreaming(endpoint, "synthetic-key", "{}") { chunks += it }
                fail("Untrusted response must fail")
            } catch (failure: ApiFailure) {
                assertFalse(failure.message.orEmpty().contains("private-provider-error"))
                assertTrue(chunks.isEmpty()); assertEquals(0L, body.closed.count)
            }
        }
    }

    @Test fun cancellationBeforeALateCreatedCallCannotEnqueueTheRequest() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        lateinit var call: FakeCall
        val body = Body(ByteArrayInputStream(byteArrayOf()))
        val factory = Call.Factory { request ->
            call = FakeCall(request, body, automatic = false); entered.countDown()
            release.await(3, TimeUnit.SECONDS); call
        }
        val job = launch(Dispatchers.Default) { OfficialHttpsTransport(factory).exchangeStreaming(endpoint, "synthetic-key", "{}") {} }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            job.cancel(); release.countDown()
            withTimeout(2000) { job.join() }
            assertTrue(call.isCanceled()); assertEquals(1L, call.enqueued.count); assertEquals(0, body.reads.get())
        } finally { release.countDown(); job.cancelAndJoin() }
    }

    @Test fun responseDeliveredAfterCancellationIsClosedWithoutOpeningItsBodyStream() = runBlocking {
        val body = Body(ByteArrayInputStream("late private response".toByteArray()))
        lateinit var call: FakeCall
        val factory = Call.Factory { request -> FakeCall(request, body, automatic = false).also { call = it } }
        val created = CountDownLatch(1)
        val wrapped = Call.Factory { request -> factory.newCall(request).also { created.countDown() } }
        val job = launch(Dispatchers.Default) { OfficialHttpsTransport(wrapped).exchangeStreaming(endpoint, "synthetic-key", "{}") {} }
        try {
            assertTrue(created.await(3, TimeUnit.SECONDS)); assertTrue(call.enqueued.await(3, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            call.deliver()
            assertTrue(call.isCanceled()); assertEquals(0L, body.closed.count); assertEquals(0, body.reads.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun requestCannotBeReplayedAndLongAnswerHasNoOverallDeadline() = runBlocking {
        val body = Body(ByteArrayInputStream(byteArrayOf()))
        lateinit var request: Request
        val factory = Call.Factory { value -> request = value; FakeCall(value, body) }
        OfficialHttpsTransport(factory).exchangeStreaming(endpoint, "synthetic-key", "{}") {}
        assertTrue(request.body!!.isOneShot())
        assertEquals("text/event-stream", request.header("Accept"))
        val client = OfficialHttpsTransport.officialClient
        assertFalse(client.retryOnConnectionFailure); assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        assertEquals(15000, client.connectTimeoutMillis); assertEquals(15000, client.writeTimeoutMillis)
        assertEquals(0, client.readTimeoutMillis); assertEquals(0, client.callTimeoutMillis)
    }
}
