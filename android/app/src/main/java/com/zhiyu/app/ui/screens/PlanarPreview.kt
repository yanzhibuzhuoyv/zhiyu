package com.zhiyu.app.ui.screens

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyu.app.data.planar.PlanarDocument
import com.zhiyu.app.data.planar.PlanarFrame
import com.zhiyu.app.data.planar.PlanarSurfaceView
import com.zhiyu.app.BuildConfig

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
    // Keep the native surface while documents change: some phone dialogs never create a
    // replacement Surface. GPU data is swapped on the existing GL thread instead.
    val owner = remember(context, lifecycle) { Any() }
    key(owner) { OwnedPlanarPreview(document, mouth, idle, modifier, lifecycle, onFailure, onFrame) }
}

@Composable private fun OwnedPlanarPreview(
    document: PlanarDocument, mouth: Float, idle: Boolean, modifier: Modifier, lifecycle: Lifecycle,
    onFailure: (RuntimeException) -> Unit, onFrame: (PlanarFrame) -> Unit,
) {
    val reportFailure by rememberUpdatedState(onFailure)
    val reportFrame by rememberUpdatedState(onFrame)
    val background=MaterialTheme.colorScheme.surface.toArgb()
    var failure by remember(document) { mutableStateOf<RuntimeException?>(null) }
    val reportRenderingFailure by rememberUpdatedState<(RuntimeException)->Unit>({failure=it;reportFailure(it)})
    var surface by remember { mutableStateOf<PlanarSurfaceView?>(null) }
    val owned = surface
    DisposableEffect(owned, lifecycle) {
        if(BuildConfig.DEBUG)Log.d("ZhiyuPlanarState","effect surface=${System.identityHashCode(owned)} lifecycle=${lifecycle.currentState}")
        val observer = LifecycleEventObserver { _, event ->
            if(BuildConfig.DEBUG)Log.d("ZhiyuPlanarState","event surface=${System.identityHashCode(owned)} lifecycle=${lifecycle.currentState} event=$event")
            when (event) {
                Lifecycle.Event.ON_RESUME -> owned?.resumePreview()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> owned?.pausePreview()
                Lifecycle.Event.ON_DESTROY -> owned?.releasePreview()
                else -> Unit
            }
        }
        if (owned != null) {
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) owned.resumePreview() else owned.pausePreview()
        }
        onDispose {
            if(BuildConfig.DEBUG)Log.d("ZhiyuPlanarState","dispose surface=${System.identityHashCode(owned)} lifecycle=${lifecycle.currentState}")
            if (owned != null) {lifecycle.removeObserver(observer);owned.releasePreview()}
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { viewContext ->
            PlanarSurfaceView(viewContext, document, onFailure = {reportRenderingFailure(it)},
                onFrame = { reportFrame(it) }).also { surface = it }
        }, update = { it.updateDocument(document);it.updateBackground(background);it.update(mouth, idle) }, modifier = Modifier.fillMaxSize(),
            onRelease = { released ->
                released.releasePreview()
                if (surface === released) surface = null
            })
        if (failure != null) {
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
