package com.zhiyu.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException

/** A call can drive the saved rig's mouth/blink/breath without changing identity or repainting a face. */
@Composable fun CharacterPreview(identity: OcIdentity?, motion: OcMotion, loadSource: suspend (OcIdentity) -> Bitmap?,
    modifier: Modifier = Modifier, cameraYaw: Float = 0f) {
    var source by remember(identity?.ocId, identity?.revision, identity?.photoSource) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(identity?.ocId, identity?.revision) { mutableStateOf(false) }
    LaunchedEffect(identity?.ocId, identity?.revision, identity?.photoSource) {
        val current = identity ?: return@LaunchedEffect
        if (current.route != "photo_subject") return@LaunchedEffect
        try { source = loadSource(current) }
        catch (error: Exception) { if (error is CancellationException) throw error; failure = true }
    }
    DisposableEffect(source) { val captured = source; onDispose { captured?.recycle() } }
    val ready = identity != null && identity.confirmed && identity.route != "unknown" &&
        (identity.route != "photo_subject" || source != null)
    if (ready && !failure) AvatarPreview(checkNotNull(identity), OcScene(cameraYaw = cameraYaw, motion = OcRigRules.motion(motion)), source, modifier)
    else Box(modifier, contentAlignment = Alignment.Center) {
        Text(if (failure) "形象暂时无法显示" else if (identity?.route == "photo_subject") "形象准备中…" else "先设定恋人的形象",
            style = MaterialTheme.typography.bodyMedium)
    }
}
