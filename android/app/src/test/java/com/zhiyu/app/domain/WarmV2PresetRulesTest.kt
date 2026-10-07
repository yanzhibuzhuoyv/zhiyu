package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class WarmV2PresetRulesTest {
    private val warm = CompanionProfile(style = "warm_v2", name = "知心", userName = "小语")

    @Test fun currentTrialVersionIsAcceptedWithoutReplacingTheInitialVersion() {
        assertEquals(warm, CompanionRules.normalize(warm))
        val initial = warm.copy(style = "warm_v1")
        assertEquals(initial, CompanionRules.normalize(initial))
        assertEquals("gentle", CompanionProfile().style)
    }

    @Test fun temporaryCompromiseDoesNotUnlockTheSavedPersona() {
        val normalized = CompanionRules.normalize(warm)!!
        val binding = CompanionBinding("deepseek", "test-model", normalized)
        CompanionRules.checkChange(binding, normalized.copy())
        listOf<CompanionProfile?>(
            null, normalized.copy(mode = "custom"), normalized.copy(style = "warm_v1"),
            normalized.copy(instructions = "换一种性格"), normalized.copy(name = "另一位"),
        ).forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) { CompanionRules.checkChange(binding, changed) }
        }
    }

    @Test fun initialBindingCannotSilentlyBecomeTheCurrentTrialVersion() {
        val initial = warm.copy(style = "warm_v1")
        val binding = CompanionBinding("deepseek", "test-model", initial)
        CompanionRules.checkChange(binding, initial.copy())
        assertThrows(IllegalArgumentException::class.java) { CompanionRules.checkChange(binding, warm) }
    }

    @Test fun currentPresetDoesNotAcceptCustomInstructions() {
        assertThrows(IllegalArgumentException::class.java) {
            CompanionRules.normalize(warm.copy(instructions = "重新设定性格"))
        }
    }
}
