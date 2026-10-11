package com.zhiyu.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhiyu.app.domain.ChatImageDraft
import com.zhiyu.app.ui.ChatPhotoController

@Composable fun RowScope.ChatPhotoAction(controller: ChatPhotoController, enabled: Boolean) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.choose(uri)
    }
    IconButton(onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
        enabled = enabled && !controller.busy, modifier = Modifier.size(48.dp).testTag("choose_chat_photo")) {
        Icon(Icons.Outlined.Image, "发送图片")
    }
}

@Composable fun ChatPhotoPreview(controller: ChatPhotoController, draft: ChatImageDraft?, enabled: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        controller.error?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).testTag("chat_photo_error"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                IconButton(onClick = controller::dismissError) { Icon(Icons.Outlined.Close, "关闭图片提示") }
            }
        }
        if (draft != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("chat_photo_draft"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            controller.preview?.let { bitmap ->
                Image(bitmap.asImageBitmap(), "准备发送的图片", Modifier.size(72.dp), contentScale = ContentScale.Fit)
            }
            Text("想分享的图片", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { controller.remove(draft) }, enabled = enabled && !controller.busy,
                modifier = Modifier.testTag("remove_chat_photo")) { Icon(Icons.Outlined.Close, "移除待发送图片") }
        }
    }
}
