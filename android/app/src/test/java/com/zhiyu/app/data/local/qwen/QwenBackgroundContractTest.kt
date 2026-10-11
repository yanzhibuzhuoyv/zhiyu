package com.zhiyu.app.data.local.qwen

import org.junit.Assert.*
import org.junit.Test

class QwenBackgroundContractTest {
    private val identity = QwenSceneSnapshot("a".repeat(32), "2026-10-11", 4, "b".repeat(32), "c".repeat(64), "a".repeat(64))

    @Test fun completedBackgroundIsOnlyAcceptedForItsOriginalDayAndCharacter() {
        val request = QwenBackgroundRequest(identity, "无人街道，傍晚灯光")
        request.requireCurrent(identity)
        listOf(identity.copy(dayId = "2026-10-12"), identity.copy(visualVersion = 5),
            identity.copy(chatId = "d".repeat(32)), identity.copy(referenceId = "e".repeat(32)),
            identity.copy(referenceSha256 = "f".repeat(64)), identity.copy(characterPackageSha = "d".repeat(64))).forEach {
            assertThrows(IllegalArgumentException::class.java) { request.requireCurrent(it) }
        }
        assertEquals("text_to_image_background", request.capability)
    }

    @Test fun invalidPromptAndIdentityFailBeforeNativeGeneration() {
        listOf("", " ", "x".repeat(4001), "\ud800").forEach { prompt ->
            assertThrows(IllegalArgumentException::class.java) { QwenBackgroundRequest(identity, prompt) }
        }
        listOf(identity.copy(dayId = "2026-02-30"), identity.copy(visualVersion = -1),
            identity.copy(referenceSha256 = null), identity.copy(chatId = "../../private")).forEach {
            assertThrows(IllegalArgumentException::class.java) { QwenBackgroundRequest(it, "无人房间") }
        }
    }

    @Test fun backgroundGenerationDoesNotRequireOrReplaceAnOcReference() {
        val request = QwenBackgroundRequest(identity.copy(referenceId = null, referenceSha256 = null, characterPackageSha = null), "无人房间")
        request.requireCurrent(request.identity)
        assertNull(request.identity.referenceId)
    }

    @Test fun storageBudgetUsesExactActualWeightsAndCallerReserve() {
        val pack = QwenModelPackManifestTest.parse(QwenModelPackManifestTest.fixture(5_000_000_000L))
        assertEquals(40_000_000_123L, QwenPackCapacity.requiredStorage(pack, 2, 123))
        QwenPackCapacity.requireStorage(pack, 40_000_000_123L, 2, 123)
        assertThrows(IllegalArgumentException::class.java) {
            QwenPackCapacity.requireStorage(pack, 40_000_000_122L, 2, 123)
        }
    }

    @Test fun capacityOverflowAndInvalidInputsCannotWrapIntoAFalseSuccess() {
        val pack = QwenModelPackManifestTest.parse(QwenModelPackManifestTest.fixture())
        assertThrows(IllegalArgumentException::class.java) { QwenPackCapacity.requiredStorage(pack, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { QwenPackCapacity.requiredStorage(pack, 1, -1) }
        assertThrows(ArithmeticException::class.java) { QwenPackCapacity.requiredStorage(pack, 2, Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { QwenPackCapacity.requireStorage(pack, -1, 1, 0) }
    }

    @Test fun unknownMeasuredPeakCannotBeMistakenForPhoneReadiness() {
        assertFalse(QwenPackCapacity.memoryFits(null, 12_000_000_000L, 0))
        assertTrue(QwenPackCapacity.memoryFits(4_000_000_000L, 5_000_000_000L, 1_000_000_000L))
        assertFalse(QwenPackCapacity.memoryFits(4_000_000_000L, 4_999_999_999L, 1_000_000_000L))
        assertFalse(QwenPackCapacity.memoryFits(Long.MAX_VALUE, Long.MAX_VALUE, 1))
        assertThrows(IllegalArgumentException::class.java) { QwenPackCapacity.memoryFits(-1, 12, 0) }
    }
}
