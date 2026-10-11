package com.zhiyu.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
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
import com.zhiyu.app.domain.Message
import com.zhiyu.app.ui.VoiceController
import com.zhiyu.app.ui.VoicePhase

/** A foreground turn call; visual rendering is supplied by the confirmed OC, not a camera upload. */
@Composable fun TurnCallDialog(controller: VoiceController, name: String,
    lastAssistant: Message?, chatBusy: Boolean, chatError: String?, sendText: (String) -> Unit,
    onDismiss: () -> Unit, character: @Composable (speaking: Boolean, mouthLevel: Float) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentAssistant by rememberUpdatedState(lastAssistant)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.startListening()
        else if (!allowed) controller.permissionDenied()
    }
    fun talk() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.startListening()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(controller) { controller.beginCall(lastAssistant?.id) }
    LaunchedEffect(lastAssistant?.id, chatBusy, chatError) { controller.observeAssistant(lastAssistant, chatBusy, chatError) }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stop()
            if (event == Lifecycle.Event.ON_RESUME) controller.beginCall(currentAssistant?.id)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.stop() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    character(controller.phase == VoicePhase.SPEAKING, controller.mouthLevel)
                }
                Text(when (controller.phase) {
                    VoicePhase.IDLE -> "我在，想说什么都可以。"
                    VoicePhase.PREPARING -> "稍等一下，我马上就好。"
                    VoicePhase.LISTENING -> "我在听…"
                    VoicePhase.REVIEWING -> "看看我听得对不对。"
                    VoicePhase.WAITING -> "对方正在输入中"
                    VoicePhase.SPEAKING -> "轮到我说啦。"
                }, modifier = Modifier.testTag("turn_call_status"), style = MaterialTheme.typography.bodyMedium)
                controller.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (controller.phase == VoicePhase.LISTENING && controller.transcript.isNotBlank()) {
                    Text(controller.transcript, modifier = Modifier.heightIn(max = 100.dp), style = MaterialTheme.typography.bodyLarge)
                }
                if (controller.phase == VoicePhase.REVIEWING) {
                    OutlinedTextField(value = controller.reviewText, onValueChange = controller::editReview,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).testTag("voice_review_text"), maxLines = 4,
                        label = { Text("你刚刚说的话") })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = ::talk, enabled = !chatBusy) { Text("再说一次") }
                        Button(onClick = { controller.submit(sendText) }, enabled = !chatBusy && controller.reviewText.isNotBlank(),
                            modifier = Modifier.testTag("voice_send_review")) { Text("发送这句话") }
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (controller.phase) {
                        VoicePhase.IDLE -> FilledIconButton(onClick = ::talk, enabled = !chatBusy,
                            modifier = Modifier.size(64.dp).testTag("voice_start_turn")) { Icon(Icons.Outlined.Mic, "开始说话") }
                        VoicePhase.LISTENING -> FilledIconButton(onClick = controller::finishListening,
                            modifier = Modifier.size(64.dp).testTag("voice_finish_turn")) { Icon(Icons.Outlined.Stop, "说完了") }
                        VoicePhase.PREPARING, VoicePhase.SPEAKING -> TextButton(onClick = {
                            controller.stop(); controller.beginCall(lastAssistant?.id)
                        }) { Text("先停一下") }
                        else -> Unit
                    }
                }
                FilledTonalIconButton(onClick = { controller.stop(); onDismiss() }, modifier = Modifier.size(64.dp)
                    .testTag("voice_end_call")) { Icon(Icons.Outlined.CallEnd, "结束通话", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
