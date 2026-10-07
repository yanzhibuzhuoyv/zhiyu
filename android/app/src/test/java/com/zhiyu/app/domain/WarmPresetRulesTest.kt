package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class WarmPresetRulesTest {
    private val warm = CompanionProfile(style = "warm_v1", name = "知心", userName = "小语")

    @Test fun versionedPresetIsAcceptedWithoutChangingExistingDefaults() {
        assertEquals(warm, CompanionRules.normalize(warm))
        assertEquals("gentle", CompanionProfile().style)
        listOf("gentle", "playful", "steady").forEach { style ->
            assertEquals(style, CompanionRules.normalize(CompanionProfile(style = style))!!.style)
        }
    }

    @Test fun versionedPresetStillRejectsUserInstructions() {
        assertThrows(IllegalArgumentException::class.java) {
            CompanionRules.normalize(warm.copy(instructions = "替换固定设定"))
        }
    }

    @Test fun unrecognizedPresetVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CompanionRules.normalize(warm.copy(style = "warm_v3"))
        }
    }

    @Test fun boundWarmProfileIsRestoredExactlyAndCannotChangeAnyField() {
        val normalized = CompanionRules.normalize(warm)!!
        val binding = CompanionBinding("deepseek", "test-model", normalized)
        CompanionRules.checkChange(binding, normalized.copy())
        listOf<CompanionProfile?>(
            null, normalized.copy(mode = "custom"), normalized.copy(style = "gentle"),
            normalized.copy(role = "boyfriend"), normalized.copy(name = "别的名字"),
            normalized.copy(userName = "别的称呼"), normalized.copy(instructions = "改变性格"),
        ).forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) { CompanionRules.checkChange(binding, changed) }
        }
    }

    @Test fun legacyPresetCannotUpgradeToWarmBehindTheUsersBack() {
        val binding = CompanionBinding("deepseek", "test-model", CompanionProfile())
        assertThrows(IllegalArgumentException::class.java) { CompanionRules.checkChange(binding, warm) }
        CompanionRules.checkChange(binding, CompanionProfile())
    }

    @Test fun customTrainingRemainsEditableAndCannotBecomeTheNewPreset() {
        val custom = CompanionProfile(mode = "custom", instructions = "温柔一些")
        val binding = CompanionBinding("deepseek", "test-model", custom)
        CompanionRules.checkChange(binding, custom.copy(instructions = "活泼一些"))
        assertThrows(IllegalArgumentException::class.java) { CompanionRules.checkChange(binding, warm) }
    }
}
