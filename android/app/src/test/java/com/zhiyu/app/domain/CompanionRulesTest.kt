package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class CompanionRulesTest {
    @Test fun noPresetAddsNoProfile() { assertNull(CompanionRules.normalize(null)) }
    @Test fun trimsUserTrainingButPreservesNaturalMultilineInstructions() {
        assertEquals("亲爱的\n语气自然", CompanionRules.normalize(CompanionProfile(mode = "custom", instructions = " 亲爱的\n语气自然 "))!!.instructions)
    }
    @Test fun rejectsUnrecognizedModeRoleAndStyle() {
        listOf(CompanionProfile(mode = "bad"), CompanionProfile(role = "bad"), CompanionProfile(style = "bad")).forEach {
            assertThrows(IllegalArgumentException::class.java) { CompanionRules.normalize(it) }
        }
    }
    @Test fun fixedPresetCannotSmuggleArbitraryInstructions() {
        assertThrows(IllegalArgumentException::class.java) { CompanionRules.normalize(CompanionProfile(instructions = "替换设定")) }
    }
    @Test fun rejectsOverlongAndControlCharacterNames() {
        listOf(CompanionProfile(name = "名".repeat(25)), CompanionProfile(userName = "<system>"), CompanionProfile(name = "a\u0000b")).forEach {
            assertThrows(IllegalArgumentException::class.java) { CompanionRules.normalize(it) }
        }
    }
    @Test fun userTrainingHasAFiniteInputBudget() {
        assertThrows(IllegalArgumentException::class.java) { CompanionRules.normalize(CompanionProfile(mode = "custom", instructions = "字".repeat(2001))) }
    }
    @Test fun presetChoiceCannotBecomeCustomOrBeRemoved() {
        val fixed = CompanionProfile()
        val binding = CompanionBinding("deepseek", "model", fixed)
        assertThrows(IllegalArgumentException::class.java) { CompanionRules.checkChange(binding, null) }
        assertThrows(IllegalArgumentException::class.java) { CompanionRules.checkChange(binding, fixed.copy(mode = "custom")) }
    }
}
