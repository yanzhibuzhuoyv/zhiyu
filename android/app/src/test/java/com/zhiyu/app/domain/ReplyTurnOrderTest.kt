package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class ReplyTurnOrderTest {
    private fun message(id: String, role: String) = Message(id, role, "原文$id", emptyList(), "2026-10-18T00:00:00Z")
    @Test fun presentationGroupsUsersBeforeAnswersWithoutChangingAnOriginal() {
        val raw = listOf(message("u1", "user"), message("a1", "assistant"), message("u2", "user"), message("a2", "assistant"))
        val ordered = ReplyTurnOrder.apply(raw, listOf(listOf("u1", "u2", "a1", "a2")))
        assertEquals(listOf("u1", "u2", "a1", "a2"), ordered.map { it.id })
        assertEquals(raw.toSet(), ordered.toSet())
        assertEquals(listOf("u1", "a1", "u2", "a2"), raw.map { it.id })
    }
    @Test fun partialPagesAndUnrelatedMessagesKeepTheirOrder() {
        val first = message("before", "assistant"); val last = message("after", "user")
        val page = listOf(first, message("u2", "user"), message("a2", "assistant"), last)
        assertEquals(page, ReplyTurnOrder.apply(page, listOf(listOf("u1", "u2", "a1", "a2"))))
    }
    @Test fun duplicateReceiptMembersAreRejectedRatherThanDuplicatingMessages() {
        assertTrue(runCatching { ReplyTurnOrder.apply(emptyList(), listOf(listOf("u", "u"))) }.isFailure)
    }
}
