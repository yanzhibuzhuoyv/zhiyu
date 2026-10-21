package com.zhiyu.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyu.app.data.LocalVoicePlayback
import com.zhiyu.app.data.voicemessages.*
import com.zhiyu.app.domain.Message
import com.zhiyu.app.ui.ChatVoiceController
import com.zhiyu.app.ui.ChatVoicePhase
import kotlinx.coroutines.*

@Composable fun ChatVoiceAction(controller: ChatVoiceController, enabled: Boolean) {
    val context = LocalContext.current
    val currentEnabled by rememberUpdatedState(enabled)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && currentEnabled) controller.start()
    }
    IconButton(onClick = {
        if (controller.capturing) controller.finish()
        else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }, enabled = enabled && controller.draft == null, modifier = Modifier.testTag("voice_record")) {
        Icon(if (controller.capturing) Icons.Outlined.Stop else Icons.Outlined.Mic,
            if (controller.capturing) "结束录音" else "录制语音消息")
    }
}

@Composable fun ChatVoiceDraft(controller: ChatVoiceController, send: suspend (String, String) -> Boolean) {
    if (controller.phase == ChatVoicePhase.IDLE && controller.error == null) return
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().testTag("voice_draft")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(when (controller.phase) {
                ChatVoicePhase.PREPARING -> "麦克风准备中…"
                ChatVoicePhase.RECORDING -> "正在录音 ${controller.seconds}秒 · 最长60秒"
                ChatVoicePhase.TRANSCRIBING -> "正在听清这段话…"
                ChatVoicePhase.PLAYING -> "试听中"
                else -> "语音 ${controller.draft?.durationMillis?.div(1000) ?: 0}秒"
            }, style = MaterialTheme.typography.labelMedium)
            if (controller.transcript.isNotEmpty()) Text(controller.transcript, maxLines = 3, style = MaterialTheme.typography.bodySmall)
            controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (controller.capturing) TextButton(controller::finish) { Text("结束录音") }
                if (controller.draft != null) {
                    TextButton(controller::preview) { Text(if (controller.phase == ChatVoicePhase.PLAYING) "停止试听" else "试听") }
                    TextButton({ controller.send(send) }, enabled = controller.phase == ChatVoicePhase.READY,
                        modifier = Modifier.testTag("voice_send")) { Text("发送语音") }
                }
                TextButton(controller::cancel) { Text("放弃") }
            }
        }
    }
}

/** The raw transcript stays readable even if local synthesis fails. Audio is a separate durable attachment. */
@Composable fun MessageVoiceContent(message: Message) {
    val clipId = VoiceMessageOrigins.clipId(message.origin) ?: return
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember(context) { VoiceMessageStore(context) }
    val playback = remember(context) { LocalVoicePlayback(context) }
    var record by remember(clipId) { mutableStateOf<VoiceClipRecord?>(null) }
    var playing by remember(clipId) { mutableStateOf(false) }
    var error by remember(clipId) { mutableStateOf<String?>(null) }
    var job by remember(clipId) { mutableStateOf<Job?>(null) }
    var transcriptOpen by remember(clipId) { mutableStateOf(false) }
    LaunchedEffect(clipId) {
        repeat(300) {
            record = try { withContext(Dispatchers.IO) { store.find(clipId) } }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "这段语音暂时无法读取"; return@LaunchedEffect }
            if (record?.state in setOf(VoiceClipState.READY, VoiceClipState.FAILED)) return@LaunchedEffect
            delay(600)
        }
        error = "语音暂时没准备好，文字仍保留在这里"
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(playback, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { playback.stop(); job?.cancel(); playing = false } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); playback.stop(); job?.cancel() }
    }
    val ready = record?.state == VoiceClipState.READY
    TextButton(onClick = {
        if (playing) { playback.stop(); job?.cancel(); playing = false }
        else {
            playing = true; error = null
            job = scope.launch {
                try {
                    val selected = checkNotNull(record)
                    val audio = withContext(Dispatchers.IO) { store.read(clipId) }
                    playback.play(audio, volume = selected.volume, pitchSemitones = selected.pitchSemitones, maximumSeconds = 180)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = "播放暂时中断，可以再听一次"
                } finally { playing = false }
            }
        }
    }, enabled = ready, modifier = Modifier.testTag("voice_message_${message.id}")) {
        Text(when {
            playing -> "停止播放"
            ready -> "▶ 语音 ${((record?.durationMillis ?: 0) + 999) / 1000}秒"
            record?.state == VoiceClipState.FAILED -> "语音暂不可用，先看文字吧"
            else -> "语音正在准备…"
        })
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    val fallback = record?.state == VoiceClipState.FAILED || error != null
    if (fallback || transcriptOpen) Text(message.content, style = chatTextStyle(com.zhiyu.app.ui.LocalChatTypography.current),
        modifier = Modifier.testTag("voice_transcript_${message.id}"))
    if (!fallback) TextButton({ transcriptOpen = !transcriptOpen }, modifier = Modifier.testTag("voice_transcript_toggle_${message.id}")) {
        Text(if (transcriptOpen) "收起文字" else "查看文字")
    }
}
