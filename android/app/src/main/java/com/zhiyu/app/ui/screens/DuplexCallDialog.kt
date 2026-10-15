package com.zhiyu.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyu.app.ui.*

@Composable fun DuplexCallDialog(controller:DuplexVoiceController,chatId:String,name:String,
    sendText:suspend(String,String)->String,onDismiss:()->Unit,
    streamText:((String,String)->kotlinx.coroutines.flow.Flow<String>)?=null,
    character: @Composable (Boolean,Float)->Unit={_,_->}) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val currentSend by rememberUpdatedState(sendText)
    val currentStream by rememberUpdatedState(streamText)
    fun connect(){controller.start(chatId,currentStream){id,text->currentSend(id,text)}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed->
        if(allowed&&lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))connect()
        else if(!allowed)controller.permissionDenied()
    }
    DisposableEffect(controller,chatId,lifecycle) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)controller.stop()}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);controller.stop()}
    }
    fun begin() {
        if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)connect()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    Dialog(onDismissRequest={controller.stop();onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(name,style=MaterialTheme.typography.headlineSmall)
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                    character(controller.phase==DuplexPhase.SPEAKING,controller.mouthLevel)
                }
                Text(when(controller.phase) {
                    DuplexPhase.IDLE->"我在，接通后随时和我说话。"
                    DuplexPhase.PREPARING->"等我一下，马上就好。"
                    DuplexPhase.LISTENING->"我在听…"
                    DuplexPhase.THINKING->"对方正在输入中"
                    DuplexPhase.SPEAKING->"你可以随时接着说。"
                    DuplexPhase.STOPPING->"正在结束通话"
                },Modifier.testTag("duplex_call_status"),style=MaterialTheme.typography.bodyMedium)
                if(controller.transcript.isNotBlank())Text(controller.transcript.takeLast(1000),
                    Modifier.heightIn(max=100.dp),style=MaterialTheme.typography.bodyLarge,maxLines=4)
                if(controller.aecEnabled==false&&controller.headset==false)Text("戴上耳机，声音会更清楚。",
                    style=MaterialTheme.typography.bodySmall)
                controller.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                Row(horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(controller.phase==DuplexPhase.IDLE)FilledIconButton(::begin,
                        Modifier.size(64.dp).testTag("duplex_connect")){Icon(Icons.Outlined.Mic,"接通")}
                    FilledTonalIconButton({controller.stop();onDismiss()},Modifier.size(64.dp).testTag("duplex_end_call")) {
                        Icon(Icons.Outlined.CallEnd,"结束通话",tint=MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
