package com.zhiyu.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zhiyu.app.domain.AppearanceSelection
import com.zhiyu.app.domain.AppearanceTarget
import com.zhiyu.app.ui.AppearanceImages

@Composable
fun AppearanceDialog(
    selection: AppearanceSelection,
    images: AppearanceImages,
    busy: Boolean,
    choose: (AppearanceTarget) -> Unit,
    reset: (AppearanceTarget) -> Unit,
    close: () -> Unit,
    error: String? = null,
) {
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).testTag("appearance_dialog")) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("头像与背景", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭外观设置") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("appearance_error")) }
                    Text("选择一张喜欢的图片，让这里更像你们的空间。", style = MaterialTheme.typography.bodyMedium)
                    AppearanceTarget.entries.forEach { target ->
                        AppearanceChoice(target, images[target], selection[target] != null, busy, { choose(target) }, { reset(target) })
                    }
                    Text("图片保存在本机设置中，重新打开也会保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AppearanceChoice(target: AppearanceTarget, image: Bitmap?, selected: Boolean, busy: Boolean, choose: () -> Unit, reset: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (target == AppearanceTarget.BACKGROUND) {
                Surface(modifier = Modifier.size(width = 74.dp, height = 52.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    if (image != null) Image(image.asImageBitmap(), "聊天背景预览", contentScale = ContentScale.Crop)
                    else Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, null) }
                }
            } else AvatarImage(image, target.label, Modifier.size(52.dp))
            Column {
                Text(target.label, style = MaterialTheme.typography.titleMedium)
                Text(if (selected) "已选择图片" else "默认", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = choose, enabled = !busy, modifier = Modifier.testTag("choose_${target.name.lowercase()}")) { Text("选择图片") }
            if (selected) TextButton(onClick = reset, enabled = !busy, modifier = Modifier.testTag("reset_${target.name.lowercase()}")) { Text("恢复默认") }
        }
    }
}

@Composable
fun AvatarImage(image: Bitmap?, description: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.clip(CircleShape), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        if (image != null) Image(image.asImageBitmap(), description, contentScale = ContentScale.Crop)
        else Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PersonOutline, description, Modifier.padding(7.dp)) }
    }
}
