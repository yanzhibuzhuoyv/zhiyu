package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

class RemoteImageTransportTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS/0AAAAASUVORK5CYII=")

    @Test fun fixedClientDisablesRedirectsRetriesAndBoundsTimeouts() {
        val client = HttpsRemoteImageTransport.officialClient
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
        assertEquals(15_000, client.connectTimeoutMillis); assertEquals(180_000, client.callTimeoutMillis)
        assertEquals(2, client.dispatcher.maxRequests); assertEquals(2, client.dispatcher.maxRequestsPerHost)
    }

    @Test fun actualMultipartUsesImageArrayAndBearerOnlyAtFixedOrigin() = runBlocking {
        var captured: Request? = null
        val transport = HttpsRemoteImageTransport(Call.Factory { request -> captured = request; FakeCall(request, "{}".toResponseBody()) })
        assertEquals("{}", transport.exchange("synthetic-key", Image2Protocol.request("same OC tea", png)))
        val request = captured!!
        assertEquals("https://cn-api.ooapi.cc/v1/images/edits", request.url.toString())
        assertEquals("Bearer synthetic-key", request.header("Authorization"))
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        val body = buffer.readUtf8()
        assertTrue(body.contains("name=\"image[]\"; filename=\"reference.png\""))
        assertTrue(body.contains("image/png")); assertFalse(body.contains("synthetic-key")); assertFalse(body.contains("return_base64"))
    }

    @Test fun downloadNeverSendsAuthorizationAndRejectsUnsafeUrlBeforeOpeningCall() = runBlocking {
        var captured: Request? = null; var opens = 0
        val transport = HttpsRemoteImageTransport(Call.Factory { request -> captured = request; opens++; FakeCall(request, png.toResponseBody()) })
        assertArrayEquals(png, transport.fetch("https://images.example.com/result.png"))
        assertNull(captured!!.header("Authorization")); assertEquals("GET", captured!!.method)
        try { transport.fetch("https://127.0.0.1/private"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, opens)
    }

    @Test fun publicHostnameResolvingToAnyPrivateAddressIsRejected() {
        val resolver = PublicImageDns { listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")) }
        assertThrows(java.net.UnknownHostException::class.java) { resolver.lookup("images.example.com") }
    }

    @Test fun observedBalanceFailureIsSanitizedAndDoesNotRetry() = runBlocking {
        var opens = 0
        val transport = HttpsRemoteImageTransport(Call.Factory { request -> opens++; FakeCall(request,
            """{"code":"INSUFFICIENT_BALANCE","message":"secret-key"}""".toResponseBody(), 403) })
        try { transport.exchange("synthetic-key", Image2Protocol.request("portrait")); fail() }
        catch (failure: ApiFailure) { assertEquals("image_balance_insufficient", failure.errorCode); assertFalse(failure.message!!.contains("secret-key")) }
        assertEquals(1, opens)
    }

    private class FakeCall(private val req: Request, private val body: ResponseBody, private val status: Int = 200) : Call {
        private var cancelled = false
        private var executed = false
        override fun request() = req
        override fun enqueue(callback: Callback) {
            executed = true
            callback.onResponse(this, Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("synthetic").body(body).build())
        }
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = executed
        override fun addEventListener(eventListener: EventListener) = Unit
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun execute(): Response = error("not used")
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(req, body, status)
    }
}
