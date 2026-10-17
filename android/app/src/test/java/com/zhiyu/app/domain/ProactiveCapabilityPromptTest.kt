package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class ProactiveCapabilityPromptTest {
    private val ready = ProactiveContactRuntime(userEnabled = true, notificationsAllowed = true, userPaused = false)

    @Test fun enabledAndPermittedContactIsSupportedWithoutPretendingItHasBeenDelivered() {
        assertEquals(ProactiveContactStatus.READY, ready.status)
        val prompt = render(ready)
        assertTrue(prompt.contains("proactive_contact_supported=true"))
        assertTrue(prompt.contains("proactive_contact_state=ready"))
        assertTrue(prompt.contains("可以主动找你"))
        assertTrue(prompt.contains("不代表已经发出或送达"))
        assertTrue(prompt.contains("不能保证准点"))
    }

    @Test fun blockedNotificationsNeverBecomeReadyMerelyBecauseTheAppSupportsContact() {
        val blocked = ready.copy(notificationsAllowed = false)
        assertEquals(ProactiveContactStatus.NOTIFICATIONS_BLOCKED, blocked.status)
        val prompt = render(blocked)
        assertTrue(prompt.contains("proactive_contact_supported=true"))
        assertTrue(prompt.contains("proactive_contact_state=notifications_blocked"))
        assertTrue(prompt.contains("通知未开放"))
        assertFalse(prompt.contains("proactive_contact_state=ready"))
    }

    @Test fun userPauseTakesPriorityEvenWhenNotificationAccessIsAlsoBlocked() {
        val paused = ready.copy(userPaused = true, notificationsAllowed = false)
        assertEquals(ProactiveContactStatus.PAUSED, paused.status)
        val prompt = render(paused)
        assertTrue(prompt.contains("proactive_contact_state=paused"))
        assertTrue(prompt.contains("先不打扰你"))
        assertTrue(prompt.contains("不能把暂停说成永远不能主动联系"))
        assertFalse(prompt.contains("proactive_contact_state=ready"))
    }

    @Test fun disabledOptInIsDifferentFromAnUnsupportedCapability() {
        val disabled = ready.copy(userEnabled = false)
        assertEquals(ProactiveContactStatus.USER_DISABLED, disabled.status)
        val prompt = render(disabled)
        assertTrue(prompt.contains("proactive_contact_state=user_disabled"))
        assertTrue(prompt.contains("主动联系尚未开启"))
        assertTrue(prompt.contains("不能据此说自己只能被动回复"))
    }

    @Test fun aMissingOrUnreadableSnapshotDoesNotInventPermissionOrDisability() {
        val prompt = render(null)
        assertTrue(prompt.contains("proactive_contact_state=unknown"))
        assertTrue(prompt.contains("当前开关状态未确认"))
        assertTrue(prompt.contains("不能断言已经开启"))
        assertFalse(prompt.contains("proactive_contact_state=ready"))
        assertTrue(prompt.contains("proactive_contact_supported=true"))
    }

    @Test fun backgroundRestrictionIsReportedSeparatelyFromCapabilityAndIsNeverAnArrivalPromise() {
        val restricted = ready.copy(backgroundRestricted = true)
        assertEquals(ProactiveContactStatus.READY, restricted.status)
        assertTrue(render(restricted).contains("proactive_contact_state=ready"))
        assertTrue(render(restricted).contains("background_restricted=true"))
        assertTrue(render(restricted).contains("后台受到限制"))
        assertTrue(render(ready).contains("background_restricted=unknown"))
        assertTrue(render(ready.copy(backgroundRestricted = false)).contains("background_restricted=false"))
    }

    @Test fun everySnapshotCanReflectAChangedPermissionOrPauseInsteadOfReusingOldChatClaims() {
        val snapshots = listOf(ready, ready.copy(userPaused = true), ready.copy(notificationsAllowed = false), ready)
        assertEquals(listOf("ready", "paused", "notifications_blocked", "ready"), snapshots.map {
            Regex("proactive_contact_state=([a-z_]+)").find(render(it))?.groupValues?.get(1)
        })
        assertTrue(render(ready).contains("旧对话里的能力自述不能覆盖本次状态"))
    }

    @Test fun fixedWarmStylesDeferToTheActualRuntimeInsteadOfProhibitingAllProactiveMessages() {
        for (style in listOf("warm_v1", "warm_v2")) {
            val prompt = LocalCompanionPrompt.build(CompanionProfile(mode = "preset", style = style), emptyList(), emptyList(),
                runtime = ConversationRuntime(proactiveContact = ready))
            assertTrue(style, prompt.contains("主动联系能力以本次可信运行状态为准"))
            assertFalse(style, prompt.contains("不编造正在吃饭、替用户买东西、曾线下见面或将自动提醒"))
            assertTrue(style, prompt.contains("proactive_contact_state=ready"))
        }
    }

    private fun render(contact: ProactiveContactRuntime?) =
        RuntimeDialogueContext.render(ConversationRuntime(proactiveContact = contact))
}
