package com.zhiyu.app.data.remotescene

import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.test.runTest
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class LifePhotoPromptClientTest {
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1)
    private val chat = "a".repeat(32)
    private val scene = LocalDailyRules.scene(chat, 1, Instant.parse("2026-10-14T00:00:00Z"))
    private val target = LocalScenePrefetchTarget(scene, 1, scene.slots[1], false)
    private val checks = "\"checks\":{\"identity\":true,\"counts\":true,\"props\":true,\"scene\":true,\"crop\":true}"
    private class Store : CredentialStore {
        var saved = SavedConnection("local://phone", "deepseek", "synthetic-chat-key", "deepseek-v4-flash")
        var bound = CompanionBinding("deepseek", "deepseek-v4-flash")
        override fun read() = saved
        override fun write(connection: SavedConnection) { saved = connection }
        override fun clear() = Unit
        override fun readBinding() = bound
        override fun writeBinding(binding: CompanionBinding) { bound = binding }
    }
    private class Provider(var answer: String) : DirectProviderClient() {
        var calls = 0; var messages = emptyList<DirectChatMessage>(); var changed: () -> Unit = {}
        override suspend fun complete(providerId: String, key: String, model: String, messages: List<DirectChatMessage>, maxTokens: Int): String {
            assertEquals("deepseek", providerId); assertEquals("synthetic-chat-key", key)
            calls++; this.messages = messages; changed(); return answer
        }
    }
    private fun binding(store: Store) = RemoteSceneBinding(chat,
        VisualProfile("import", "original OC", "unspecified", "long", 1, "b".repeat(32), null, GenerationQuota(false, 0, 0, 2)),
        null, OcAssetRef("b".repeat(32), ChatPhotoRules.sha(png), 1), "synthetic-image-key", Image2Protocol.DEFAULT_MODEL,
        store.saved, store.bound, png)
    private fun client(store: Store, provider: Provider) = LifePhotoPromptClient(store, provider) {
        ChatPhotoPayload(ChatPhotoRules.sha(it), ChatPhotoRules.sha(it), "image/png", it)
    }

    @Test fun promptUsesBoundChatProviderAndPermitsActivityPoseWithoutInventingUnseenBody() = runTest {
        val store = Store(); val provider = Provider("{\"prompt\":\"The same OC holding a cup by the morning window.\"}")
        val prompt = client(store, provider).create(binding(store), target)
        assertTrue(prompt.contains("same OC")); assertEquals(1, provider.calls)
        val instruction = provider.messages.first().content
        assertTrue(instruction.contains("pose")); assertTrue(instruction.contains("unseen body"))
        assertFalse(instruction.contains("background-only")); assertFalse(instruction.contains("never generate a person"))
        assertTrue(provider.messages.last().content.contains(scene.sceneId))
        assertArrayEquals(png, provider.messages.flatMap { it.photos }.single().bytes)
        assertFalse(provider.messages.any { it.content.contains("synthetic-chat-key") })
    }
    @Test fun reviewSendsBothActualPixelPayloadsAndApprovalNeverClaimsCertainIdentity() = runTest {
        val store = Store(); val provider = Provider("{\"approved\":true,\"reason\":\"synthetic visual comparison\",\"correction\":\"\",$checks}")
        val other = png + byteArrayOf(2)
        val review = client(store, provider).review(binding(store), target, "same OC tea", AgnesGeneratedImage(other, "image/png"))
        assertEquals(LifePhotoReviewStatus.APPROVED, review.status); assertFalse(review.identityVerified)
        assertEquals(ChatPhotoRules.sha(other), review.imageSha256)
        val images = provider.messages.filter { it.photos.isNotEmpty() }
        assertEquals(2, images.size); assertArrayEquals(png, images.first().photos.single().bytes)
        assertArrayEquals(other, images.last().photos.single().bytes)
        assertTrue(provider.messages.first().content.contains("appendages")); assertTrue(provider.messages.first().content.contains("crop"))
    }
    @Test fun unavailableNativeVisionDoesNotCallAnotherAiOrApproveFromText() = runTest {
        val store = Store().apply { saved = saved.copy(model = "deepseek-v4-pro"); bound = bound.copy(model = "deepseek-v4-pro") }
        val provider = Provider("{\"approved\":true}")
        val review = client(store, provider).review(binding(store), target, "same OC tea", AgnesGeneratedImage(png, "image/png"))
        assertEquals(LifePhotoReviewStatus.UNAVAILABLE, review.status); assertEquals(0, provider.calls)
    }
    @Test fun keyChangedAfterActualPixelRequestInvalidatesReviewAndNeverRepeats() = runTest {
        val store = Store(); val provider = Provider("{\"approved\":true,\"reason\":\"synthetic\",\"correction\":\"\",$checks}")
        provider.changed = { store.saved = store.saved.copy(apiKey = "new-synthetic-key") }
        val review = client(store, provider).review(binding(store), target, "same OC tea", AgnesGeneratedImage(png, "image/png"))
        assertEquals(LifePhotoReviewStatus.UNKNOWN, review.status); assertEquals(1, provider.calls)
    }
    @Test fun malformedReviewDoesNotApproveAndAuthFailureReachesBatchStop() = runTest {
        val store = Store(); val provider = Provider("{\"approved\":true,\"reason\":\"missing correction\"}")
        assertEquals(LifePhotoReviewStatus.UNKNOWN, client(store, provider).review(binding(store), target,
            "same OC tea", AgnesGeneratedImage(png, "image/png")).status)
        provider.changed = { throw ApiFailure(401, "synthetic", "provider_auth_failed") }
        try { client(store, provider).review(binding(store), target, "same OC tea", AgnesGeneratedImage(png, "image/png")); fail() }
        catch (failure: ApiFailure) { assertEquals("provider_auth_failed", failure.errorCode) }
    }
    @Test fun overallApprovalCannotOverrideUncertainCountOrUnseenCropCheck() = runTest {
        val store = Store(); val provider = Provider("{\"approved\":true,\"reason\":\"synthetic\",\"correction\":\"\",${checks.replace("\"counts\":true", "\"counts\":false")}}")
        assertEquals(LifePhotoReviewStatus.REJECTED, client(store, provider).review(binding(store), target,
            "same OC tea", AgnesGeneratedImage(png, "image/png")).status)
        assertEquals(1, provider.calls)
    }
    @Test fun selfiePurposeOverridesLandscapeIndexWhileKeepingActualSlotAndCropConstraints() = runTest {
        val store = Store(); val provider = Provider("{\"prompt\":\"The same OC in a new portrait selfie.\"}")
        val real = LocalScenePrefetchTarget(scene, 0, scene.slots[0], false,
            LocalSelfieTicket("a".repeat(64), 0, 0))
        val prompt = client(store, provider).create(binding(store), real)
        assertTrue(prompt.contains("selfie", ignoreCase = true))
        assertFalse(prompt.contains("scenery only"))
        assertTrue(provider.messages.last().content.contains("\"view\":\"portrait\""))
        assertTrue(provider.messages.last().content.contains("\"purpose\":\"selfie\""))
        assertTrue(provider.messages.last().content.contains(scene.slots[0].activity))
        assertTrue(provider.messages.first().content.contains("unseen body"))
    }
    @Test fun borrowedSlotReplacementDescribesAnotherPhotoMomentWithoutChangingItsStoredTrajectory() = runTest {
        val store = Store(); val provider = Provider("{\"prompt\":\"The same OC in a distinct new pose by the window.\"}")
        val time = java.time.LocalTime.parse(target.slot.time).plusMinutes(1).toString()
        val replacement = target.copy(reshoot = LocalSceneReshoot("c".repeat(32), "d".repeat(64), time))
        client(store, provider).create(binding(store), replacement)
        val metadata = provider.messages.last().content
        assertTrue(metadata.contains("\"capture_time\":\"$time\""))
        assertTrue(metadata.contains("\"time\":\"${target.slot.time}\""))
        assertTrue(metadata.contains(LocalDailyRules.revision(scene)))
        assertTrue(provider.messages.first().content.contains("distinct", ignoreCase = true))
    }

    @Test fun visualPromptAuthorReceivesTheCurrentOriginalPixelsRatherThanGuessingAppearanceFromText() = runTest {
        val store = Store(); val provider = Provider("{\"prompt\":\"The same OC sitting by the window.\"}")
        client(store, provider).create(binding(store), target)
        val image = provider.messages.flatMap { it.photos }.single()
        assertArrayEquals(png, image.bytes)
        assertEquals(ChatPhotoRules.sha(png), image.originalSha256)
        assertTrue(provider.messages.first().content.contains("fixed appearance"))
        assertFalse(provider.messages.any { it.content.contains("synthetic-image-key") })
        assertEquals(1, provider.calls)
    }

    @Test fun sceneryPromptsAndReviewDoNotRequireAnOcFigureButKeepItsImageStyle() = runTest {
        val store = Store(); val provider = Provider("{\"prompt\":\"A quiet landscape in the reference artwork style.\"}")
        val landscape = target.copy(index = 0)
        val prompt = client(store, provider).create(binding(store), landscape)
        assertTrue(prompt.contains("scenery-only", ignoreCase = true))
        assertFalse(prompt.contains("Preserve the same original OC identity, face, clothes"))
        assertTrue(prompt.contains("reference image style"))
        provider.answer = "{\"approved\":true,\"reason\":\"No person, matching artwork style\",\"correction\":\"\",$checks}"
        client(store, provider).review(binding(store), landscape, prompt, AgnesGeneratedImage(png + byteArrayOf(2), "image/png"))
        assertTrue(provider.messages.first().content.contains("absence of the OC is required"))
        assertTrue(provider.messages.first().content.contains("identity check"))
    }
}
