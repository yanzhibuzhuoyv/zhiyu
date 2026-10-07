package com.zhiyu.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.zhiyu.app.domain.CompanionProfile
import com.zhiyu.app.ui.screens.FixedCompanionProfileDialog
import com.zhiyu.app.ui.screens.PresetStyleChoices
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WarmV2PresetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newSelectionUsesTheCurrentVersionWhileKeepingTheGentleDefault() {
        var chosen = "gentle"
        compose.setContent {
            var style by remember { mutableStateOf("gentle") }
            MaterialTheme { Column { PresetStyleChoices(style, true) { style = it; chosen = it } } }
        }
        compose.onNodeWithTag("style_gentle").assertIsSelected()
        compose.onNodeWithTag("style_warm_v1").assertDoesNotExist()
        compose.onNodeWithTag("style_warm_v2").assertIsNotSelected().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("warm_v2", chosen) }
        compose.onNodeWithTag("warm_preset_description")
            .assertTextEquals("性格底色稳定，也会表达不同意见或暂时让步。")
        compose.onNodeWithText("温柔相伴·试用版").assertExists()
        compose.onNodeWithTag("style_warm_v1").assertDoesNotExist()
    }

    @Test fun currentVersionBindingStillShowsAReadOnlyProfile() {
        val warm = CompanionProfile(style = "warm_v2", name = "知心", userName = "小语")
        compose.setContent { MaterialTheme { FixedCompanionProfileDialog(warm) {} } }
        compose.onNodeWithText("性格：温柔相伴·试用版", substring = true).assertExists()
        compose.onNodeWithTag("profile_locked").assertExists()
        compose.onNodeWithTag("style_warm_v2").assertDoesNotExist()
        compose.onNodeWithTag("save_profile").assertDoesNotExist()
        compose.onNodeWithTag("profile_instructions").assertDoesNotExist()
    }
}
