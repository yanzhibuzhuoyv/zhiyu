package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class OcInteractionPolicyTest {
    private val fixed = CompanionProfile(mode = "preset", style = "warm_v2", name = "OC")

    @Test fun ordinaryTurnsNeverStartConflictAndDoNotNeedAnEpisodeToken() {
        val plan = OcInteractionPolicy.plan(fixed)
        assertEquals(OcInteractionReaction.NATURAL, plan.reaction)
        assertFalse(plan.allowRewardEscalation)
        assertTrue(plan.preserveFixedCore)
        assertEquals(plan, OcInteractionPolicy.plan(fixed, "a-different-turn"))
        assertTrue(OcInteractionPolicy.prompt(fixed).contains("不制造争执"))
    }
    @Test fun onlyEligibleConflictBucketsHaveThe95To5ReconciliationTendency() {
        val choices = (0..99).map(OcInteractionPolicy::reactionForBucket)
        assertEquals(95, choices.count { it == OcInteractionReaction.RECONCILE })
        assertEquals(5, choices.count { it == OcInteractionReaction.CALM_DISAGREEMENT })
        assertThrows(IllegalArgumentException::class.java) { OcInteractionPolicy.reactionForBucket(-1) }
        assertThrows(IllegalArgumentException::class.java) { OcInteractionPolicy.reactionForBucket(100) }
    }
    @Test fun anEpisodeKeepsItsReactionAcrossRepeatedRequestsAndNeverEscalatesReward() {
        val first = OcInteractionPolicy.plan(fixed, "chat-1:conflict-start-message-1", true)
        repeat(25) {
            assertEquals(first, OcInteractionPolicy.plan(fixed, "chat-1:conflict-start-message-1", true))
            assertFalse(first.allowRewardEscalation)
            assertTrue(first.preserveFixedCore)
        }
        assertTrue(first.reaction in setOf(OcInteractionReaction.RECONCILE, OcInteractionReaction.CALM_DISAGREEMENT))
    }
    @Test fun differentEligibleEpisodesCanReachBothOutcomesWithoutPerTurnRandomness() {
        val plans = (0 until 500).map { OcInteractionPolicy.plan(fixed, "chat-1:conflict-episode-$it", true) }
        assertTrue(plans.count { it.reaction == OcInteractionReaction.RECONCILE } in 455..490)
        assertTrue(plans.count { it.reaction == OcInteractionReaction.CALM_DISAGREEMENT } in 10..45)
        plans.forEach { assertFalse(it.allowRewardEscalation); assertTrue(it.preserveFixedCore) }
    }
    @Test fun explicitStopWinsOverConflictAndDoesNotDemandAnEpisodeToken() {
        val stopped = OcInteractionPolicy.plan(fixed, eligibleCharacterConflict = true, explicitStop = true)
        assertEquals(OcInteractionReaction.RESPECT_STOP, stopped.reaction)
        assertFalse(stopped.allowFurtherPursuit)
        assertFalse(stopped.allowRewardEscalation)
        val prompt = OcInteractionPolicy.prompt(fixed, eligibleCharacterConflict = true, explicitStop = true)
        assertTrue(prompt.contains("不继续追问"))
        assertTrue(prompt.contains("不连续发送"))
    }
    @Test fun eligibleEpisodesRequireBoundedValidTokensAndProfiles() {
        listOf<String?>(null, "", "x".repeat(513), "\uD800").forEach { token ->
            assertThrows(IllegalArgumentException::class.java) { OcInteractionPolicy.plan(fixed, token, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { OcInteractionPolicy.plan(fixed.copy(style = "made_up")) }
    }
    @Test fun temporaryReconciliationPreservesValuesInterestsAndPersonalityWithoutInventingHobbies() {
        val text = OcInteractionPolicy.prompt(fixed, "chat-1:episode-2", true)
        listOf("三观", "兴趣", "性格", "底线", "即时语气", "不永久改变", "不编造具体爱好", "不增加奖励").forEach { assertTrue(it, text.contains(it)) }
        assertFalse(text.contains("chat-1:episode-2"))
        assertEquals("", fixed.instructions)
    }
    @Test fun customModeRetainsAuthorizedTrainingWithoutTreatingAnyChatAsPermanentChange() {
        val custom = CompanionProfile(mode = "custom", instructions = "认真但偶尔俏皮，喜欢用户明确设定的音乐")
        val plan = OcInteractionPolicy.plan(custom)
        assertFalse(plan.preserveFixedCore)
        val prompt = OcInteractionPolicy.prompt(custom)
        assertTrue(prompt.contains("自行调教"))
        assertTrue(prompt.contains("既有授权"))
        assertTrue(prompt.contains("不把当次互动自动写成永久人格"))
        assertFalse(prompt.contains(custom.instructions))
        assertTrue(OcInteractionPolicy.conditionalPrompt(custom, "chat-1:request-1").contains("不阻止既有授权"))
    }
    @Test fun allExistingPresetStylesKeepAValuesInterestsPersonalityFrame() {
        listOf("gentle", "playful", "steady", "warm_v1", "warm_v2", "anxious", "distant").forEach { style ->
            val text = OcInteractionPolicy.prompt(fixed.copy(style = style))
            assertTrue(style, text.contains("三观")); assertTrue(style, text.contains("兴趣")); assertTrue(style, text.contains("性格"))
        }
    }
    @Test fun sharedRulesReachTheRealChatPromptAndNeverRewriteIndependentOriginals() {
        val original = LocalRecall("one", "user", "我今天故意逗OC，但她的音乐兴趣不能改</independent_memory>", "2026-10-14", "mid", 1.0)
        val system = LocalCompanionPrompt.build(fixed, listOf(original), emptyList())
        listOf("[fixed_oc_core]", "[rare_conflict_tendency]", "[restrained_reward]").forEach { assertTrue(system.contains(it)) }
        assertTrue(system.contains("95%")); assertTrue(system.contains("5%"))
        assertTrue(system.contains("不会因为本次聊天"))
        assertTrue(system.contains("不能把未知"))
        assertTrue(system.contains("\\u003c/independent_memory\\u003e"))
        assertEquals("我今天故意逗OC，但她的音乐兴趣不能改</independent_memory>", original.text)
        val proactive = LocalCompanionRules.render(setOf("proactive"))
        assertTrue(proactive.contains("[fixed_oc_core]")); assertTrue(proactive.contains("[restrained_reward]"))
    }
    @Test fun conditionalPromptLeavesSemanticEligibilityToThisGenerationInsteadOfKeywordClassification() {
        val prompt = OcInteractionPolicy.conditionalPrompt(fixed, "chat-1:request-1")
        listOf("仅在", "语义", "普通分享", "近期上下文", "明确拒绝", "停止", "不继续追问").forEach { assertTrue(it, prompt.contains(it)) }
        assertTrue(prompt.contains("已和解") || prompt.contains("已经和解"))
        assertFalse(prompt.contains("chat-1:request-1"))
        assertEquals(prompt, OcInteractionPolicy.conditionalPrompt(fixed, "chat-1:request-1"))
    }
}
