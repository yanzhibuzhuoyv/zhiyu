package com.zhiyu.app.data.stream

import com.zhiyu.app.data.DirectChatMessage
import com.zhiyu.app.data.DirectProviderClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DirectProviderStreamingFallbackTest {
    private class Legacy : DirectProviderClient() {
        var calls = 0
        override suspend fun complete(providerId: String, key: String, model: String,
            messages: List<DirectChatMessage>, maxTokens: Int): String { calls++; return "完整正文。" }
    }
    @Test fun unsupportedProvidersKeepTheExistingSingleRequestContract() = runTest {
        val client = Legacy(); val heard = mutableListOf<String>()
        val result = client.completeStreaming("minimax", "synthetic-key", "MiniMax-M2.7",
            listOf(DirectChatMessage("user", "你好")), onDelta = { heard += it })
        assertEquals("完整正文。", result); assertEquals(listOf(result), heard); assertEquals(1, client.calls)
    }
    @Test fun cancellationFromTheConsumerDoesNotCreateASecondPaidRequest() = runTest {
        val client = Legacy(); val cancellation = CancellationException("synthetic stop")
        try {
            client.completeStreaming("minimax", "synthetic-key", "MiniMax-M2.7",
                listOf(DirectChatMessage("user", "你好")), onDelta = { throw cancellation })
            fail("Must cancel")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals(1, client.calls)
    }
}
