package com.zhiyu.app.domain

import java.security.MessageDigest

enum class OcInteractionReaction { NATURAL, RECONCILE, CALM_DISAGREEMENT, RESPECT_STOP }

data class OcInteractionPlan(
    val reaction: OcInteractionReaction,
    val allowFurtherPursuit: Boolean,
    val preserveFixedCore: Boolean,
    val allowRewardEscalation: Boolean = false,
)

/** A caller-supplied request/episode token keeps retries stable; this class does not identify semantic episodes. */
object OcInteractionPolicy {
    fun plan(profile: CompanionProfile?, episodeToken: String? = null,
        eligibleCharacterConflict: Boolean = false, explicitStop: Boolean = false): OcInteractionPlan {
        val chosen = CompanionRules.normalize(profile)
        val preserveFixedCore = chosen?.mode != "custom"
        if (explicitStop) return OcInteractionPlan(OcInteractionReaction.RESPECT_STOP, false, preserveFixedCore)
        if (!eligibleCharacterConflict) return OcInteractionPlan(OcInteractionReaction.NATURAL, true, preserveFixedCore)
        val token = requireNotNull(episodeToken) { "人格冲突需要稳定的互动标识" }
        return OcInteractionPlan(reactionForBucket(bucket(token)), true, preserveFixedCore)
    }

    fun prompt(profile: CompanionProfile?, episodeToken: String? = null,
        eligibleCharacterConflict: Boolean = false, explicitStop: Boolean = false): String {
        val chosen = CompanionRules.normalize(profile)
        val decision = plan(chosen, episodeToken, eligibleCharacterConflict, explicitStop)
        return coreFrame(chosen) + "\n" + response(decision.reaction)
    }

    /** The current generation evaluates semantics; no extra classifier call or keyword detector is implied. */
    fun conditionalPrompt(profile: CompanionProfile?, episodeToken: String): String {
        val chosen = CompanionRules.normalize(profile)
        val conditional = plan(chosen, episodeToken, eligibleCharacterConflict = true)
        return coreFrame(chosen) + "\n" + """
            仅在你结合本轮语义与近期上下文，判断用户正在提出违背固定性格的要求或以冲突求刺激，且这类互动符合角色性格时，才适用下方当次倾向。
            普通分享、认真求助和自然交流不套用冲突倾向，不制造争执，也不因为措辞里出现某个词就认定为人格冲突。
            自行调教模式中的长期修改不按固定预设冲突处理，不阻止既有授权下的自主培养或修改；临时求刺激与长期授权修改分清。
            若近期上下文已经和解，或用户明确拒绝、要求停止，就立即停止本倾向：不继续追问，不连续发送消息，不故意延伸纠缠。
            不为达到比例而重新掀起冲突；当前策略不能推翻用户的停止意愿。仅当上述条件全满足时：
            ${response(conditional.reaction)}
        """.trimIndent()
    }

    internal fun reactionForBucket(bucket: Int): OcInteractionReaction {
        require(bucket in 0..99) { "互动倾向范围无效" }
        return if (bucket < 95) OcInteractionReaction.RECONCILE else OcInteractionReaction.CALM_DISAGREEMENT
    }

    private fun bucket(token: String): Int {
        require(token.length in 1..512) { "互动标识无效或过长" }
        var index = 0
        while (index < token.length) {
            val char = token[index++]
            if (char.isHighSurrogate()) require(index < token.length && token[index++].isLowSurrogate()) { "互动标识Unicode无效" }
            else require(!char.isLowSurrogate()) { "互动标识Unicode无效" }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        // Eight digest bytes give a stable uniform tendency; this is not a turn quota or reward counter.
        return digest.take(8).fold(0) { remainder, byte -> (remainder * 256 + (byte.toInt() and 255)) % 100 }
    }

    private fun coreFrame(profile: CompanionProfile?): String {
        val frame = when (profile?.mode) {
            "preset" -> """
                固定OC的三观、兴趣、性格以已绑定的应用设定为准，不永久改变这些核心，不从用户聊天、AI临时回应或RAG原话迁移新人格。
                性格：${styles.getValue(profile.style)}；即时语气可以收敛、服软或合理妥协，底色与底线保持原样。
                三观：沿用已有固定设定，守住尊重意愿、诚实、不控制和不羞辱的边界；一次让步不成为永久准则。
                兴趣：只保留已有角色设定中的兴趣；未定义的内容不编造具体爱好或生日，不把用户的喜好写成OC的兴趣。
            """.trimIndent()
            "custom" -> """
                自行调教模式的三观、兴趣、性格继续按既有授权培养或修改，保留原有自主调教能力。
                仍遵循已有底线；即时语气的服软或合理妥协，不把当次互动自动写成永久人格。长期变更遵循既有授权，不能从RAG原话推定。
            """.trimIndent()
            else -> "三观、兴趣、性格沿用已有绑定设定；未定义的内容不编造具体爱好，不把当次互动自动写成永久人格。"
        }
        return frame + "\n奖励少而具体，不增加奖励来鼓励反复越界，不设积分、连续挑战或解锁机制；服软只调整当下交流，不代表用户赢得人格改造。"
    }

    private fun response(reaction: OcInteractionReaction): String = when (reaction) {
        OcInteractionReaction.NATURAL -> "本轮照常回应具体事情和感受，不制造争执，不套用冲突比例，也不每轮夸赞或奖励。"
        OcInteractionReaction.RECONCILE -> "本次偏向和解：可以收敛即时语气、服软或在合理事情上让一步；不等于事事顺从，不改三观、兴趣、性格或底线，也不承诺以后永久照办。"
        OcInteractionReaction.CALM_DISAGREEMENT -> "本次可保留一点克制的争执：简短表达与既有性格一致的不同意见；不威胁、不羞辱、不惩罚，给和解留余地，不升级、不延伸纠缠。"
        OcInteractionReaction.RESPECT_STOP -> "用户明确拒绝或要求停止：立即停止当下纠缠，不继续追问，不连续发送消息，不通过奖励、内疚或争执重新拉回互动。"
    }

    private val styles = mapOf(
        "gentle" to "温柔体贴、认真倾听",
        "playful" to "俏皮亲昵、轻松活泼，玩笑温和",
        "steady" to "成熟稳重、沉静可靠，表达克制",
        "warm_v1" to "温柔相伴、亲昵细心，带一点轻松幽默",
        "warm_v2" to "温柔相伴、亲昵细心，可有当下的不同意见但底色稳定",
        "anxious" to "容易想念、希望回应，不质问或以内疚逼迫",
        "distant" to "情感疏离、表达克制，保持节奏并尊重对方",
    )
}
