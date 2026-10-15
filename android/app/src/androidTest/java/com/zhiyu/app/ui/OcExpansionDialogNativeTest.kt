package com.zhiyu.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zhiyu.app.data.ocexpansion.*
import com.zhiyu.app.domain.*
import com.zhiyu.app.ui.screens.OcExpansionDialog
import com.zhiyu.app.ui.theme.ZhiYuTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OcExpansionDialogNativeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private class Manager:OcExpansionManagement {
        var value=OcExpansionView(OcFramingPolicy(OcAssetRef("a".repeat(32),"b".repeat(64),1),OcFraming.BUST,true,0,1),null,true,true)
        var generated=0;var adopted=0;var chosen:Boolean?=null
        val raw=byteArrayOf(137.toByte(),80,78,71,13,10,26,10,1)
        override suspend fun state(chat:String)=value
        override suspend fun chooseFraming(chat:String,framing:OcFraming,allowExpansion:Boolean):OcExpansionView {
            chosen=allowExpansion;return value.copy(policy=value.policy!!.copy(framing=framing,allowExpansion=allowExpansion))
        }
        override suspend fun generate(chat:String):OcExpansionView {generated++;return value.copy(canGenerate=false)}
        override suspend fun recheck(chat:String,requestId:String)=value
        override suspend fun preview(chat:String,requestId:String)=raw.copyOf()
        override suspend fun confirm(chat:String,requestId:String,expectedSha:String):OcExpansionView {adopted++;return value}
    }
    private fun mount(manager:Manager):OcExpansionController {
        lateinit var controller:OcExpansionController
        compose.setContent{ZhiYuTheme {
            val scope=rememberCoroutineScope()
            controller=remember {OcExpansionController(manager,scope,ScenePhotoPreviewDecoder {Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)})}
            DisposableEffect(controller){onDispose{controller.dispose()}}
            OcExpansionDialog(controller){}
        }}
        compose.runOnIdle{controller.connect("f".repeat(32))}
        compose.waitUntil(5000){controller.view!=null&&!controller.busy}
        return controller
    }
    @Test fun generationButtonExplainsFeesAndRequiresFinalConfirmation() {
        val manager=Manager();val controller=mount(manager)
        compose.onNodeWithTag("oc_expand_generate").performScrollTo().performClick()
        compose.onNodeWithText("准备全身补全候选？").assertExists();assertEquals(0,manager.generated)
        compose.onNodeWithTag("oc_expand_confirm_fee").performClick()
        compose.waitUntil(5000){!controller.busy&&manager.generated==1}
        assertEquals(0,manager.adopted)
    }
    @Test fun optingOutIsSavedWithoutCallingTheImageService() {
        val manager=Manager();val controller=mount(manager)
        compose.onNodeWithTag("oc_allow_expansion").performScrollTo().performClick()
        compose.onNodeWithTag("oc_crop_save").performScrollTo().performClick()
        compose.waitUntil(5000){!controller.busy&&manager.chosen==false}
        assertEquals(0,manager.generated);assertEquals(0,manager.adopted)
    }
    @Test fun approvedCandidateCannotBeAdoptedUntilItsPictureWasViewed() {
        val manager=Manager().apply {value=value.copy(canGenerate=false,
            candidate=OcExpansionRow("d".repeat(32),ChatPhotoRules.sha(raw),"approved",true,true))}
        val controller=mount(manager)
        compose.onNodeWithTag("oc_expand_accept").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("oc_expand_preview").performScrollTo().performClick()
        compose.waitUntil(5000){!controller.busy&&controller.preview!=null}
        compose.onNodeWithTag("oc_expand_accept").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5000){!controller.busy&&manager.adopted==1}
        assertEquals(0,manager.generated)
    }
}
