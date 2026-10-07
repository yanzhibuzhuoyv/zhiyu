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

/** Exercises only composables with synthetic profiles; never opens or clears user credentials. */
class WarmPresetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialPresetDraftIsRetainedUntilTheUserChoosesAnotherStyle() {
        var chosen = "warm_v1"
        compose.setContent {
            var style by remember { mutableStateOf("warm_v1") }
            MaterialTheme { Column { PresetStyleChoices(style, true) { style = it; chosen = it } } }
        }
        compose.onNodeWithTag("style_warm_v1").assertIsSelected()
        compose.onNodeWithText("温柔相伴·初版").assertExists()
        compose.runOnIdle { assertEquals("warm_v1", chosen) }
        compose.onNodeWithTag("warm_preset_description")
            .assertTextEquals("亲昵自然，陪你分享日常；需要时再一起忆旧。")
        compose.onNodeWithTag("style_playful").performClick().assertIsSelected()
        compose.onNodeWithTag("warm_preset_description").assertDoesNotExist()
        compose.onNodeWithTag("style_steady").assertExists()
        compose.onNodeWithTag("style_gentle").assertExists()
    }

    @Test fun busyStateDoesNotAllowSelectingANewPreset() {
        compose.setContent { MaterialTheme { Column { PresetStyleChoices("gentle", false) {} } } }
        compose.onNodeWithTag("style_warm_v2").assertIsNotEnabled().assertIsNotSelected()
        compose.onNodeWithTag("style_warm_v1").assertDoesNotExist()
        compose.onNodeWithTag("style_gentle").assertIsSelected()
    }

    @Test fun boundWarmPresetDisplaysTheVersionedNameAndHasNoEditControls() {
        val warm = CompanionProfile(style = "warm_v1", role = "girlfriend", name = "知心", userName = "小语")
        compose.setContent { MaterialTheme { FixedCompanionProfileDialog(warm) {} } }
        compose.onNodeWithText("已固定的恋人设定").assertExists()
        compose.onNodeWithText("性格：温柔相伴·初版", substring = true).assertExists()
        compose.onNodeWithText("恋人名字：知心", substring = true).assertExists()
        compose.onNodeWithTag("profile_locked").assertExists()
        compose.onNodeWithTag("style_warm_v1").assertDoesNotExist()
        compose.onNodeWithTag("save_profile").assertDoesNotExist()
        compose.onNodeWithTag("profile_instructions").assertDoesNotExist()
    }
}
