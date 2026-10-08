package com.zhiyu.app.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.AppearanceSelection
import com.zhiyu.app.domain.AppearanceTarget
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AppearanceStoreTest {
    /** A separate test preference name: user artwork, credentials and personality binding stay untouched. */
    private fun preferences(test: (Context, String, AppearanceStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "appearance_test_${UUID.randomUUID()}"
        try { test(context, name, AppearanceStore(context, name)) } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun allImageSelectionsSurviveAStoreRecreation() = preferences { context, name, store ->
        val selection = AppearanceSelection("content://photos/user", "content://photos/oc", "content://photos/background")
        store.save(selection)
        val restored = AppearanceStore(context, name).load()
        assertEquals(selection, restored.selection)
        assertTrue(restored.invalidTargets.isEmpty())
    }

    @Test fun resettingOneImageDoesNotResetTheOthers() = preferences { context, name, store ->
        val initial = AppearanceSelection("content://photos/user", "content://photos/oc", "content://photos/background")
        store.save(initial)
        store.save(initial.with(AppearanceTarget.BACKGROUND, null))
        val restored = AppearanceStore(context, name).load().selection
        assertEquals(initial.userAvatar, restored.userAvatar)
        assertEquals(initial.companionAvatar, restored.companionAvatar)
        assertNull(restored.background)
    }

    @Test fun invalidStoredImageIsReportedWithADefaultAndCanBeRepaired() = preferences { context, name, store ->
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("BACKGROUND", "https://wrong-host/image").commit()
        val loaded = store.load()
        assertNull(loaded.selection.background)
        assertEquals(listOf(AppearanceTarget.BACKGROUND), loaded.invalidTargets)
        store.save(loaded.selection)
        assertTrue(store.load().invalidTargets.isEmpty())
    }
}
