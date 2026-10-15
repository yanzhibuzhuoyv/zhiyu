package com.zhiyu.app.ui

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.ui.screens.DuplexCallDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.*
import org.junit.Assert.*
import rig2d.voice.*
import java.util.concurrent.atomic.AtomicInteger

class DuplexCallDialogNativeTest {
    @get:Rule val compose=createComposeRule()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val acquired=AtomicInteger();private val closed=AtomicInteger()
    private val controller=DuplexVoiceController(scope,{source,_->DuplexPortsFactory {
        acquired.incrementAndGet()
        DuplexPorts(object:VoiceCapture {
            override fun frames()=flow<VoicePcm>{awaitCancellation()}
            override fun close(){closed.incrementAndGet()}
        },object:VoiceRecognizer {
            override suspend fun open(stamp:VoiceStamp):VoiceRecognition=error("no synthetic speech")
            override fun close(){closed.incrementAndGet()}
        },source,object:VoiceSynthesizer {
            override suspend fun synthesize(text:String,stamp:VoiceStamp,active:()->Boolean):VoicePcm=error("no synthetic speech")
            override fun cancel(stamp:VoiceStamp)=Unit
            override fun close(){closed.incrementAndGet()}
        },object:VoicePlayer {
            override suspend fun play(audio:VoicePcm,stamp:VoiceStamp,onLevel:(Float)->Unit)=Unit
            override fun stop(stamp:VoiceStamp?)=Unit
            override fun close(){closed.incrementAndGet()}
        })
    }})
    @Before fun grant() {
        val i=InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(
            "pm grant ${i.targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}")).use{it.readBytes()}
    }
    @After fun cleanup(){compose.runOnUiThread{controller.dispose()};scope.cancel()}
    @Test fun openingOnlyShowsOriginalCharacterAndDoesNotStartMicrophoneUntilConnect() {
        compose.setContent {DuplexCallDialog(controller,"a".repeat(32),"知语",{_,_->error("no speech")},{},character={_,_->Text("原背景角色")})}
        compose.onNodeWithText("原背景角色").assertExists();compose.onNodeWithTag("duplex_connect").assertExists()
        assertEquals(0,acquired.get())
        compose.onNodeWithTag("duplex_connect").performClick()
        compose.waitUntil(5000){acquired.get()==1&&controller.phase==DuplexPhase.LISTENING}
        compose.onNodeWithTag("duplex_call_status").assertTextEquals("我在听…")
        compose.onNodeWithTag("voice_review_text").assertDoesNotExist()
    }
    @Test fun endingTheDialogClosesAllPortsAndNeverAutomaticallyReconnects() {
        var visible by mutableStateOf(true);var dismissals=0
        compose.setContent {if(visible)DuplexCallDialog(controller,"a".repeat(32),"知语",{_,_->error("no speech")},
            {dismissals++;visible=false})}
        compose.onNodeWithTag("duplex_connect").performClick()
        compose.waitUntil(5000){acquired.get()==1&&controller.phase==DuplexPhase.LISTENING}
        compose.onNodeWithTag("duplex_end_call").performClick()
        compose.waitUntil(5000){closed.get()==4&&controller.phase==DuplexPhase.IDLE}
        assertEquals(1,dismissals);assertEquals(1,acquired.get())
        compose.onNodeWithTag("duplex_connect").assertDoesNotExist()
    }
}
