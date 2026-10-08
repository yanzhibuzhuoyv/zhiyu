package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class AppearanceRulesTest {
    @Test fun acceptsOnlyDocumentContentUris() {
        assertEquals("content://media/external/images/17", AppearanceRules.documentUri("content://media/external/images/17"))
        listOf("https://host/photo.jpg", "file:///storage/photo.jpg", "content:///photo", "content://user:password@host/photo")
            .forEach { value -> assertThrows(IllegalArgumentException::class.java) { AppearanceRules.documentUri(value) } }
    }

    @Test fun boundsLargeBackgroundWithoutDistortingPortraitPhoto() {
        assertEquals(ImageDimensions(1536, 2048), AppearanceRules.decodedSize(6000, 8000, AppearanceTarget.BACKGROUND))
        assertEquals(ImageDimensions(512, 384), AppearanceRules.decodedSize(4000, 3000, AppearanceTarget.COMPANION_AVATAR))
        assertEquals(ImageDimensions(100, 200), AppearanceRules.decodedSize(100, 200, AppearanceTarget.USER_AVATAR))
    }

    @Test fun rejectsBrokenAndExcessiveImageDimensionsBeforeAllocatingPixels() {
        listOf(0 to 100, -1 to 2, 40000 to 10, 20000 to 20000).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) { AppearanceRules.decodedSize(width, height, AppearanceTarget.BACKGROUND) }
        }
    }

    @Test fun replacingAndResettingOneSelectionPreservesOtherSelections() {
        val original = AppearanceSelection("content://photos/user", "content://photos/oc", "content://photos/background")
        val changed = original.with(AppearanceTarget.USER_AVATAR, "content://photos/new-user")
        assertEquals("content://photos/user", original.userAvatar)
        assertEquals(original.companionAvatar, changed.companionAvatar)
        assertEquals(original.background, changed.background)
        assertNull(changed.with(AppearanceTarget.BACKGROUND, null).background)
        assertEquals("content://photos/new-user", changed.with(AppearanceTarget.BACKGROUND, null).userAvatar)
    }
}
