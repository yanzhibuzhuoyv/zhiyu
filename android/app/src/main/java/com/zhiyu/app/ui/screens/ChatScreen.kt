package com.zhiyu.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiyu.app.domain.ChatDisplayRules
import com.zhiyu.app.domain.ChatTypographySettings
import com.zhiyu.app.domain.Message
import com.zhiyu.app.domain.OutgoingDraft
import com.zhiyu.app.domain.OutgoingPhase
import com.zhiyu.app.domain.OutgoingQueueRules
import com.zhiyu.app.ui.AppState
import com.zhiyu.app.ui.ChatViewModel
import com.zhiyu.app.ui.AppearanceImages
import com.zhiyu.app.ui.LocalAppearanceImages
import com.zhiyu.app.ui.LocalChatTypography
import com.zhiyu.app.ui.theme.WatercolorBouquet
import com.zhiyu.app.ui.theme.WatercolorChatBackground
import java.time.ZoneId

@Composable fun ChatScreen(
    state: AppState,
    viewModel: ChatViewModel,
    typography: ChatTypographySettings = LocalChatTypography.current,
    composerActions: @Composable RowScope.() -> Unit = {},
    composerPreview: @Composable () -> Unit = {},
    composerPreparing: Boolean = false,
) {
    val list = rememberLazyListState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    val appearance = LocalAppearanceImages.current
    val companionName = state.profile?.name?.takeIf(String::isNotBlank) ?: "知语"
    FollowChatTimeline(state, list, viewportHeight, typography.fontSizeSp)
    Box(Modifier.fillMaxSize().testTag("chat_canvas")) {
        ChatBackground(appearance)
        // Scaffold consumes its padding in the caller; only this screen consumes the IME.
        Column(Modifier.fillMaxSize().imePadding()) {
            if (state.messages.isEmpty() && state.pendingMessage == null && state.pendingImage == null && state.pendingOutgoing.isEmpty()) {
                EmptyChat(Modifier.weight(1f), state.isBusy) { viewModel.draft("今天我想和你分享一件事。") }
            } else {
                ChatTimeline(state, viewModel, list, companionName, appearance, typography,
                    Modifier.weight(1f).onSizeChanged { viewportHeight = it.height })
            }
            ChatComposer(state, viewModel, typography, composerActions, composerPreview, composerPreparing)
        }
    }
}

@Composable private fun EmptyChat(modifier: Modifier, busy: Boolean, suggest: () -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        WatercolorBouquet(Modifier.width(250.dp).height(160.dp).testTag("empty_chat_watercolor_art"))
        Spacer(Modifier.height(24.dp))
        Text("我在，慢慢说。", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text("今天发生了什么，或有什么想放在心里的话？\n我们从这里开始，慢慢熟悉彼此。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        SuggestionChip(onClick = suggest, enabled = !busy, label = { Text("先聊聊今天") })
    }
}

@Composable private fun ChatTimeline(
    state: AppState, viewModel: ChatViewModel, list: LazyListState, companionName: String,
    appearance: AppearanceImages, typography: ChatTypographySettings, modifier: Modifier,
) {
    val zone = ZoneId.systemDefault()
    val separators = remember(state.messages, zone) { state.messages.mapIndexed { index, message ->
        ChatDisplayRules.separator(state.messages.getOrNull(index - 1)?.createdAt, message.createdAt, zone)
    } }
    LazyColumn(modifier.fillMaxWidth().testTag("chat_messages"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "history_controls") { HistoryControls(state, viewModel) }
        itemsIndexed(state.messages, key = { _, it -> it.id }) { index, message ->
            Column {
                separators[index]?.let { timestamp ->
                    Text(timestamp, Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("message_gap_${message.id}"),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ChatMessageBubble(message, companionName, appearance, typography, voiceContent = { MessageVoiceContent(it) })
            }
        }
        itemsIndexed(state.pendingOutgoing, key = { _, it -> "outgoing_${it.id}" }) { _, item ->
            OutgoingBubble(item, state, viewModel, appearance, typography)
        }
        if (state.pendingMessage != null || state.pendingImage != null) {
            item(key = "pending_message") { PendingBubble(state, appearance, typography) }
        }
        if (state.pendingMessage != null || state.pendingImage != null || state.busy in listOf("AI 正在输入", "AI 正在思考")) {
            item(key = "companion_typing") { TypingIndicator() }
        }
        item(key = "timeline_tail") { Spacer(Modifier.height(1.dp).testTag("chat_timeline_tail")) }
    }
}

@Composable private fun HistoryControls(state: AppState, viewModel: ChatViewModel) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.viewingOlder) TextButton(onClick = viewModel::refresh, enabled = !state.isBusy, modifier = Modifier.testTag("recent_messages")) { Text("回到最近消息") }
        if (state.hasOlder) TextButton(onClick = viewModel::loadOlder, enabled = !state.isBusy, modifier = Modifier.testTag("older_messages")) { Text("更早的消息") }
    }
}

