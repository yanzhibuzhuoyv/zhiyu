package com.zhiyu.app.data

import com.zhiyu.app.domain.ApiFailure
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Captures the real request and parses synthetic responses; no network or real API Key. */
class DirectProviderClientTest {
    private class Transport(var response: String) : DirectProviderTransport {
        var endpoint = ""; var calls = 0; var request = JSONObject()
        override suspend fun exchange(endpoint: String, key: String, json: String): String {
            this.endpoint = endpoint; calls++; request = JSONObject(json); return response
        }
    }
    private fun response(content: Any?, finish: String = "stop", reasoning: String = ""): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", content ?: JSONObject.NULL).put("reasoning_content", reasoning)))).toString()
    private val conversation = listOf(DirectChatMessage("system", "合成恋人约束"), DirectChatMessage("user", "合成提问"))
    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected failure")
    }
    @Test fun completeAnswerIsPreservedInOneOfficialRequestAndThinkingOptionsAreExplicit() = runBlocking {
        val longAnswer = "完整回答🐈\n".repeat(5000)
        val transport = Transport(response(longAnswer))
        val answer = DirectProviderClient(transport).complete("deepseek", "synthetic-key", "deepseek-flash", conversation, 32768)
        assertEquals(longAnswer, answer)
        assertEquals("https://api.deepseek.com/chat/completions", transport.endpoint)
        assertEquals(1, transport.calls)
        assertFalse(transport.request.getBoolean("stream"))
        assertEquals(32768, transport.request.getInt("max_tokens"))
        assertEquals("disabled", transport.request.getJSONObject("thinking").getString("type"))
        assertEquals(2, transport.request.getJSONArray("messages").length())
    }
    @Test fun validationMakesATinyRealModelRequestAndReasoningIsNotDisplayedAsTheAnswer() = runBlocking {
        val transport = Transport(response(null, "length", "synthetic private reasoning"))
        val client = DirectProviderClient(transport)
        client.validate("qwen", "synthetic-key", "qwen-plus")
        assertEquals(1, transport.calls)
        assertEquals(8, transport.request.getInt("max_tokens"))
        assertFalse(transport.request.getBoolean("enable_thinking"))
        transport.response = response(null, "stop", "synthetic private reasoning")
        val error = failure { client.complete("qwen", "synthetic-key", "qwen-plus", conversation) } as ApiFailure
        assertEquals("provider_response_invalid", error.errorCode)
        assertFalse(error.message!!.contains("synthetic private reasoning"))
    }
    @Test fun anOutputLimitRejectsATruncatedAnswerInsteadOfPresentingItAsComplete() = runBlocking {
        val transport = Transport(response("没有写完的答案", "length"))
        val error = failure { DirectProviderClient(transport).complete("deepseek", "synthetic-key", "deepseek-flash", conversation) } as ApiFailure
        assertEquals("provider_output_limit", error.errorCode)
    }
    @Test fun malformedErrorsAndUnsupportedResponsePartsNeverExposeProviderBodies() = runBlocking {
        val transport = Transport("{\"error\":\"private-body-and-synthetic-key\"}")
        val client = DirectProviderClient(transport)
        val error = failure { client.complete("deepseek", "synthetic-key", "deepseek-flash", conversation) } as ApiFailure
        assertFalse(error.message!!.contains("private-body"))
        transport.response = response(JSONArray().put(JSONObject().put("type", "image_url").put("text", "private-body")))
        assertTrue(failure { client.complete("deepseek", "synthetic-key", "deepseek-flash", conversation) } is ApiFailure)
    }
    @Test fun loneSurrogatesCannotBeSilentlySavedWhileValidEmojiRemainComplete() = runBlocking {
        val transport = Transport("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"broken\\ud800\"}}]}")
        val client = DirectProviderClient(transport)
        assertEquals("provider_response_invalid", (failure { client.complete("deepseek", "synthetic-key", "deepseek-flash", conversation) } as ApiFailure).errorCode)
        transport.response = response("完整🐈")
        assertEquals("完整🐈", client.complete("deepseek", "synthetic-key", "deepseek-flash", conversation))
    }
    @Test fun minimaxPrivateThinkBlockIsRemovedButTheFullFinalAnswerIsKept() = runBlocking {
        val transport = Transport(response("<think>private reasoning</think>\n完整最终回答🐈"))
        assertEquals("完整最终回答🐈", DirectProviderClient(transport).complete("minimax", "synthetic-key", "MiniMax-M2.7", conversation))
    }
    @Test fun unsupportedProvidersModelsAndUnsafeKeysFailBeforeAnyRequest() = runBlocking {
        val transport = Transport(response("OK")); val client = DirectProviderClient(transport)
        assertTrue(failure { client.complete("unknown", "synthetic-key", "model", conversation) } is IllegalArgumentException)
        assertTrue(failure { client.complete("deepseek", "key\nAuthorization: bad", "deepseek-flash", conversation) } is IllegalArgumentException)
        assertTrue(failure { client.complete("kimi", "synthetic-key", "kimi-k3", conversation) } is IllegalArgumentException)
        assertEquals(0, transport.calls)
    }
}
