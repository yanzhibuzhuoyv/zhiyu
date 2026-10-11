package com.zhiyu.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.zhiyu.app.domain.*
import com.zhiyu.app.ui.screens.OcCreatorDialog
import com.zhiyu.app.ui.theme.ZhiYuTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OcCreatorUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun twoDimensionalEditorReallyAddsAsymmetricPartsAndSavesParameters() {
        var saved: OcIdentity? = null
        val initial = OcIdentityRules.fresh("parts_2d")
        compose.setContent { ZhiYuTheme { OcCreatorDialog(initial, false, null, { null }, { saved = it }, {}) } }
        compose.onNodeWithText("附属物", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("oc_add_part").performScrollTo().performClick()
        compose.onNodeWithText("角色右侧").performScrollTo().performClick()
        compose.onNodeWithText("武器").performScrollTo().performClick()
        compose.onNodeWithTag("oc_add_part").performScrollTo().performClick()
        compose.onNodeWithTag("oc_save_identity").performClick()
        compose.runOnIdle {
            assertEquals(2, saved?.parts?.size)
            assertEquals("wing", saved?.parts?.get(0)?.kind); assertEquals("left", saved?.parts?.get(0)?.side)
            assertEquals("weapon", saved?.parts?.get(1)?.kind); assertEquals("right", saved?.parts?.get(1)?.side)
        }
    }
    @Test fun threeDimensionalEditorChangesFaceWhileKeepingTheSameCharacter() {
        var saved: OcIdentity? = null
        val initial = OcIdentityRules.fresh("avatar_3d")
        compose.setContent { ZhiYuTheme { OcCreatorDialog(initial, false, null, { null }, { saved = it }, {}) } }
        compose.onNodeWithTag("oc_face_width").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.2f) }
        compose.onNodeWithTag("oc_camera_yaw").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(55f) }
        compose.onNodeWithTag("oc_save_identity").performClick()
        compose.runOnIdle {
            assertEquals(initial.ocId, saved?.ocId); assertEquals("avatar_3d", saved?.route)
            assertEquals(1.2f, saved?.body?.headWidth ?: 0f, .001f)
            assertEquals(initial.revision, saved?.revision)
        }
    }
    @Test fun photoRouteCannotSaveWithoutAConfirmedImageAndExplainsLocalAnimation() {
        val initial = OcIdentityRules.fresh("photo_subject")
        compose.setContent { ZhiYuTheme { OcCreatorDialog(initial, false, null, { null }, { throw AssertionError("no source") }, {}) } }
        compose.onNodeWithTag("oc_save_identity").assertIsNotEnabled()
        compose.onNodeWithText("先在外观页面导入并确认完整人物。这里让头、呼吸、眼嘴和道具局部活动，不重画脸。单张照片没有背面和被遮住的部位。")
            .assertExists()
    }
}
