package com.zhiyu.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhiyu.app.domain.ChatDisplayRules
import com.zhiyu.app.domain.ChatTypographySettings
import com.zhiyu.app.domain.Message
import com.zhiyu.app.domain.ResponseSegment
import com.zhiyu.app.domain.ResponseSegments
import com.zhiyu.app.ui.AppearanceImages
import com.zhiyu.app.ui.LocalChatTypography
import java.time.ZoneId

/** A stored reply keeps its identity and original; only its visual presentation has several bubbles. */
@Suppress("DEPRECATION")
@Composable fun ChatMessageBubble(
    message: Message, companionName: String = "知语", appearance: AppearanceImages = AppearanceImages(),
    typography: ChatTypographySettings = LocalChatTypography.current,
    voiceContent: @Composable (Message) -> Unit = {},
) {
    val user = message.role == "user"
    var expanded by remember(message.id) { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val timestamp = remember(message.createdAt, zone) { ChatDisplayRules.messageTime(message.createdAt, zone) }
    val segments = remember(message.id, message.role, message.content, message.origin) {
        if (com.zhiyu.app.data.voicemessages.VoiceMessageOrigins.isVoice(message.origin)) listOf(ResponseSegment(0, 0, ""))
        else if (user || message.content.isEmpty()) listOf(ResponseSegment(0, message.content.length, message.content))
        else ResponseSegments.split(message.content)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, if (user) Alignment.End else Alignment.Start), verticalAlignment = Alignment.Top) {
        if (!user) AvatarImage(appearance.companionAvatar, "恋人头像", Modifier.size(40.dp).padding(top = 3.dp))
        Column(Modifier.weight(1f, fill = false).widthIn(max = 640.dp).testTag("message_${message.id}"),
            horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Text(if (user) "你" else companionName, Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            segments.forEachIndexed { index, segment ->
                val last = index == segments.lastIndex
                MessagePart(message, segment, index, user, typography, last, expanded,
                    { expanded = !expanded }, voiceContent)
                if (last && message.origin == "call_interrupted") Text("话说到一半", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                timestamp?.let {
                    Text(it, Modifier.padding(horizontal = 6.dp, vertical = 4.dp).testTag(
                        if (last) "message_time_${message.id}" else "message_segment_time_${message.id}_$index"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!last) Spacer(Modifier.height(6.dp))
            }
        }
        if (user) AvatarImage(appearance.userAvatar, "我的头像", Modifier.size(40.dp).padding(top = 3.dp))
    }
}

@Composable private fun MessagePart(message: Message, segment: ResponseSegment, index: Int, user: Boolean,
    typography: ChatTypographySettings, last: Boolean, expanded: Boolean, toggleSources: () -> Unit,
    voiceContent: @Composable (Message) -> Unit,
) {
    Surface(shape = RoundedCornerShape(topStart = if (user) 22.dp else 6.dp, topEnd = if (user) 6.dp else 22.dp,
        bottomEnd = 22.dp, bottomStart = 22.dp), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (user) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
        modifier = Modifier.widthIn(max = 640.dp).testTag("message_segment_${message.id}_$index")) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            val displayed = if (user) segment.text else segment.text.trim()
            if (displayed.isNotEmpty()) Text(displayed, style = chatTextStyle(typography))
            if (last) {
                voiceContent(message)
                ChatMediaAttachments(message.mediaAttachments)
                MessageTools(message, expanded, toggleSources)
            }
        }
    }
}

@Composable private fun ColumnScope.MessageTools(message: Message, expanded: Boolean, toggleSources: () -> Unit) {
    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (message.sources.isNotEmpty()) TextButton(onClick = toggleSources) {
            Icon(Icons.Outlined.Description, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
            Text("${message.sources.size} 条知识引用")
        }
    }
    if (expanded) message.sources.forEach { source ->
        HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text(source.name, style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text(source.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
