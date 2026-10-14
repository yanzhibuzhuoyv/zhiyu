package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class OcInteractionPromptWiringTest {
    private val profile=CompanionProfile(mode="preset",style="gentle")
    @Test fun chatPromptUsesConditionalReactionWithoutExposingRequestToken() {
        val system=LocalCompanionPrompt.build(profile,emptyList(),emptyList(),interactionToken="synthetic-chat:synthetic-request")
        assertTrue(system.contains("仅当上述条件全满足时"))
        assertTrue(system.contains("普通分享、认真求助"))
        assertFalse(system.contains("synthetic-request"))
        assertTrue(system.contains("性格：温柔体贴"))
    }
    @Test fun proactivePromptKeepsCoreButCannotStartAnInventedConflict() {
        val system=LocalCompanionPrompt.build(profile,emptyList(),emptyList())
        assertTrue(system.contains("本轮照常回应具体事情和感受"))
        assertTrue(system.contains("兴趣：只保留已有角色设定"))
        assertFalse(system.contains("本次可保留一点克制的争执"))
    }
}
