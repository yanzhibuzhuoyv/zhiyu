package com.zhiyu.app.data

import com.zhiyu.app.data.local.qwen.QwenScenePromptClient
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Snapshot and paid-call accounting; fake pixel encoding is not an image accuracy proof. */
class QwenScenePromptClientTest {
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1)
    private val request = QwenScenePromptRequest("a".repeat(32), "2026-10-12", "b".repeat(32),
        "12:30", "午间小猫", listOf("cat", "afternoon"))
    private val prompt = "A quiet garden with one cat in warm afternoon sunlight."
    private class Store : CredentialStore {
        var connection: SavedConnection? = SavedConnection(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-key", "deepseek-flash")
        var binding: CompanionBinding? = CompanionBinding("deepseek", "deepseek-flash")
        override fun read() = connection
        override fun write(connection: SavedConnection) { this.connection = connection }
        override fun clear() { connection = null }
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
    }
    private class Client(var answer: String) : DirectProviderClient() {
        var calls = 0; var messages = emptyList<DirectChatMessage>()
        var onCall: () -> Unit = {}
        override suspend fun complete(providerId: String, key: String, model: String,
            messages: List<DirectChatMessage>, maxTokens: Int): String {
            assertEquals("deepseek", providerId); assertEquals("synthetic-key", key)
            assertEquals("deepseek-flash", model)
            calls++; this.messages = messages
            onCall()
            return answer
        }
    }
    private fun helper(store: Store, client: Client) = QwenScenePromptClient(store, client) { bytes ->
        ChatPhotoPayload(ChatPhotoRules.sha(bytes), ChatPhotoRules.sha(bytes), "image/png", bytes)
    }

    @Test fun promptUsesBoundUserKeyAndSceneDataInOneTextOnlyCall() = runTest {
        val store = Store(); val client = Client("{\"prompt\":\"$prompt\"}")
        val result = helper(store, client).createPrompt(request)
        assertTrue(result.contains("no people")); assertEquals(1, client.calls)
        assertTrue(client.messages.all { it.photos.isEmpty() })
        assertTrue(client.messages.last().content.contains(request.sceneId))
        assertFalse(client.messages.any { it.content.contains("synthetic-key") })
    }

    @Test fun reviewCarriesPixelsNotOcrAndBindsApprovalToOriginalPngSha() = runTest {
        val store = Store(); val client = Client("{\"approved\":true,\"reason\":\"符合小猫午后场景\",\"correction\":\"\"}")
        val result = helper(store, client).review(request, prompt, png)
        assertEquals(QwenSceneReviewStatus.APPROVED, result.status)
        assertEquals(ChatPhotoRules.sha(png), result.imageSha256)
        assertEquals(1, client.calls)
        assertArrayEquals(png, client.messages.single { it.photos.isNotEmpty() }.photos.single().bytes)
        assertFalse(result.identityVerified)
    }

    @Test fun unsupportedVisionDoesNotPayForAFalseTextOnlyApproval() = runTest {
        val store = Store().apply {
            connection = connection!!.copy(model = "deepseek-v4-pro")
            binding = binding!!.copy(model = "deepseek-v4-pro")
        }
        val client = Client("{\"approved\":true,\"reason\":\"filename looks good\",\"correction\":\"\"}")
        val result = helper(store, client).review(request, prompt, png)
        assertEquals(QwenSceneReviewStatus.UNAVAILABLE, result.status)
        assertEquals(0, client.calls)
    }

    @Test fun changedConnectionAfterAResponseCannotApproveOrAutomaticallyRepeat() = runTest {
        val store = Store(); val client = Client("{\"approved\":true,\"reason\":\"okay\",\"correction\":\"\"}")
        client.onCall = { store.connection = store.connection!!.copy(apiKey = "different-synthetic-key") }
        val result = helper(store, client).review(request, prompt, png)
        assertEquals(QwenSceneReviewStatus.UNKNOWN, result.status)
        assertEquals(1, client.calls)
        assertFalse(result.reason.contains("synthetic-key"))
    }

    @Test fun providerFailureOrMalformedApprovalIsUnknownAndNeverRetried() = runTest {
        val store = Store(); val client = Client("{\"approved\":true,\"reason\":\"okay\"}")
        assertEquals(QwenSceneReviewStatus.UNKNOWN, helper(store, client).review(request, prompt, png).status)
        assertEquals(1, client.calls)
        client.onCall = { throw ApiFailure(0, "private-upstream-body", "provider_connection_failed") }
        val result = helper(store, client).review(request, prompt, png)
        assertEquals(QwenSceneReviewStatus.UNKNOWN, result.status)
        assertEquals(2, client.calls)
        assertFalse(result.reason.contains("private-upstream-body"))
    }

    @Test fun referenceBytesMustMatchMetadataBeforeAnotherCloudRequest() = runTest {
        val store = Store(); val client = Client("{\"approved\":true,\"reason\":\"okay\",\"correction\":\"\"}")
        val result = helper(store, client).review(request.copy(referenceSha256 = "f".repeat(64)), prompt, png, png)
        assertEquals(QwenSceneReviewStatus.UNAVAILABLE, result.status)
        assertEquals(0, client.calls)
    }

    @Test fun cancellationPropagatesWithoutASecondRequest() = runTest {
        val store = Store(); val client = Client("unused")
        client.onCall = { throw CancellationException("synthetic cancel") }
        try { helper(store, client).review(request, prompt, png); fail("Cancellation must reach the worker reservation") }
        catch (_: CancellationException) { }
        assertEquals(1, client.calls)
    }

    @Test fun compositeRequiresAReferenceAndCarriesSeparateRealPixelPartsWhenProvided() = runTest {
        val store = Store(); val client = Client("{\"approved\":true,\"reason\":\"人工合成测试回复\",\"correction\":\"\"}")
        assertEquals(QwenSceneReviewStatus.UNAVAILABLE,
            helper(store, client).review(request, prompt, png, purpose = QwenSceneReviewPurpose.COMPOSITE).status)
        assertEquals(0, client.calls)
        val reference = png + byteArrayOf(2)
        val result = helper(store, client).reviewComposite(request.copy(referenceSha256 = ChatPhotoRules.sha(reference)), prompt, png, reference)
        assertEquals(QwenSceneReviewPurpose.COMPOSITE, result.purpose)
        assertEquals(2, client.messages.count { it.photos.isNotEmpty() })
        assertArrayEquals(reference, client.messages.first { it.photos.isNotEmpty() }.photos.single().bytes)
        assertArrayEquals(png, client.messages.last().photos.single().bytes)
        assertTrue(client.messages.first().content.contains("body region absent"))
        assertFalse(result.identityVerified)
    }

    @Test fun changedBindingAfterPromptRequestInvalidatesTheOldPromptWithoutRetry() = runTest {
        val store = Store(); val client = Client("{\"prompt\":\"$prompt\"}")
        client.onCall = { store.binding = store.binding!!.copy(model = "deepseek-v4-pro") }
        try { helper(store, client).createPrompt(request); fail("A new binding cannot inherit old generated instructions") }
        catch (failure: ApiFailure) { assertEquals("scene_prompt_stale", failure.errorCode) }
        assertEquals(1, client.calls)
    }

    @Test fun povChecksOnlyVisibleFragmentsAndStillRequiresTheOriginalReference() = runTest {
        val store = Store(); val client = Client("{\"approved\":true,\"reason\":\"片段测试复审\",\"correction\":\"\"}")
        assertEquals(QwenSceneReviewStatus.UNAVAILABLE,
            helper(store, client).review(request, prompt, png, purpose = QwenSceneReviewPurpose.POV).status)
        assertEquals(0, client.calls)
        val reference = png + byteArrayOf(3)
        val result = helper(store, client).review(request.copy(referenceSha256 = ChatPhotoRules.sha(reference)), prompt,
            png, reference, QwenSceneReviewPurpose.POV)
        assertEquals(QwenSceneReviewPurpose.POV, result.purpose)
        assertTrue(client.messages.first().content.contains("only visible fragments"))
        assertTrue(client.messages.first().content.contains("do not require the full character"))
        assertEquals(2, client.messages.count { it.photos.isNotEmpty() })
    }
}
