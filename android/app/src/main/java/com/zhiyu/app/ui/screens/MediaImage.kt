package com.zhiyu.app.ui.screens

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zhiyu.app.domain.MediaAttachment
import com.zhiyu.app.ui.LocalMediaController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun ChatMediaAttachments(values: List<MediaAttachment>) {
    values.forEach { attachment ->
        MediaImage(attachment.id, attachment.sha256, attachment.kind == "sticker",
            if (attachment.kind == "sticker") "恋人发来的表情包" else "恋人分享的 OC 素材")
    }
}

@Composable fun MediaImage(id: String, sha256: String? = null, sticker: Boolean = false, description: String = "OC 参考图", template: Boolean = false) {
    val controller = LocalMediaController.current ?: return
    val resources = LocalContext.current.resources
    var drawable by remember(controller, id, sha256, template, sticker) { mutableStateOf<Drawable?>(null) }
    var failure by remember(controller, id, sha256, template, sticker) { mutableStateOf<String?>(null) }
    var retry by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(controller, id, sha256, template, sticker, retry) {
        failure = null
        drawable = null
        try {
            val bytes = controller.image(id, sha256, template)
            drawable = withContext(Dispatchers.IO) { MediaDrawableDecoder.decode(resources, bytes, sticker) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure = "图片暂时无法显示"
        }
    }
    DisposableEffect(drawable) {
        if (Build.VERSION.SDK_INT >= 28) (drawable as? AnimatedImageDrawable)?.start()
        onDispose { if (Build.VERSION.SDK_INT >= 28) (drawable as? AnimatedImageDrawable)?.stop() }
    }
    val size = if (sticker) Modifier.size(132.dp) else Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp)
    Box(size.padding(vertical = 4.dp).testTag("media_$id")) {
        when {
            failure != null -> TextButton(onClick = { retry++ }) { Text("图片加载失败，点击重试") }
            drawable == null -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> AndroidView(factory = { context -> ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true
            } }, update = { view -> view.setImageDrawable(drawable); view.contentDescription = description }, modifier = Modifier.fillMaxWidth())
        }
    }
}