@Composable private fun TypingIndicator() {
    Text("对方正在输入中", Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("companion_typing")
        .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun chatTextStyle(settings: ChatTypographySettings): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * 1.55f).sp)

@Composable private fun ChatComposer(
    state: AppState, viewModel: ChatViewModel, typography: ChatTypographySettings,
    actions: @Composable RowScope.() -> Unit, preview: @Composable () -> Unit, preparing: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            preview()
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions()
                OutlinedTextField(value = state.draft, onValueChange = viewModel::draft,
                    modifier = Modifier.weight(1f).heightIn(max = 180.dp).testTag("message_draft"),
                    placeholder = { Text("想和你说…") }, textStyle = chatTextStyle(typography),
                    shape = MaterialTheme.shapes.medium, maxLines = 3, enabled = state.connected && !state.restoring)
                FilledIconButton(onClick = viewModel::send, enabled = state.connected && !state.restoring && !preparing &&
                    state.pendingOutgoing.size < OutgoingQueueRules.CAPACITY && (state.draft.isNotBlank() || state.draftImage != null),
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "发送消息", Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable private fun OutgoingBubble(item: OutgoingDraft, state: AppState, viewModel: ChatViewModel,
    appearance: AppearanceImages, typography: ChatTypographySettings,
) {
    val failed = item.phase in setOf(OutgoingPhase.FAILED, OutgoingPhase.SUBMITTED_NO_REPLY)
    if (item.submittedMessageId != null && !failed) return
    Column(Modifier.fillMaxWidth().testTag("pending_outgoing_${item.id}"), horizontalAlignment = Alignment.End) {
        if (item.submittedMessageId == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("你", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(20.dp), color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))) {
                        Column(Modifier.padding(14.dp)) {
                            if (item.voiceDraftId != null) Text("语音消息", style = chatTextStyle(typography))
                            if (item.content.isNotBlank()) Text(item.content, style = chatTextStyle(typography))
                            item.image?.let { ChatMediaAttachments(listOf(it.attachment)) }
                        }
                    }
                }
                AvatarImage(appearance.userAvatar, "我的头像", Modifier.size(40.dp))
            }
        }
        if (item.phase == OutgoingPhase.QUEUED) Text("等待发出", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        item.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (failed) Row {
            if (item.phase == OutgoingPhase.FAILED && item.submittedMessageId == null) TextButton(
                onClick = { viewModel.retryOutgoing(item.id) }, enabled = state.connected,
                modifier = Modifier.testTag("retry_outgoing_${item.id}")) { Text("重试") }
            TextButton(onClick = { viewModel.discardOutgoing(item.id) }, modifier = Modifier.testTag("discard_outgoing_${item.id}")) {
                Text(if (item.submittedMessageId == null) "移除" else "知道了")
            }
        }
    }
}

@Composable private fun PendingBubble(state: AppState, appearance: AppearanceImages, typography: ChatTypographySettings) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("你", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(20.dp), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))) {
                Column(Modifier.padding(14.dp)) {
                    state.pendingMessage?.takeIf(String::isNotBlank)?.let { Text(it, style = chatTextStyle(typography)) }
                    state.pendingImage?.let { ChatMediaAttachments(listOf(it)) }
                }
            }
        }
        AvatarImage(appearance.userAvatar, "我的头像", Modifier.size(40.dp))
    }
}

@Composable private fun ChatBackground(appearance: AppearanceImages) {
    val background = appearance.background
    if (background != null) {
        Image(background.asImageBitmap(), "聊天背景", Modifier.fillMaxSize().testTag("custom_chat_background"), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)))
    } else WatercolorChatBackground(Modifier.fillMaxSize())
}
