package com.zhiyu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.zhiyu.app.domain.planar.*
import com.zhiyu.app.ui.screens.PlanarEditorCanvas
import com.zhiyu.app.ui.theme.ZhiYuTheme
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule

class PlanarCanvasCorrectionNativeTest {
    @get:Rule val compose=createComposeRule()
    @Test fun firstPolygonPointReplacesImportedMaskRatherThanSilentlyKeepingIt(){
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xffece2d9.toInt())
        val png=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        val initial=PlanarDraft.suggest(64,64).copy(parts=listOf(PlanarPart("wing","翼","accessory",20.0,
            listOf(PlanarPoint(4.0,4.0),PlanarPoint(20.0,4.0),PlanarPoint(20.0,20.0)),PlanarPoint(10.0,10.0),
            brush=listOf(PlanarBrushStroke(listOf(PlanarPoint(8.0,8.0)),2.0,false)),baseMaskPng=Base64.getEncoder().encodeToString(png))))
        var draft by mutableStateOf(initial)
        compose.setContent{ZhiYuTheme{PlanarEditorCanvas(bitmap,draft,"wing","polygon",24.0,false,true,
            Modifier.fillMaxWidth().height(300.dp),{}, {draft=it})}}
        try {
            compose.onNodeWithTag("planar_editor_canvas").performTouchInput {click(center)}
            compose.runOnIdle {assertNull(draft.parts[0].baseMaskPng);assertEquals(1,draft.parts[0].polygon.size)
                assertTrue(draft.parts[0].brush.isEmpty());assertNotNull(initial.parts[0].baseMaskPng)
                assertEquals(3,initial.parts[0].polygon.size)}
        } finally {compose.runOnUiThread {bitmap.recycle()}}
    }
}
