package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProactiveRepositoryTest {
    private class Store : CredentialStore {
        var connection = SavedConnection("https://backend.invalid", "deepseek", "synthetic-key", "fixed-model",
            AuthSession("old-session", "deepseek", "fixed-model", System.currentTimeMillis() / 1000 + 10000))
        private var binding: CompanionBinding? = CompanionBinding("deepseek", "fixed-model")
        override fun read() = connection
        override fun write(connection: SavedConnection) { this.connection = connection }
        override fun clear() = error("Credentials must not be cleared")
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
    }

    @Test fun unauthorizedContactRefreshesOnceAndReusesItsRequestId() = runTest {
        val state = Store()
        val id = UUID.randomUUID().toString()
        val received = mutableListOf<Pair<String?, String>>()
        var authCalls = 0
        val transport = ProactiveTransport { url, path, _, token, body ->
            assertEquals("https://backend.invalid", url)
            if (path == "/api/auth/login") {
                authCalls++
                assertEquals("synthetic-key", body!!.getString("api_key"))
                """{"token":"new-session","provider_id":"deepseek","model":"fixed-model","expires_at":${System.currentTimeMillis() / 1000 + 10000}}"""
            } else {
                assertFalse(body!!.has("api_key"))
                received.add(token to body.getString("request_id"))
                if (token == "old-session") throw ApiFailure(401, "Expired")
                """{"emitted":false,"message":null,"next_due_at":null,"reason":"quiet"}"""
            }
        }
        val result = ProactiveRepository(state, false, transport).check("chat", id)
        assertFalse(result.emitted)
        assertEquals(1, authCalls)
        assertEquals(listOf("old-session" to id, "new-session" to id), received)
        assertEquals("synthetic-key", state.connection.apiKey)
    }

    @Test fun aSecondUnauthorizedResponseDoesNotEnterAnAuthLoop() = runTest {
        val state = Store()
        var authCalls = 0
        var checks = 0
        val transport = ProactiveTransport { _, path, _, _, _ ->
            if (path == "/api/auth/login") {
                authCalls++
                """{"token":"new-session","provider_id":"deepseek","model":"fixed-model","expires_at":${System.currentTimeMillis() / 1000 + 10000}}"""
            } else { checks++; throw ApiFailure(401, "Still unauthorized") }
        }
        try { ProactiveRepository(state, false, transport).check("chat", UUID.randomUUID().toString()); fail("Expected 401") }
        catch (failure: ApiFailure) { assertEquals(401, failure.status) }
        assertEquals(1, authCalls)
        assertEquals(2, checks)
    }
}
