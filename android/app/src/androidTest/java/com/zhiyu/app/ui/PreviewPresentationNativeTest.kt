package com.zhiyu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreviewPresentationNativeTest {
    @get:Rule val compose=createComposeRule()
    @Test fun oldDisplayListRetainsSafePixelsAfterTheControllerRecyclesItsBuffer() {
        val color=0xffbc8579.toInt()
        val raw=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).also{it.eraseColor(color)}
        var tick by mutableIntStateOf(0)
        compose.setContent {Column {
            Image(rememberPreviewPresentation(raw),"safe preview",Modifier.size(100.dp).testTag("presentation"))
            Text("frame $tick")
        }}
        compose.onNodeWithTag("presentation").assertExists()
        compose.runOnIdle {raw.recycle();tick++}
        compose.onNodeWithText("frame 1").assertExists()
        val pixels=compose.onNodeWithTag("presentation").captureToImage().toPixelMap()
        assertTrue(raw.isRecycled)
        assertEquals(color,pixels[pixels.width/2,pixels.height/2].toArgb())
    }
}
