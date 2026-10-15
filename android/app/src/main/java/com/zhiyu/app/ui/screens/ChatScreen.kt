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
            if (state.messages.isEmpty() && state.pendingMessage == null && state.pendingImage == null) {
                EmptyChat(Modifier.weight(1f), state.isBusy) { viewModel.draft("今天我想和你分享一件事。") }
            } else {
                ChatTimeline(state, viewModel, list, companionName, appearance, typography,
                    Modifier.weight(1f).onSizeChanged { viewportHeight = it.height })
            }
            ChatComposer(state, viewModel, typography, composerActions, composerPreview)
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
                ChatMessageBubble(message, companionName, appearance, typography)
            }
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

@Composable private fun chatTextStyle(settings: ChatTypographySettings): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * 1.55f).sp)

@Composable private fun ChatComposer(
    state: AppState, viewModel: ChatViewModel, typography: ChatTypographySettings,
    actions: @Composable RowScope.() -> Unit, preview: @Composable () -> Unit,
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
                    shape = MaterialTheme.shapes.medium, maxLines = 3, enabled = !state.isBusy)
                FilledIconButton(onClick = viewModel::send, enabled = !state.isBusy && (state.draft.isNotBlank() || state.draftImage != null),
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "发送消息", Modifier.size(22.dp))
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable fun ChatMessageBubble(
    message: Message, companionName: String = "知语", appearance: AppearanceImages = AppearanceImages(),
    typography: ChatTypographySettings = LocalChatTypography.current,
) {
    val user = message.role == "user"
    val clipboard = LocalClipboardManager.current
    var expanded by remember(message.id) { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val timestamp = remember(message.createdAt, zone) { ChatDisplayRules.messageTime(message.createdAt, zone) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, if (user) Alignment.End else Alignment.Start), verticalAlignment = Alignment.Top) {
        if (!user) AvatarImage(appearance.companionAvatar, "恋人头像", Modifier.size(40.dp).padding(top = 3.dp))
        Column(Modifier.weight(1f, fill = false).widthIn(max = 640.dp), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Text(if (user) "你" else companionName, Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(topStart = if (user) 22.dp else 6.dp, topEnd = if (user) 6.dp else 22.dp, bottomEnd = 22.dp, bottomStart = 22.dp), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, if (user) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
                modifier = Modifier.widthIn(max = 640.dp).testTag("message_${message.id}")) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.content.isNotEmpty()) Text(message.content, style = chatTextStyle(typography))
                    ChatMediaAttachments(message.mediaAttachments)
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        if (message.sources.isNotEmpty()) TextButton(onClick = { expanded = !expanded }) {
                            Icon(Icons.Outlined.Description, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${message.sources.size} 条知识引用")
                        }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ContentCopy, "复制消息", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (expanded) message.sources.forEach { source ->
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(source.name, style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(source.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            if (message.origin == "call_interrupted") Text("话说到一半", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            timestamp?.let { Text(it, Modifier.padding(horizontal = 6.dp, vertical = 4.dp).testTag("message_time_${message.id}"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (user) AvatarImage(appearance.userAvatar, "我的头像", Modifier.size(40.dp).padding(top = 3.dp))
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
