package com.zhiyu.app.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class VisualGenerationStoreTest {
    private fun isolated(test: (Context, String, VisualGenerationStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "visual_generation_test_${UUID.randomUUID()}"
        try { test(context, name, VisualGenerationStore(context, name)) } finally { context.deleteSharedPreferences(name) }
    }
    @Test fun restartAndUnknownResponseKeepTheSameRequestIdentity() = isolated { context, name, store ->
        val id = store.begin("a".repeat(32), "成年原创角色").id
        assertEquals(id, VisualGenerationStore(context, name).begin("a".repeat(32), "成年原创角色").id)
        assertThrows(IllegalArgumentException::class.java) { store.begin("a".repeat(32), "另一位角色") }
        assertNotEquals(id, store.begin("b".repeat(32), "成年原创角色").id)
    }
    @Test fun onlyConfirmedCompletionAllowsANewAttempt() = isolated { _, _, store ->
        val chat = "a".repeat(32)
        val first = store.begin(chat, "成年原创角色")
        store.complete(chat, "wrong-request")
        assertEquals(first, store.read(chat))
        store.complete(chat, first.id)
        assertNull(store.read(chat))
        assertNotEquals(first.id, store.begin(chat, "成年原创角色").id)
    }
}
