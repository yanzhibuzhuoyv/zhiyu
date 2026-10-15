package com.zhiyu.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyu.app.data.planar.PlanarDocument
import com.zhiyu.app.data.planar.PlanarFrame
import com.zhiyu.app.data.planar.PlanarSurfaceView

@Composable
fun PlanarPreview(
    document: PlanarDocument,
    mouth: Float,
    idle: Boolean,
    modifier: Modifier = Modifier,
    onFailure: (RuntimeException) -> Unit = {},
    onFrame: (PlanarFrame) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reportFailure by rememberUpdatedState(onFailure)
    val reportFrame by rememberUpdatedState(onFrame)
    var failure by remember(document) { mutableStateOf<RuntimeException?>(null) }
    val surface = remember(context, document, lifecycle) {
        PlanarSurfaceView(context, document, onFailure = {
            failure = it
            reportFailure(it)
        }, onFrame = { reportFrame(it) })
    }
    DisposableEffect(surface, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surface.resumePreview()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> surface.pausePreview()
                Lifecycle.Event.ON_DESTROY -> surface.releasePreview()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) surface.resumePreview() else surface.pausePreview()
        onDispose {
            lifecycle.removeObserver(observer)
            surface.releasePreview()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (failure == null) {
            key(surface) {
                AndroidView(factory = { surface }, update = { it.update(mouth, idle) }, modifier = Modifier.fillMaxSize())
            }
        } else {
            val bitmap = remember(document) {
                val source = document.sourcePng
                BitmapFactory.decodeByteArray(source, 0, source.size)
            }
            // Display lists can outlive composition. This bounded, display-only image is GC owned.
            if (bitmap != null) Image(bitmap.asImageBitmap(), document.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Text("动态预览暂时不可用，已保留原图。", Modifier.align(Alignment.BottomCenter))
        }
    }
}
