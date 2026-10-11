package com.zhiyu.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.zhiyu.app.domain.*
import com.zhiyu.app.ui.screens.OcCreatorDialog
import com.zhiyu.app.ui.theme.ZhiYuTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OcCreatorUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun historicalTwoDimensionalRouteCannotOpenAModelEditor() = assertNoModelEditor("parts_2d")
    @Test fun historicalThreeDimensionalRouteCannotOpenAModelEditor() = assertNoModelEditor("avatar_3d")

    private fun assertNoModelEditor(route: String) {
        val initial = OcIdentityRules.fresh(route)
        compose.setContent { ZhiYuTheme {
            OcCreatorDialog(initial, false, null, { null }, { throw AssertionError("model save reached") }, {})
        } }
        compose.onNodeWithText("请通过导入或生成图片设置恋人外观。").assertIsDisplayed()
        compose.onNodeWithTag("oc_face_width").assertDoesNotExist()
        compose.onNodeWithTag("oc_add_part").assertDoesNotExist()
        compose.onNodeWithTag("oc_camera_yaw").assertDoesNotExist()
        compose.onNodeWithTag("oc_save_identity").assertDoesNotExist()
    }
    @Test fun photoRouteCannotSaveWithoutAConfirmedImageAndExplainsLocalAnimation() {
        val initial = OcIdentityRules.fresh("photo_subject")
        compose.setContent { ZhiYuTheme { OcCreatorDialog(initial, false, null, { null }, { throw AssertionError("no source") }, {}) } }
        compose.onNodeWithTag("oc_save_identity").assertIsNotEnabled()
        compose.onNodeWithText("先在外观页面导入并确认完整人物。这里让头、呼吸、眼嘴和道具局部活动，不重画脸。单张照片没有背面和被遮住的部位。")
            .assertExists()
    }
}
